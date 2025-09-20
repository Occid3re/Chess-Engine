package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.RookHelper;

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
    private static final int DEFENDER_BONUS = 5;
    private static final int QUEEN_ATTACKED_PENALTY = -135;
    private static final int BACKRANK_WEAKNESS_MIDGAME_PENALTY = -30;
    private static final int BACKRANK_WEAKNESS_ENDGAME_PENALTY = -15;

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
    private KingSafetyView currentView = KingSafetyView.empty();
    private boolean initialized;
    private boolean dirty = true;
    private int midgameScoreCache;
    private int endgameScoreCache;

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
        if (board == null) {
            for (SideState state : sideStates) {
                state.reset();
            }
            midgameScoreCache = 0;
            endgameScoreCache = 0;
            currentView = KingSafetyView.empty();
            dirty = false;
            return currentView;
        }
        long whiteAttacks = board.getAttackBitboard(true);
        long blackAttacks = board.getAttackBitboard(false);
        rebuildSideState(sideStates[WHITE], board, true, whiteAttacks, blackAttacks);
        rebuildSideState(sideStates[BLACK], board, false, blackAttacks, whiteAttacks);
        refreshScoreCaches();
        dirty = false;
        return currentView;
    }

    public KingSafetyView getView(BitBoard board) {
        return evaluate(board);
    }

    private void rebuildFromContext(EvaluationContext context) {
        BitBoard board = context.getBoard();
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
        BitBoard previousBoard = previous.getBoard();
        BitBoard currentBoard = current.getBoard();
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
        if (state.backrankMask != 0 && ((prevFriendlyAttacks ^ currFriendlyAttacks) & state.backrankMask) != 0) {
            return true;
        }
        return false;
    }

    private void rebuildSideState(SideState state, BitBoard board, boolean isWhite, long friendlyAttacks, long enemyAttacks) {
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
            state.filePenalty = (enemyPawns & state.fileMask) != 0 ? HALF_OPEN_FILE_PENALTY : OPEN_FILE_PENALTY;
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

        int shieldPenalty = state.missingShield * MISSING_PAWN_SHIELD_PENALTY;
        int attackPenalty = -state.totalAttackWeight;
        int defenderBonus = state.defenderCount * DEFENDER_BONUS;
        computeBackrankWeaknessPenalty(state, board, isWhite, friendlyAttacks);
        int baseMidgame = shieldPenalty + state.filePenalty + attackPenalty + defenderBonus;
        state.midgameKingSafety = baseMidgame + state.backrankWeaknessMidgame;
        state.endgameKingSafety = baseMidgame / 2 + state.backrankWeaknessEndgame;

        long queens = isWhite ? board.getWhiteQueens() : board.getBlackQueens();
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

    private void computeBackrankWeaknessPenalty(SideState state, BitBoard board, boolean isWhite,
                                                long friendlyAttacks) {
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

        if ((friendlyAttacks & backrankMask) != 0) {
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
                PhaseScore.of(sideStates[BLACK].midgameKingSafety, sideStates[BLACK].endgameKingSafety),
                PhaseScore.of(sideStates[WHITE].midgameQueenPenalty, sideStates[WHITE].endgameQueenPenalty),
                PhaseScore.of(sideStates[BLACK].midgameQueenPenalty, sideStates[BLACK].endgameQueenPenalty)
        );
    }

    public KingSafetyView getCurrentView() {
        return currentView;
    }

    public static final class KingSafetyView {
        private final PhaseScore whiteKing;
        private final PhaseScore blackKing;
        private final PhaseScore whiteQueen;
        private final PhaseScore blackQueen;

        private KingSafetyView(PhaseScore whiteKing, PhaseScore blackKing,
                               PhaseScore whiteQueen, PhaseScore blackQueen) {
            this.whiteKing = whiteKing;
            this.blackKing = blackKing;
            this.whiteQueen = whiteQueen;
            this.blackQueen = blackQueen;
        }

        public static KingSafetyView empty() {
            PhaseScore zero = PhaseScore.of(0, 0);
            return new KingSafetyView(zero, zero, zero, zero);
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
        private final int midgame;
        private final int endgame;

        private PhaseScore(int midgame, int endgame) {
            this.midgame = midgame;
            this.endgame = endgame;
        }

        public static PhaseScore of(int midgame, int endgame) {
            return new PhaseScore(midgame, endgame);
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
