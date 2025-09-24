package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.RookHelper;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

import static julius.game.chessengine.helper.BitHelper.FileMasks;
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

    private static final int MISSING_PAWN_SHIELD_PENALTY = -15;
    private static final int HALF_OPEN_FILE_PENALTY = -15;
    private static final int OPEN_FILE_PENALTY = -25;
    private static final int DEFENDER_BONUS = 10;
    private static final int QUEEN_ATTACKED_PENALTY = -75;
    private static final int BACKRANK_WEAKNESS_MIDGAME_PENALTY = -100;
    private static final int BACKRANK_WEAKNESS_ENDGAME_PENALTY = -50;

    private static final int[] ATTACK_WEIGHTS = new int[7];

    private static final long NOT_A_FILE = ~FileMasks[0];
    private static final long NOT_H_FILE = ~FileMasks[7];

    private static final BishopHelper BISHOP_HELPER = BishopHelper.getInstance();
    private static final RookHelper ROOK_HELPER = RookHelper.getInstance();

    static {
        ATTACK_WEIGHTS[PAWN] = 5;
        ATTACK_WEIGHTS[KNIGHT] = 10;
        ATTACK_WEIGHTS[BISHOP] = 10;
        ATTACK_WEIGHTS[ROOK] = 15;
        ATTACK_WEIGHTS[QUEEN] = 20;
    }

    private final SideState[] sideStates = {new SideState(), new SideState()};
    @Getter
    private KingSafetyView currentView = KingSafetyView.empty();
    private boolean dirty = true;
    private int midgameScoreCache;
    private int endgameScoreCache;

    @Override
    public void initialize(EvaluationContext context) {
        Objects.requireNonNull(context, "context");
        rebuildFromContext(context);
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
        EvaluationContext previous = moveContext.previousContext();
        EvaluationContext current = moveContext.currentContext();
        if (previous == null) {
            rebuildFromContext(current);
            return;
        }
        int move = moveContext.move();
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
        }
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
        EvaluationContext.BoardView view = board == null ? null : EvaluationContext.BoardView.from(board);
        long whiteAttacks = board == null ? 0L : board.getAttackBitboard(true);
        long blackAttacks = board == null ? 0L : board.getAttackBitboard(false);
        rebuildFromSnapshot(view, whiteAttacks, blackAttacks);
        return currentView;
    }

    public KingSafetyView getView(BitBoard board) {
        return evaluate(board);
    }

    private void rebuildFromContext(EvaluationContext context) {
        if (context == null) {
            rebuildFromSnapshot(null, 0L, 0L);
            return;
        }
        rebuildFromSnapshot(context.board(), context.whiteAttackMap(), context.blackAttackMap());
    }

    private void rebuildFromSnapshot(EvaluationContext.BoardView board, long whiteAttacks, long blackAttacks) {
        for (SideState state : sideStates) {
            state.reset();
        }
        if (board == null) {
            midgameScoreCache = 0;
            endgameScoreCache = 0;
            currentView = KingSafetyView.empty();
            dirty = false;
            return;
        }
        rebuildSideState(sideStates[WHITE], board, true, whiteAttacks, blackAttacks);
        rebuildSideState(sideStates[BLACK], board, false, blackAttacks, whiteAttacks);
        refreshScoreCaches();
        dirty = false;
    }

    private boolean sideNeedsUpdate(int side, MoveContext moveContext) {
        SideState state = sideStates[side];
        if (state.kingSquare < 0) {
            return true;
        }
        EvaluationContext previous = moveContext.previousContext();
        EvaluationContext current = moveContext.currentContext();
        if (previous == null) {
            return true;
        }
        long prevEnemyAttacks = side == WHITE ? previous.blackAttackMap() : previous.whiteAttackMap();
        long currEnemyAttacks = side == WHITE ? current.blackAttackMap() : current.whiteAttackMap();
        long prevFriendlyAttacks = side == WHITE ? previous.whiteAttackMap() : previous.blackAttackMap();
        long currFriendlyAttacks = side == WHITE ? current.whiteAttackMap() : current.blackAttackMap();
        long zoneDiff = (prevEnemyAttacks ^ currEnemyAttacks) & state.kingZone;
        if (zoneDiff != 0) {
            return true;
        }
        int move = moveContext.move();
        int from = MoveHelper.deriveFromIndex(move);
        int to = MoveHelper.deriveToIndex(move);
        long moveMask = (1L << from) | (1L << to);
        if ((moveMask & (state.kingZone | state.shieldMask)) != 0) {
            return true;
        }
        int movedPiece = MoveHelper.derivePieceTypeBits(move);
        boolean isWhiteSide = side == WHITE;
        if (movedPiece == PAWN) {
            long fileMask = state.fileMask;
            if (fileMask != 0 && (((1L << from) | (1L << to)) & fileMask) != 0) {
                return fileOccupancyChanged(state, previous.board(), current.board(), isWhiteSide);
            }
        }
        int capturedPiece = MoveHelper.deriveCapturedPieceTypeBits(move);
        if (capturedPiece == PAWN) {
            long captureMask = 1L << to;
            if (MoveHelper.isEnPassantMove(move)) {
                captureMask = side == WHITE ? (1L << (to - 8)) : (1L << (to + 8));
            }
            if ((captureMask & state.shieldMask) != 0) {
                return true;
            }
            if ((captureMask & state.fileMask) != 0) {
                return fileOccupancyChanged(state, previous.board(), current.board(), isWhiteSide);
            }
        }
        EvaluationContext.BoardView previousBoard = previous.board();
        EvaluationContext.BoardView currentBoard = current.board();
        long prevQueens = side == WHITE ? previousBoard.whiteQueens() : previousBoard.blackQueens();
        long currQueens = side == WHITE ? currentBoard.whiteQueens() : currentBoard.blackQueens();
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

    private boolean fileOccupancyChanged(SideState state,
                                         EvaluationContext.BoardView previousBoard,
                                         EvaluationContext.BoardView currentBoard,
                                         boolean sideIsWhite) {
        if (previousBoard == null || currentBoard == null) {
            return true;
        }
        long fileMask = state.fileMask;
        if (fileMask == 0) {
            return false;
        }
        long prevFriendly = sideIsWhite ? previousBoard.whitePawns() : previousBoard.blackPawns();
        long currFriendly = sideIsWhite ? currentBoard.whitePawns() : currentBoard.blackPawns();
        if (Long.bitCount(prevFriendly & fileMask) != Long.bitCount(currFriendly & fileMask)) {
            return true;
        }
        long prevEnemy = sideIsWhite ? previousBoard.blackPawns() : previousBoard.whitePawns();
        long currEnemy = sideIsWhite ? currentBoard.blackPawns() : currentBoard.whitePawns();
        return Long.bitCount(prevEnemy & fileMask) != Long.bitCount(currEnemy & fileMask);
    }

    private void rebuildSideState(SideState state, EvaluationContext.BoardView board, boolean isWhite,
                                  long friendlyAttacks, long enemyAttacks) {
        state.reset();
        long kingBits = isWhite ? board.whiteKing() : board.blackKing();
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

        long friendlyPawns = isWhite ? board.whitePawns() : board.blackPawns();
        long enemyPawns = isWhite ? board.blackPawns() : board.whitePawns();

        int shieldCount = Long.bitCount(friendlyPawns & state.shieldMask);
        state.missingShield = Math.max(0, 3 - shieldCount);

        boolean friendlyPawnOnFile = (friendlyPawns & state.fileMask) != 0;
        if (!friendlyPawnOnFile) {
            state.filePenalty = (enemyPawns & state.fileMask) != 0 ? HALF_OPEN_FILE_PENALTY : OPEN_FILE_PENALTY;
        }

        state.defenderCount = Long.bitCount(friendlyAttacks & state.kingZone);

        long allPieces = board.allPieces();
        accumulatePawnAttacks(state, enemyPawns, !isWhite);
        accumulateKnightAttacks(state, isWhite ? board.blackKnights() : board.whiteKnights());
        accumulateBishopAttacks(state, isWhite ? board.blackBishops() : board.whiteBishops(), allPieces);
        accumulateRookAttacks(state, isWhite ? board.blackRooks() : board.whiteRooks(), allPieces);
        accumulateQueenAttacks(state, isWhite ? board.blackQueens() : board.whiteQueens(), allPieces);

        int total = 0;
        long zone = state.kingZone;
        while (zone != 0) {
            int sq = Long.numberOfTrailingZeros(zone);
            total += state.zoneAttackWeights[sq];
            zone &= zone - 1;
        }
        state.totalAttackWeight = total;

        int shieldPenalty = state.missingShield * MISSING_PAWN_SHIELD_PENALTY;
        int attackPenalty = -state.totalAttackWeight;
        int defenderBonus = state.defenderCount * DEFENDER_BONUS;
        computeBackrankWeaknessPenalty(state, board, isWhite);
        int baseMidgame = shieldPenalty + state.filePenalty + attackPenalty + defenderBonus;
        state.midgameKingSafety = baseMidgame + state.backrankWeaknessMidgame;
        state.endgameKingSafety = baseMidgame / 2 + state.backrankWeaknessEndgame;

        long queens = isWhite ? board.whiteQueens() : board.blackQueens();
        int queenMid = 0;
        int queenEnd = 0;
        long remaining = queens;
        while (remaining != 0) {
            long q = remaining & -remaining;
            if ((enemyAttacks & q) != 0) {
                queenMid += QUEEN_ATTACKED_PENALTY;
                queenEnd += QUEEN_ATTACKED_PENALTY;
            }
            remaining ^= q;
        }
        state.midgameQueenPenalty = queenMid;
        state.endgameQueenPenalty = queenEnd;
    }

    private void computeBackrankWeaknessPenalty(SideState state, EvaluationContext.BoardView board, boolean isWhite) {
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
        long occupiedEscape = escapeSquares & board.allPieces();
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

        state.backrankWeaknessMidgame = BACKRANK_WEAKNESS_MIDGAME_PENALTY;
        state.backrankWeaknessEndgame = BACKRANK_WEAKNESS_ENDGAME_PENALTY;
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

    private long computeFriendlyNonKingAttacks(EvaluationContext.BoardView board, boolean isWhite) {
        long attacks = 0L;
        long occupancy = board.allPieces();

        long pawns = isWhite ? board.whitePawns() : board.blackPawns();
        int pawnColor = isWhite ? WHITE : BLACK;
        long remaining = pawns;
        while (remaining != 0) {
            long pawn = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(pawn);
            attacks |= PAWN_ATTACKS[pawnColor][index];
            remaining ^= pawn;
        }

        remaining = isWhite ? board.whiteKnights() : board.blackKnights();
        while (remaining != 0) {
            long knight = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(knight);
            attacks |= knightMoveTable[index];
            remaining ^= knight;
        }

        remaining = isWhite ? board.whiteBishops() : board.blackBishops();
        while (remaining != 0) {
            long bishop = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(bishop);
            long mask = BISHOP_HELPER.bishopMasks[index];
            attacks |= BISHOP_HELPER.calculateMovesUsingBishopMagic(index, occupancy & mask);
            remaining ^= bishop;
        }

        remaining = isWhite ? board.whiteRooks() : board.blackRooks();
        while (remaining != 0) {
            long rook = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(rook);
            long mask = ROOK_HELPER.rookMasks[index];
            attacks |= ROOK_HELPER.calculateMovesUsingRookMagic(index, occupancy & mask);
            remaining ^= rook;
        }

        remaining = isWhite ? board.whiteQueens() : board.blackQueens();
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
            addAttacks(state, attacks, ATTACK_WEIGHTS[PAWN]);
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
            addAttacks(state, attacks, ATTACK_WEIGHTS[KNIGHT]);
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
            addAttacks(state, attacks, ATTACK_WEIGHTS[BISHOP]);
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
            addAttacks(state, attacks, ATTACK_WEIGHTS[ROOK]);
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
            addAttacks(state, diagonal | straight, ATTACK_WEIGHTS[QUEEN]);
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
        currentView = new KingSafetyView(
                PhaseScore.of(sideStates[WHITE].midgameKingSafety, sideStates[WHITE].endgameKingSafety),
                PhaseScore.of(sideStates[BLACK].midgameKingSafety, sideStates[BLACK].endgameKingSafety)
        );
    }

    public record KingSafetyView(PhaseScore whiteKing, PhaseScore blackKing) {

        public static KingSafetyView empty() {
                PhaseScore zero = PhaseScore.of(0, 0);
                return new KingSafetyView(zero, zero);
            }

        }

    public record PhaseScore(int midgame, int endgame) {

        public static PhaseScore of(int midgame, int endgame) {
                return new PhaseScore(midgame, endgame);
            }

            public int blend(int phase) {
                int endWeight = clampPhase(phase);
                int midWeight = 256 - endWeight;
                long blended = (long) midgame * midWeight + (long) endgame * endWeight;
                return (int) (blended / 256);
            }

            private static int clampPhase(int phase) {
                if (phase < 0) {
                    return 0;
                }
                return Math.min(phase, 256);
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
