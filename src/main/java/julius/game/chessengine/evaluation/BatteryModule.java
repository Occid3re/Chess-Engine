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

    private static final int BATTERY_FORMATION_MIDGAME = 16;
    private static final int BATTERY_FORMATION_ENDGAME = 12;
    private static final int BATTERY_RAY_CONTROL_MIDGAME = 2;
    private static final int BATTERY_RAY_CONTROL_ENDGAME = 2;

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
        BATTERY_ATTACK_MIDGAME[PAWN] = 6;
        BATTERY_ATTACK_ENDGAME[PAWN] = 5;

        BATTERY_ATTACK_MIDGAME[KNIGHT] = 9;
        BATTERY_ATTACK_ENDGAME[KNIGHT] = 7;

        BATTERY_ATTACK_MIDGAME[BISHOP] = 9;
        BATTERY_ATTACK_ENDGAME[BISHOP] = 8;

        BATTERY_ATTACK_MIDGAME[ROOK] = 13;
        BATTERY_ATTACK_ENDGAME[ROOK] = 11;

        BATTERY_ATTACK_MIDGAME[QUEEN] = 18;
        BATTERY_ATTACK_ENDGAME[QUEEN] = 15;

        BATTERY_ATTACK_MIDGAME[KING] = 30;
        BATTERY_ATTACK_ENDGAME[KING] = 34;
    }

    private int midgameScoreCache;
    private int endgameScoreCache;
    private boolean dirty = true;

    @Override
    public void initialize(EvaluationContext context) {
        evaluate(context);
    }

    @Override
    public void evaluate(EvaluationContext context) {
        if (!dirty) {
            return;
        }
        if (context == null) {
            midgameScoreCache = 0;
            endgameScoreCache = 0;
            dirty = false;
            return;
        }
        EvaluationContext.BoardView board = context.getBoard();
        if (board == null) {
            midgameScoreCache = 0;
            endgameScoreCache = 0;
            dirty = false;
            return;
        }

        BatteryScore white = evaluateSide(board, true);
        BatteryScore black = evaluateSide(board, false);

        midgameScoreCache = white.midgame - black.midgame;
        endgameScoreCache = white.endgame - black.endgame;
        dirty = false;
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        dirty = true;
    }

    @Override
    public void undoMove(MoveContext moveContext) {
        dirty = true;
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

    private BatteryScore evaluateSide(EvaluationContext.BoardView board, boolean isWhite) {
        BatteryScore score = new BatteryScore();
        long occupancy = board.getAllPieces();
        long friendlyPieces = isWhite ? board.getWhitePieces() : board.getBlackPieces();
        long enemyPieces = isWhite ? board.getBlackPieces() : board.getWhitePieces();

        long diagonalCandidates = (isWhite ? board.getWhiteBishops() : board.getBlackBishops())
                | (isWhite ? board.getWhiteQueens() : board.getBlackQueens());
        accumulateBatteries(board, diagonalCandidates, DIAGONAL_DIRECTIONS, occupancy,
                friendlyPieces, enemyPieces, true, score);

        long orthogonalCandidates = (isWhite ? board.getWhiteRooks() : board.getBlackRooks())
                | (isWhite ? board.getWhiteQueens() : board.getBlackQueens());
        accumulateBatteries(board, orthogonalCandidates, ORTHOGONAL_DIRECTIONS, occupancy,
                friendlyPieces, enemyPieces, false, score);

        return score;
    }

    private void accumulateBatteries(EvaluationContext.BoardView board, long candidates,
                                     Direction[] directions, long occupancy, long friendlyPieces,
                                     long enemyPieces, boolean diagonal, BatteryScore score) {
        long remaining = candidates;
        while (remaining != 0) {
            long bit = remaining & -remaining;
            int square = Long.numberOfTrailingZeros(bit);
            accumulateFromSquare(board, square, directions, occupancy, friendlyPieces,
                    enemyPieces, diagonal, score);
            remaining ^= bit;
        }
    }

    private void accumulateFromSquare(EvaluationContext.BoardView board, int square,
                                      Direction[] directions, long occupancy, long friendlyPieces,
                                      long enemyPieces, boolean diagonal, BatteryScore score) {
        for (Direction direction : directions) {
            accumulateInDirection(board, square, direction, occupancy, friendlyPieces,
                    enemyPieces, diagonal, score);
        }
    }

    private void accumulateInDirection(EvaluationContext.BoardView board, int square,
                                       Direction direction, long occupancy, long friendlyPieces,
                                       long enemyPieces, boolean diagonal, BatteryScore score) {
        int rank = square / 8;
        int file = square % 8;
        int r = rank + direction.rankDelta;
        int f = file + direction.fileDelta;
        while (r >= 0 && r < 8 && f >= 0 && f < 8) {
            int targetSquare = r * 8 + f;
            long mask = 1L << targetSquare;
            if ((occupancy & mask) != 0) {
                if ((friendlyPieces & mask) != 0) {
                    PieceType friendlyType = board.getPieceTypeAtIndex(targetSquare);
                    if (friendlyType != null && supportsDirection(friendlyType, diagonal)) {
                        score.midgame += BATTERY_FORMATION_MIDGAME;
                        score.endgame += BATTERY_FORMATION_ENDGAME;
                        accumulateRayThreats(board, targetSquare, direction, friendlyPieces,
                                enemyPieces, score);
                    }
                }
                break;
            }
            r += direction.rankDelta;
            f += direction.fileDelta;
        }
    }

    private void accumulateRayThreats(EvaluationContext.BoardView board, int fromSquare,
                                      Direction direction, long friendlyPieces, long enemyPieces,
                                      BatteryScore score) {
        int rank = fromSquare / 8;
        int file = fromSquare % 8;
        int r = rank + direction.rankDelta;
        int f = file + direction.fileDelta;
        while (r >= 0 && r < 8 && f >= 0 && f < 8) {
            int targetSquare = r * 8 + f;
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
            r += direction.rankDelta;
            f += direction.fileDelta;
        }
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

    private static final class Direction {
        private final int rankDelta;
        private final int fileDelta;

        private Direction(int rankDelta, int fileDelta) {
            this.rankDelta = rankDelta;
            this.fileDelta = fileDelta;
        }
    }

    private static final class BatteryScore {
        private int midgame;
        private int endgame;
    }
}
