package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.ImmutableBoardView;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.RookHelper;
import julius.game.chessengine.tuning.Tuning;

import java.util.Arrays;
import java.util.Objects;

import static julius.game.chessengine.helper.BitHelper.FileMasks;
import static julius.game.chessengine.helper.BitHelper.RankMasks;
import static julius.game.chessengine.helper.KingHelper.KING_ATTACKS;
import static julius.game.chessengine.helper.KnightHelper.knightMoveTable;
import static julius.game.chessengine.helper.PawnMoveTables.PAWN_ATTACKS;

/**
 * Tracks tapered king and queen safety penalties for both sides.  The module maintains a per-side
 * map from king-zone squares to aggregated attacker weights together with pawn-shield metadata so
 * the evaluation pipeline can refresh the score incrementally.
 */
public final class KingSafetyModule implements EvaluationModule {

    private static final int WHITE = 0;
    private static final int BLACK = 1;

    private static final int PAWN = MoveHelper.pieceTypeToInt(PieceType.PAWN);
    private static final int KNIGHT = MoveHelper.pieceTypeToInt(PieceType.KNIGHT);
    private static final int BISHOP = MoveHelper.pieceTypeToInt(PieceType.BISHOP);
    private static final int ROOK = MoveHelper.pieceTypeToInt(PieceType.ROOK);
    private static final int QUEEN = MoveHelper.pieceTypeToInt(PieceType.QUEEN);
    private static final int KING = MoveHelper.pieceTypeToInt(PieceType.KING);

    private static final long NOT_A_FILE = ~FileMasks[0];
    private static final long NOT_H_FILE = ~FileMasks[7];

    private static final BishopHelper BISHOP_HELPER = BishopHelper.getInstance();
    private static final RookHelper ROOK_HELPER = RookHelper.getInstance();

    private final int missingPawnShieldPenalty;
    private final int halfOpenFilePenalty;
    private final int openFilePenalty;
    private final int defenderBonus;
    private final int queenAttackedPenalty;
    private final int backrankWeaknessMidgamePenalty;
    private final int backrankWeaknessEndgamePenalty;
    private final int pawnStormMidgamePenalty;
    private final int pawnStormEndgamePenalty;
    private final int[] attackWeights = new int[7];

    private final SideState[] sideStates = {new SideState(), new SideState()};
    private final KingSafetyView currentView = new KingSafetyView();
    private boolean initialized;
    private boolean dirty = true;
    private int midgameScoreCache;
    private int endgameScoreCache;

    public KingSafetyModule() {
        this.missingPawnShieldPenalty = Tuning.missingPawnShieldPenalty();
        this.halfOpenFilePenalty = Tuning.halfOpenFilePenalty();
        this.openFilePenalty = Tuning.openFilePenalty();
        this.defenderBonus = Tuning.defenderBonus();
        this.queenAttackedPenalty = Tuning.queenAttackedPenalty();
        this.backrankWeaknessMidgamePenalty = Tuning.backrankWeaknessMidgamePenalty();
        this.backrankWeaknessEndgamePenalty = Tuning.backrankWeaknessEndgamePenalty();
        this.pawnStormMidgamePenalty = Tuning.kingSafetyPawnStormMidgamePenalty();
        this.pawnStormEndgamePenalty = Tuning.kingSafetyPawnStormEndgamePenalty();
        attackWeights[PAWN] = Tuning.kingSafetyPawnAttackWeight();
        attackWeights[KNIGHT] = Tuning.kingSafetyKnightAttackWeight();
        attackWeights[BISHOP] = Tuning.kingSafetyBishopAttackWeight();
        attackWeights[ROOK] = Tuning.kingSafetyRookAttackWeight();
        attackWeights[QUEEN] = Tuning.kingSafetyQueenAttackWeight();
    }

    @Override
    public void initialize(EvaluationContext context) {
        Objects.requireNonNull(context, "context");
        rebuildFromContext(context);
        initialized = true;
    }

    @Override
    public void evaluate(EvaluationContext context) {
        if (!dirty) {
            return;
        }
        rebuildFromContext(Objects.requireNonNull(context, "context"));
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        if (!ensureInitialized(moveContext)) {
            return;
        }
        EvaluationContext previous = moveContext.getPreviousContext();
        EvaluationContext current = moveContext.getCurrentContext();
        if (previous == null || current == null) {
            rebuildFromContext(current);
            return;
        }
        int move = moveContext.getMove();
        int movedPiece = MoveHelper.derivePieceTypeBits(move);
        int capturedPiece = MoveHelper.deriveCapturedPieceTypeBits(move);
        if (movedPiece == KING || capturedPiece == KING) {
            rebuildFromContext(current);
            return;
        }
        boolean updateWhite = sideNeedsUpdate(WHITE, moveContext);
        boolean updateBlack = sideNeedsUpdate(BLACK, moveContext);
        if (updateWhite || updateBlack) {
            rebuildFromContext(current);
            return;
        }
        // No relevant king-safety features were affected by the move; keep the cached
        // contribution rather than forcing a full rebuild on the next evaluation cycle.
        dirty = false;
    }

    @Override
    public void undoMove(MoveContext moveContext) {
        applyMove(moveContext);
    }

    @Override
    public int getMidgameScore() {
        return midgameScoreCache;
    }

    @Override
    public int getEndgameScore() {
        return endgameScoreCache;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void markDirty() {
        dirty = true;
    }

    public KingSafetyView evaluate(BitBoard board) {
        if (board == null) {
            for (SideState state : sideStates) {
                state.reset();
            }
            midgameScoreCache = 0;
            endgameScoreCache = 0;
            currentView.reset();
            dirty = false;
            return currentView;
        }
        ImmutableBoardView view = ImmutableBoardView.from(board);
        long whiteAttacks = view.getWhiteAttackMap();
        long blackAttacks = view.getBlackAttackMap();
        rebuildSideState(sideStates[WHITE], view, true, whiteAttacks, blackAttacks);
        rebuildSideState(sideStates[BLACK], view, false, blackAttacks, whiteAttacks);
        refreshScoreCaches();
        dirty = false;
        return currentView;
    }

    public KingSafetyView getView(BitBoard board) {
        return evaluate(board);
    }

    private void rebuildFromContext(EvaluationContext context) {
        ImmutableBoardView board = Objects.requireNonNull(context, "context").getBoardView();
        long whiteAttacks = context.getWhiteAttackMap();
        long blackAttacks = context.getBlackAttackMap();
        rebuildSideState(sideStates[WHITE], board, true, whiteAttacks, blackAttacks);
        rebuildSideState(sideStates[BLACK], board, false, blackAttacks, whiteAttacks);
        refreshScoreCaches();
        dirty = false;
    }

    private boolean ensureInitialized(MoveContext moveContext) {
        if (initialized) {
            return true;
        }
        EvaluationContext context = moveContext.getCurrentContext();
        if (context == null) {
            return false;
        }
        initialize(context);
        return true;
    }

    private boolean sideNeedsUpdate(int side, MoveContext moveContext) {
        SideState state = sideStates[side];
        if (state.kingSquare < 0) {
            return true;
        }
        EvaluationContext previous = moveContext.getPreviousContext();
        EvaluationContext current = moveContext.getCurrentContext();
        if (previous == null || current == null) {
            return true;
        }
        long prevEnemyAttacks = side == WHITE ? previous.getBlackAttackMap() : previous.getWhiteAttackMap();
        long currEnemyAttacks = side == WHITE ? current.getBlackAttackMap() : current.getWhiteAttackMap();
        long prevFriendlyAttacks = side == WHITE ? previous.getWhiteAttackMap() : previous.getBlackAttackMap();
        long currFriendlyAttacks = side == WHITE ? current.getWhiteAttackMap() : current.getBlackAttackMap();
        long zoneDiff = (prevEnemyAttacks ^ currEnemyAttacks) & state.kingZone;
        if (zoneDiff != 0) {
            return true;
        }
        int move = moveContext.getMove();
        int from = MoveHelper.deriveFromIndex(move);
        int to = MoveHelper.deriveToIndex(move);
        long moveMask = (1L << from) | (1L << to);
        if ((moveMask & (state.kingZone | state.shieldMask | state.fileMask)) != 0) {
            return true;
        }
        int movedPiece = MoveHelper.derivePieceTypeBits(move);
        if (movedPiece == PAWN) {
            if (((1L << from) & state.fileMask) != 0 || ((1L << to) & state.fileMask) != 0) {
                return true;
            }
        }
        int capturedPiece = MoveHelper.deriveCapturedPieceTypeBits(move);
        if (capturedPiece == PAWN) {
            long captureMask = 1L << to;
            if (MoveHelper.isEnPassantMove(move)) {
                captureMask = side == WHITE ? (1L << (to - 8)) : (1L << (to + 8));
            }
            if ((captureMask & (state.shieldMask | state.fileMask)) != 0) {
                return true;
            }
        }
        ImmutableBoardView previousBoard = previous.getBoardView();
        ImmutableBoardView currentBoard = current.getBoardView();
        long prevQueens = side == WHITE ? previousBoard.getWhiteQueens() : previousBoard.getBlackQueens();
        long currQueens = side == WHITE ? currentBoard.getWhiteQueens() : currentBoard.getBlackQueens();
        if (prevQueens != currQueens) {
            return true;
        }
        long queenMask = currQueens;
        while (queenMask != 0) {
            long q = queenMask & -queenMask;
            if (((prevEnemyAttacks ^ currEnemyAttacks) & q) != 0) {
                return true;
            }
            queenMask ^= q;
        }
        return state.backrankMask != 0 && ((prevFriendlyAttacks ^ currFriendlyAttacks) & state.backrankMask) != 0;
    }

    private void rebuildSideState(SideState state, ImmutableBoardView board, boolean isWhite,
                                  long friendlyAttacks, long enemyAttacks) {
        state.reset();
        long kingBits = isWhite ? board.getWhiteKing() : board.getBlackKing();
        if (kingBits == 0) {
            return;
        }
        int kingSquare = Long.numberOfTrailingZeros(kingBits);
        state.kingSquare = kingSquare;
        state.kingZone = KING_ATTACKS[kingSquare];
        state.shieldMask = computeShieldMask(kingBits, isWhite);
        state.fileMask = FileMasks[kingSquare & 7];
        state.backrankMask = 0L;
        int rank = kingSquare / 8;
        if ((isWhite && rank == 0) || (!isWhite && rank == 7)) {
            state.backrankMask = computeBackrankMask(kingSquare);
        }

        long friendlyPawns = isWhite ? board.getWhitePawns() : board.getBlackPawns();
        long enemyPawns = isWhite ? board.getBlackPawns() : board.getWhitePawns();

        int shieldCount = Long.bitCount(friendlyPawns & state.shieldMask);
        state.missingShield = Math.max(0, 3 - shieldCount);

        boolean friendlyPawnOnFile = (friendlyPawns & state.fileMask) != 0;
        if (!friendlyPawnOnFile) {
            state.filePenalty = (enemyPawns & state.fileMask) != 0 ? halfOpenFilePenalty : openFilePenalty;
        }

        state.defenderCount = Long.bitCount(friendlyAttacks & state.kingZone);

        long allPieces = board.getAllPieces();
        accumulatePawnAttacks(state, isWhite ? board.getBlackPawns() : board.getWhitePawns(), !isWhite);
        accumulateKnightAttacks(state, isWhite ? board.getBlackKnights() : board.getWhiteKnights());
        accumulateBishopAttacks(state, isWhite ? board.getBlackBishops() : board.getWhiteBishops(), allPieces);
        accumulateRookAttacks(state, isWhite ? board.getBlackRooks() : board.getWhiteRooks(), allPieces);
        accumulateQueenAttacks(state, isWhite ? board.getBlackQueens() : board.getWhiteQueens(), allPieces);

        int total = 0;
        long zone = state.kingZone;
        while (zone != 0) {
            int sq = Long.numberOfTrailingZeros(zone);
            total += state.zoneAttackWeights[sq];
            zone &= zone - 1;
        }
        state.totalAttackWeight = total;

        int shieldPenalty = state.missingShield * missingPawnShieldPenalty;
        int attackPenalty = -state.totalAttackWeight;
        int defenderBonus = state.defenderCount * this.defenderBonus;
        int pawnStormPenalty = computePawnStormPenalty(enemyPawns, kingSquare, isWhite);
        computeBackrankWeaknessPenalty(state, board, isWhite);
        int baseMidgame = shieldPenalty + state.filePenalty + attackPenalty + defenderBonus + pawnStormPenalty;
        state.midgameKingSafety = baseMidgame + state.backrankWeaknessMidgame;
        state.endgameKingSafety = baseMidgame / 2 + state.backrankWeaknessEndgame;

        long queens = isWhite ? board.getWhiteQueens() : board.getBlackQueens();
        int queenMid = 0;
        int queenEnd = 0;
        long remaining = queens;
        while (remaining != 0) {
            long q = remaining & -remaining;
            if ((enemyAttacks & q) != 0) {
                queenMid += queenAttackedPenalty;
                queenEnd += queenAttackedPenalty;
            }
            remaining ^= q;
        }
        state.midgameQueenPenalty = queenMid;
        state.endgameQueenPenalty = queenEnd;
    }

    private int countPawnStormPawns(long enemyPawns, int kingSquare, boolean defendingWhite) {
        if (kingSquare < 0 || enemyPawns == 0) {
            return 0;
        }
        int kingFile = kingSquare & 7;
        int minFile = Math.max(0, kingFile - 1);
        int maxFile = Math.min(7, kingFile + 1);
        long fileMask = 0L;
        for (int file = minFile; file <= maxFile; file++) {
            fileMask |= FileMasks[file];
        }
        long rankMask = defendingWhite
                ? (RankMasks[2] | RankMasks[3] | RankMasks[4])
                : (RankMasks[3] | RankMasks[4] | RankMasks[5]);
        long storm = enemyPawns & fileMask & rankMask;
        return Long.bitCount(storm);
    }

    private void computeBackrankWeaknessPenalty(SideState state, ImmutableBoardView board, boolean isWhite) {
        state.backrankWeaknessMidgame = 0;
        state.backrankWeaknessEndgame = 0;
        if (state.kingSquare < 0) {
            return;
        }
        int rank = state.kingSquare / 8;
        if ((isWhite && rank != 0) || (!isWhite && rank != 7)) {
            return;
        }

        long kingMask = 1L << state.kingSquare;
        long escapeSquares = computeEscapeSquares(kingMask, isWhite);
        long occupiedEscape = escapeSquares & board.getAllPieces();
        if ((escapeSquares & ~occupiedEscape) != 0) {
            return;
        }

        long backrankMask = state.backrankMask;
        if (backrankMask == 0L) {
            return;
        }

        long friendlyNonKingAttacks = computeFriendlyNonKingAttacks(board, isWhite);
        if ((friendlyNonKingAttacks & backrankMask) != 0) {
            return;
        }

        state.backrankWeaknessMidgame = backrankWeaknessMidgamePenalty;
        state.backrankWeaknessEndgame = backrankWeaknessEndgamePenalty;
    }

    private static long computeEscapeSquares(long kingMask, boolean isWhite) {
        long escape = 0L;
        if (isWhite) {
            escape |= kingMask << 8;
            if ((kingMask & NOT_A_FILE) != 0) {
                escape |= kingMask << 7;
            }
            if ((kingMask & NOT_H_FILE) != 0) {
                escape |= kingMask << 9;
            }
        } else {
            escape |= kingMask >>> 8;
            if ((kingMask & NOT_A_FILE) != 0) {
                escape |= kingMask >>> 9;
            }
            if ((kingMask & NOT_H_FILE) != 0) {
                escape |= kingMask >>> 7;
            }
        }
        return escape;
    }

    private static long computeBackrankMask(int kingSquare) {
        long mask = 1L << kingSquare;
        int file = kingSquare & 7;
        if (file > 0) {
            mask |= 1L << (kingSquare - 1);
        }
        if (file < 7) {
            mask |= 1L << (kingSquare + 1);
        }
        return mask;
    }

    private long computeFriendlyNonKingAttacks(ImmutableBoardView board, boolean isWhite) {
        long attacks = 0L;
        long occupancy = board.getAllPieces();

        long pawns = isWhite ? board.getWhitePawns() : board.getBlackPawns();
        int pawnColor = isWhite ? WHITE : BLACK;
        long remaining = pawns;
        while (remaining != 0) {
            long pawn = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(pawn);
            attacks |= PAWN_ATTACKS[pawnColor][index];
            remaining ^= pawn;
        }

        long knights = isWhite ? board.getWhiteKnights() : board.getBlackKnights();
        remaining = knights;
        while (remaining != 0) {
            long knight = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(knight);
            attacks |= knightMoveTable[index];
            remaining ^= knight;
        }

        long bishops = isWhite ? board.getWhiteBishops() : board.getBlackBishops();
        remaining = bishops;
        while (remaining != 0) {
            long bishop = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(bishop);
            long mask = BISHOP_HELPER.bishopMasks[index];
            attacks |= BISHOP_HELPER.calculateMovesUsingBishopMagic(index, occupancy & mask);
            remaining ^= bishop;
        }

        long rooks = isWhite ? board.getWhiteRooks() : board.getBlackRooks();
        remaining = rooks;
        while (remaining != 0) {
            long rook = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(rook);
            long mask = ROOK_HELPER.rookMasks[index];
            attacks |= ROOK_HELPER.calculateMovesUsingRookMagic(index, occupancy & mask);
            remaining ^= rook;
        }

        long queens = isWhite ? board.getWhiteQueens() : board.getBlackQueens();
        remaining = queens;
        while (remaining != 0) {
            long queen = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(queen);
            long bishopMask = BISHOP_HELPER.bishopMasks[index];
            long rookMask = ROOK_HELPER.rookMasks[index];
            long diagonal = BISHOP_HELPER.calculateMovesUsingBishopMagic(index, occupancy & bishopMask);
            long straight = ROOK_HELPER.calculateMovesUsingRookMagic(index, occupancy & rookMask);
            attacks |= diagonal | straight;
            remaining ^= queen;
        }

        return attacks;
    }

    private void accumulatePawnAttacks(SideState state, long pawns, boolean pawnsAreWhite) {
        if (pawns == 0 || state.kingZone == 0) {
            return;
        }
        int color = pawnsAreWhite ? WHITE : BLACK;
        long remaining = pawns;
        while (remaining != 0) {
            long pawn = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(pawn);
            long attacks = PAWN_ATTACKS[color][index];
            addAttacks(state, attacks, attackWeights[PAWN]);
            remaining ^= pawn;
        }
    }

    private void accumulateKnightAttacks(SideState state, long knights) {
        if (knights == 0 || state.kingZone == 0) {
            return;
        }
        long remaining = knights;
        while (remaining != 0) {
            long knight = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(knight);
            long attacks = knightMoveTable[index];
            addAttacks(state, attacks, attackWeights[KNIGHT]);
            remaining ^= knight;
        }
    }

    private void accumulateBishopAttacks(SideState state, long bishops, long occupancy) {
        if (bishops == 0 || state.kingZone == 0) {
            return;
        }
        long remaining = bishops;
        while (remaining != 0) {
            long bishop = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(bishop);
            long mask = BISHOP_HELPER.bishopMasks[index];
            long attacks = BISHOP_HELPER.calculateMovesUsingBishopMagic(index, occupancy & mask);
            addAttacks(state, attacks, attackWeights[BISHOP]);
            remaining ^= bishop;
        }
    }

    private void accumulateRookAttacks(SideState state, long rooks, long occupancy) {
        if (rooks == 0 || state.kingZone == 0) {
            return;
        }
        long remaining = rooks;
        while (remaining != 0) {
            long rook = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(rook);
            long mask = ROOK_HELPER.rookMasks[index];
            long attacks = ROOK_HELPER.calculateMovesUsingRookMagic(index, occupancy & mask);
            addAttacks(state, attacks, attackWeights[ROOK]);
            remaining ^= rook;
        }
    }

    private void accumulateQueenAttacks(SideState state, long queens, long occupancy) {
        if (queens == 0 || state.kingZone == 0) {
            return;
        }
        long remaining = queens;
        while (remaining != 0) {
            long queen = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(queen);
            long bishopMask = BISHOP_HELPER.bishopMasks[index];
            long rookMask = ROOK_HELPER.rookMasks[index];
            long diagonal = BISHOP_HELPER.calculateMovesUsingBishopMagic(index, occupancy & bishopMask);
            long straight = ROOK_HELPER.calculateMovesUsingRookMagic(index, occupancy & rookMask);
            addAttacks(state, diagonal | straight, attackWeights[QUEEN]);
            remaining ^= queen;
        }
    }

    private void addAttacks(SideState state, long attacks, int weight) {
        long relevant = attacks & state.kingZone;
        while (relevant != 0) {
            int square = Long.numberOfTrailingZeros(relevant);
            state.zoneAttackWeights[square] += weight;
            relevant &= relevant - 1;
        }
    }

    /**
     * Penalty for enemy pawns advancing toward our king. Pawns on the same or
     * adjacent files within 3 ranks of the king receive a distance-based penalty.
     */
    private static int computePawnStormPenalty(long enemyPawns, int kingSquare, boolean isWhite) {
        if (enemyPawns == 0 || kingSquare < 0) {
            return 0;
        }
        int kingFile = kingSquare & 7;
        int kingRank = kingSquare >> 3;
        int penalty = 0;
        long remaining = enemyPawns;
        while (remaining != 0) {
            int sq = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            int pawnFile = sq & 7;
            int pawnRank = sq >> 3;
            int fileDist = Math.abs(pawnFile - kingFile);
            if (fileDist > 1) continue; // only same file and adjacent files
            // Distance in ranks toward the king
            int rankDist = isWhite ? (pawnRank - kingRank) : (kingRank - pawnRank);
            if (rankDist <= 0) continue; // pawn is behind or on same rank as king
            if (rankDist <= 3) {
                // Closer pawn = bigger threat: 3 ranks away = -4, 2 = -8, 1 = -12
                penalty -= (4 - rankDist) * 4;
            }
        }
        return penalty;
    }

    private static long computeShieldMask(long kingBits, boolean isWhite) {
        long mask = 0L;
        if (kingBits == 0) {
            return mask;
        }
        if (isWhite) {
            mask |= kingBits << 8;
            if ((kingBits & NOT_A_FILE) != 0) {
                mask |= kingBits << 7;
            }
            if ((kingBits & NOT_H_FILE) != 0) {
                mask |= kingBits << 9;
            }
        } else {
            mask |= kingBits >>> 8;
            if ((kingBits & NOT_A_FILE) != 0) {
                mask |= kingBits >>> 9;
            }
            if ((kingBits & NOT_H_FILE) != 0) {
                mask |= kingBits >>> 7;
            }
        }
        return mask;
    }

    private void refreshScoreCaches() {
        int whiteMid = sideStates[WHITE].midgameKingSafety + sideStates[WHITE].midgameQueenPenalty;
        int whiteEnd = sideStates[WHITE].endgameKingSafety + sideStates[WHITE].endgameQueenPenalty;
        int blackMid = sideStates[BLACK].midgameKingSafety + sideStates[BLACK].midgameQueenPenalty;
        int blackEnd = sideStates[BLACK].endgameKingSafety + sideStates[BLACK].endgameQueenPenalty;
        midgameScoreCache = whiteMid - blackMid;
        endgameScoreCache = whiteEnd - blackEnd;
        currentView.update(
                sideStates[WHITE].midgameKingSafety, sideStates[WHITE].endgameKingSafety,
                sideStates[BLACK].midgameKingSafety, sideStates[BLACK].endgameKingSafety,
                sideStates[WHITE].midgameQueenPenalty, sideStates[WHITE].endgameQueenPenalty,
                sideStates[BLACK].midgameQueenPenalty, sideStates[BLACK].endgameQueenPenalty
        );
    }

    public KingSafetyView getCurrentView() {
        return currentView;
    }

    public static final class KingSafetyView {
        private final PhaseScore whiteKing = new PhaseScore();
        private final PhaseScore blackKing = new PhaseScore();
        private final PhaseScore whiteQueen = new PhaseScore();
        private final PhaseScore blackQueen = new PhaseScore();

        private void update(int whiteKingMid, int whiteKingEnd,
                             int blackKingMid, int blackKingEnd,
                             int whiteQueenMid, int whiteQueenEnd,
                             int blackQueenMid, int blackQueenEnd) {
            whiteKing.update(whiteKingMid, whiteKingEnd);
            blackKing.update(blackKingMid, blackKingEnd);
            whiteQueen.update(whiteQueenMid, whiteQueenEnd);
            blackQueen.update(blackQueenMid, blackQueenEnd);
        }

        private void reset() {
            update(0, 0, 0, 0, 0, 0, 0, 0);
        }

        public PhaseScore whiteKing() {
            return whiteKing;
        }

        public PhaseScore blackKing() {
            return blackKing;
        }

        public PhaseScore whiteQueen() {
            return whiteQueen;
        }

        public PhaseScore blackQueen() {
            return blackQueen;
        }

        public int whiteMidgameTotal() {
            return whiteKing.midgame + whiteQueen.midgame;
        }

        public int whiteEndgameTotal() {
            return whiteKing.endgame + whiteQueen.endgame;
        }

        public int blackMidgameTotal() {
            return blackKing.midgame + blackQueen.midgame;
        }

        public int blackEndgameTotal() {
            return blackKing.endgame + blackQueen.endgame;
        }
    }

    public static final class PhaseScore {
        private int midgame;
        private int endgame;

        private PhaseScore() {
        }

        private void update(int midgame, int endgame) {
            this.midgame = midgame;
            this.endgame = endgame;
        }

        public int blend(int phase) {
            int clamped = clampPhase(phase);
            int midWeight = 256 - clamped;
            int endWeight = clamped;
            long blended = (long) midgame * midWeight + (long) endgame * endWeight;
            return (int) (blended / 256);
        }

        private static int clampPhase(int phase) {
            if (phase < 0) {
                return 0;
            }
            if (phase > 256) {
                return 256;
            }
            return phase;
        }
    }

    private static final class SideState {
        private int kingSquare = -1;
        private long kingZone;
        private long shieldMask;
        private long fileMask;
        private int missingShield;
        private int filePenalty;
        private int defenderCount;
        private int totalAttackWeight;
        private int midgameKingSafety;
        private int endgameKingSafety;
        private int midgameQueenPenalty;
        private int endgameQueenPenalty;
        private int backrankWeaknessMidgame;
        private int backrankWeaknessEndgame;
        private final int[] zoneAttackWeights = new int[64];
        private long backrankMask;

        private void reset() {
            kingSquare = -1;
            kingZone = 0L;
            shieldMask = 0L;
            fileMask = 0L;
            missingShield = 0;
            filePenalty = 0;
            defenderCount = 0;
            totalAttackWeight = 0;
            midgameKingSafety = 0;
            endgameKingSafety = 0;
            midgameQueenPenalty = 0;
            endgameQueenPenalty = 0;
            backrankWeaknessMidgame = 0;
            backrankWeaknessEndgame = 0;
            backrankMask = 0L;
            Arrays.fill(zoneAttackWeights, 0);
        }
    }
}
