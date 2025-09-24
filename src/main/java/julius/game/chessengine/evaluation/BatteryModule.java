package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;

/**
 * Rewards coordinated long-range pieces that form batteries (stacked along the same ray) and
 * penalizes the opponent when the battery directly targets valuable pieces. The module only
 * recomputes its contribution when marked dirty.
 */
public final class BatteryModule implements EvaluationModule {

    private static final int PAWN = MoveHelper.pieceTypeToInt(PieceType.PAWN);
    private static final int KNIGHT = MoveHelper.pieceTypeToInt(PieceType.KNIGHT);
    private static final int BISHOP = MoveHelper.pieceTypeToInt(PieceType.BISHOP);
    private static final int ROOK = MoveHelper.pieceTypeToInt(PieceType.ROOK);
    private static final int QUEEN = MoveHelper.pieceTypeToInt(PieceType.QUEEN);
    private static final int KING = MoveHelper.pieceTypeToInt(PieceType.KING);

    private static final int BATTERY_FORMATION_MIDGAME = 12;
    private static final int BATTERY_FORMATION_ENDGAME = 8;
    private static final int BATTERY_RAY_CONTROL_MIDGAME = 1;
    private static final int BATTERY_RAY_CONTROL_ENDGAME = 1;

    private static final int[] BATTERY_ATTACK_MIDGAME = new int[7];
    private static final int[] BATTERY_ATTACK_ENDGAME = new int[7];

    private static final Direction[] DIAGONAL_DIRECTIONS = {
            new Direction(1, 1),   // north-east
            new Direction(1, -1)   // north-west
    };

    private static final Direction[] ORTHOGONAL_DIRECTIONS = {
            new Direction(1, 0),   // north
            new Direction(0, 1)    // east
    };

    static {
        BATTERY_ATTACK_MIDGAME[PAWN] = 5;
        BATTERY_ATTACK_ENDGAME[PAWN] = 4;

        BATTERY_ATTACK_MIDGAME[KNIGHT] = 7;
        BATTERY_ATTACK_ENDGAME[KNIGHT] = 6;

        BATTERY_ATTACK_MIDGAME[BISHOP] = 7;
        BATTERY_ATTACK_ENDGAME[BISHOP] = 6;

        BATTERY_ATTACK_MIDGAME[ROOK] = 11;
        BATTERY_ATTACK_ENDGAME[ROOK] = 9;

        BATTERY_ATTACK_MIDGAME[QUEEN] = 15;
        BATTERY_ATTACK_ENDGAME[QUEEN] = 12;

        BATTERY_ATTACK_MIDGAME[KING] = 24;
        BATTERY_ATTACK_ENDGAME[KING] = 28;
    }

    private static final int WHITE = 0;
    private static final int BLACK = 1;

    private static final Direction[] ALL_DIRECTIONS = {
            new Direction(1, 0),
            new Direction(-1, 0),
            new Direction(0, 1),
            new Direction(0, -1),
            new Direction(1, 1),
            new Direction(1, -1),
            new Direction(-1, 1),
            new Direction(-1, -1)
    };

    private final int[][] midgameContributions = new int[2][64];
    private final int[][] endgameContributions = new int[2][64];
    private final int[] sideMidgameTotals = new int[2];
    private final int[] sideEndgameTotals = new int[2];
    private final boolean[] touchedSquares = new boolean[64];

    private int midgameScoreCache;
    private int endgameScoreCache;
    private boolean dirty = true;

    @Override
    public void initialize(EvaluationContext context) {
        rebuildFromBoard(context == null ? null : context.board());
    }

    @Override
    public void evaluate(EvaluationContext context) {
        if (!dirty) {
            return;
        }
        rebuildFromBoard(context == null ? null : context.board());
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        if (!incrementalUpdate(moveContext)) {
            rebuildFromBoard(moveContext.currentContext() == null
                    ? null
                    : moveContext.currentContext().board());
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

    private void rebuildFromBoard(EvaluationContext.BoardView board) {
        clearContributions();
        if (board == null) {
            midgameScoreCache = 0;
            endgameScoreCache = 0;
            dirty = false;
            return;
        }
        long occupancy = board.allPieces();
        long whitePieces = board.whitePieces();
        long blackPieces = board.blackPieces();
        for (int square = 0; square < 64; square++) {
            PieceType type = board.getPieceTypeAtIndex(square);
            if (type == null) {
                continue;
            }
            boolean isWhite = ((whitePieces >>> square) & 1L) != 0;
            int side = isWhite ? WHITE : BLACK;
            ScorePair scores = computeScores(board, square, type, occupancy,
                    isWhite ? whitePieces : blackPieces,
                    isWhite ? blackPieces : whitePieces);
            midgameContributions[side][square] = scores.midgame;
            endgameContributions[side][square] = scores.endgame;
            sideMidgameTotals[side] += scores.midgame;
            sideEndgameTotals[side] += scores.endgame;
        }
        refreshScoreCaches();
        dirty = false;
    }

    private boolean incrementalUpdate(MoveContext moveContext) {
        EvaluationContext current = moveContext.currentContext();
        EvaluationContext previous = moveContext.previousContext();
        if (current == null) {
            clearContributions();
            midgameScoreCache = 0;
            endgameScoreCache = 0;
            dirty = false;
            return true;
        }
        if (previous == null) {
            rebuildFromBoard(current.board());
            return true;
        }
        EvaluationContext.BoardView previousBoard = previous.board();
        EvaluationContext.BoardView currentBoard = current.board();
        if (previousBoard == null || currentBoard == null) {
            rebuildFromBoard(currentBoard);
            return true;
        }

        clearTouched();

        int move = moveContext.move();
        int from = MoveHelper.deriveFromIndex(move);
        int to = MoveHelper.deriveToIndex(move);
        markAffected(previousBoard, from);
        markAffected(previousBoard, to);
        markAffected(currentBoard, from);
        markAffected(currentBoard, to);

        if (MoveHelper.isEnPassantMove(move)) {
            int capture = MoveHelper.isWhitesMove(move) ? to - 8 : to + 8;
            markAffected(previousBoard, capture);
            markAffected(currentBoard, capture);
        }

        if (MoveHelper.isCastlingMove(move)) {
            boolean whiteMove = MoveHelper.isWhitesMove(move);
            boolean kingside = to > from;
            int rookFrom = whiteMove ? (kingside ? 7 : 0) : (kingside ? 63 : 56);
            int rookTo = whiteMove ? (kingside ? 5 : 3) : (kingside ? 61 : 59);
            markAffected(previousBoard, rookFrom);
            markAffected(currentBoard, rookFrom);
            markAffected(previousBoard, rookTo);
            markAffected(currentBoard, rookTo);
        }

        int capturedPiece = MoveHelper.deriveCapturedPieceTypeBits(move);
        if (capturedPiece != 0) {
            markAffected(previousBoard, to);
        }

        applyUpdates(currentBoard);
        dirty = false;
        return true;
    }

    private void clearContributions() {
        for (int side = 0; side < midgameContributions.length; side++) {
            java.util.Arrays.fill(midgameContributions[side], 0);
            java.util.Arrays.fill(endgameContributions[side], 0);
            sideMidgameTotals[side] = 0;
            sideEndgameTotals[side] = 0;
        }
    }

    private void applyUpdates(EvaluationContext.BoardView board) {
        long whitePieces = board.whitePieces();
        long blackPieces = board.blackPieces();
        long occupancy = board.allPieces();
        for (int square = 0; square < touchedSquares.length; square++) {
            if (!touchedSquares[square]) {
                continue;
            }
            for (int side = 0; side < 2; side++) {
                int prevMid = midgameContributions[side][square];
                int prevEnd = endgameContributions[side][square];
                if (prevMid != 0 || prevEnd != 0) {
                    sideMidgameTotals[side] -= prevMid;
                    sideEndgameTotals[side] -= prevEnd;
                    midgameContributions[side][square] = 0;
                    endgameContributions[side][square] = 0;
                }
            }
            long mask = 1L << square;
            boolean isWhite = (whitePieces & mask) != 0;
            boolean isBlack = (blackPieces & mask) != 0;
            if (!isWhite && !isBlack) {
                continue;
            }
            PieceType type = board.getPieceTypeAtIndex(square);
            if (type == null) {
                continue;
            }
            int side = isWhite ? WHITE : BLACK;
            ScorePair scores = computeScores(board, square, type, occupancy,
                    isWhite ? whitePieces : blackPieces,
                    isWhite ? blackPieces : whitePieces);
            midgameContributions[side][square] = scores.midgame;
            endgameContributions[side][square] = scores.endgame;
            sideMidgameTotals[side] += scores.midgame;
            sideEndgameTotals[side] += scores.endgame;
        }
        refreshScoreCaches();
    }

    private void refreshScoreCaches() {
        midgameScoreCache = sideMidgameTotals[WHITE] - sideMidgameTotals[BLACK];
        endgameScoreCache = sideEndgameTotals[WHITE] - sideEndgameTotals[BLACK];
    }

    private void clearTouched() {
        java.util.Arrays.fill(touchedSquares, false);
    }

    private void markAffected(EvaluationContext.BoardView board, int square) {
        if (square < 0 || square >= 64 || board == null) {
            return;
        }
        touchedSquares[square] = true;
        for (Direction direction : ALL_DIRECTIONS) {
            markDirectionalImpact(board, square, direction);
        }
    }

    private void markDirectionalImpact(EvaluationContext.BoardView board, int square, Direction direction) {
        int rank = square / 8 + direction.rankDelta;
        int file = square % 8 + direction.fileDelta;
        while (rank >= 0 && rank < 8 && file >= 0 && file < 8) {
            int target = rank * 8 + file;
            PieceType type = board.getPieceTypeAtIndex(target);
            if (type != null && supportsDirection(type, isDiagonal(direction))) {
                touchedSquares[target] = true;
                break;
            }
            if (type != null) {
                break;
            }
            rank += direction.rankDelta;
            file += direction.fileDelta;
        }
    }

    private static boolean isDiagonal(Direction direction) {
        return direction.rankDelta != 0 && direction.fileDelta != 0;
    }

    private ScorePair computeScores(EvaluationContext.BoardView board,
                                    int square,
                                    PieceType pieceType,
                                    long occupancy,
                                    long friendlyPieces,
                                    long enemyPieces) {
        if (!isDiagonalSlider(pieceType) && !isOrthogonalSlider(pieceType)) {
            return ScorePair.ZERO;
        }
        ScorePair total = new ScorePair();
        if (isDiagonalSlider(pieceType)) {
            add(total, accumulateDirections(board, square, DIAGONAL_DIRECTIONS,
                    occupancy, friendlyPieces, enemyPieces, true));
        }
        if (isOrthogonalSlider(pieceType)) {
            add(total, accumulateDirections(board, square, ORTHOGONAL_DIRECTIONS,
                    occupancy, friendlyPieces, enemyPieces, false));
        }
        return total;
    }

    private ScorePair accumulateDirections(EvaluationContext.BoardView board,
                                           int square,
                                           Direction[] directions,
                                           long occupancy,
                                           long friendlyPieces,
                                           long enemyPieces,
                                           boolean diagonal) {
        ScorePair total = new ScorePair();
        for (Direction direction : directions) {
            add(total, accumulateDirection(board, square, direction, occupancy,
                    friendlyPieces, enemyPieces, diagonal));
        }
        return total;
    }

    private ScorePair accumulateDirection(EvaluationContext.BoardView board,
                                          int square,
                                          Direction direction,
                                          long occupancy,
                                          long friendlyPieces,
                                          long enemyPieces,
                                          boolean diagonal) {
        int rank = square / 8 + direction.rankDelta;
        int file = square % 8 + direction.fileDelta;
        while (rank >= 0 && rank < 8 && file >= 0 && file < 8) {
            int target = rank * 8 + file;
            long mask = 1L << target;
            if ((occupancy & mask) != 0) {
                if ((friendlyPieces & mask) != 0) {
                    PieceType friendlyType = board.getPieceTypeAtIndex(target);
                    if (friendlyType != null && supportsDirection(friendlyType, diagonal)) {
                        ScorePair scores = accumulateRayThreats(board, target, direction,
                                friendlyPieces, enemyPieces);
                        scores.midgame += BATTERY_FORMATION_MIDGAME;
                        scores.endgame += BATTERY_FORMATION_ENDGAME;
                        return scores;
                    }
                }
                break;
            }
            rank += direction.rankDelta;
            file += direction.fileDelta;
        }
        return ScorePair.ZERO;
    }

    private ScorePair accumulateRayThreats(EvaluationContext.BoardView board,
                                           int fromSquare,
                                           Direction direction,
                                           long friendlyPieces,
                                           long enemyPieces) {
        ScorePair score = new ScorePair();
        int rank = fromSquare / 8 + direction.rankDelta;
        int file = fromSquare % 8 + direction.fileDelta;
        while (rank >= 0 && rank < 8 && file >= 0 && file < 8) {
            int targetSquare = rank * 8 + file;
            long mask = 1L << targetSquare;
            if ((friendlyPieces & mask) != 0) {
                break;
            }
            if ((enemyPieces & mask) != 0) {
                PieceType enemyType = board.getPieceTypeAtIndex(targetSquare);
                if (enemyType != null) {
                    int typeBits = MoveHelper.pieceTypeToInt(enemyType);
                    score.midgame += BATTERY_ATTACK_MIDGAME[typeBits];
                    score.endgame += BATTERY_ATTACK_ENDGAME[typeBits];
                }
                break;
            }
            score.midgame += BATTERY_RAY_CONTROL_MIDGAME;
            score.endgame += BATTERY_RAY_CONTROL_ENDGAME;
            rank += direction.rankDelta;
            file += direction.fileDelta;
        }
        return score;
    }

    private static void add(ScorePair target, ScorePair delta) {
        target.midgame += delta.midgame;
        target.endgame += delta.endgame;
    }

    private static boolean supportsDirection(PieceType type, boolean diagonal) {
        return diagonal ? isDiagonalSlider(type) : isOrthogonalSlider(type);
    }

    private static boolean isDiagonalSlider(PieceType type) {
        return type == PieceType.BISHOP || type == PieceType.QUEEN;
    }

    private static boolean isOrthogonalSlider(PieceType type) {
        return type == PieceType.ROOK || type == PieceType.QUEEN;
    }

    private record Direction(int rankDelta, int fileDelta) {
    }

    private static final class ScorePair {
        private static final ScorePair ZERO = new ScorePair();
        private int midgame;
        private int endgame;

        private ScorePair() {
        }
    }
}
