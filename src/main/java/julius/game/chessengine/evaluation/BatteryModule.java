package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;

import java.util.Arrays;

/**
 * Rewards coordinated long-range pieces that form batteries (stacked along the same ray) and
 * penalizes the opponent when the battery directly targets valuable pieces. The module only
 * recomputes its contribution when marked dirty.
 */
public final class BatteryModule implements EvaluationModule {

    private static final int WHITE = 0;
    private static final int BLACK = 1;

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
            new Direction(1, 1),
            new Direction(1, -1)
    };

    private static final Direction[] ORTHOGONAL_DIRECTIONS = {
            new Direction(1, 0),
            new Direction(0, 1)
    };

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

    private final int[][] perSquareMidgame = new int[2][64];
    private final int[][] perSquareEndgame = new int[2][64];
    private final int[] midgameTotals = new int[2];
    private final int[] endgameTotals = new int[2];

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
        EvaluationContext.BoardView board = context == null ? null : context.board();
        rebuildFromBoard(board);
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        if (!updateForMove(moveContext)) {
            EvaluationContext current = moveContext.currentContext();
            EvaluationContext.BoardView board = current == null ? null : current.board();
            rebuildFromBoard(board);
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
        Arrays.fill(midgameTotals, 0);
        Arrays.fill(endgameTotals, 0);
        for (int[] cache : perSquareMidgame) {
            Arrays.fill(cache, 0);
        }
        for (int[] cache : perSquareEndgame) {
            Arrays.fill(cache, 0);
        }

        if (board == null) {
            updateScoreCache();
            dirty = false;
            return;
        }

        rebuildSide(board, true);
        rebuildSide(board, false);
        updateScoreCache();
        dirty = false;
    }

    private boolean updateForMove(MoveContext moveContext) {
        EvaluationContext current = moveContext.currentContext();
        EvaluationContext previous = moveContext.previousContext();
        EvaluationContext.BoardView currentBoard = current == null ? null : current.board();
        if (currentBoard == null) {
            Arrays.fill(midgameTotals, 0);
            Arrays.fill(endgameTotals, 0);
            updateScoreCache();
            dirty = false;
            return true;
        }
        if (previous == null) {
            rebuildFromBoard(currentBoard);
            return true;
        }

        EvaluationContext.BoardView previousBoard = previous.board();
        long impacted = collectAnchorSquares(moveContext.move());
        impacted |= collectSliderSquares(previousBoard, impacted);
        impacted |= collectSliderSquares(currentBoard, impacted);

        if (impacted == 0) {
            dirty = false;
            return true;
        }

        long removed = impacted;
        while (removed != 0) {
            int square = Long.numberOfTrailingZeros(removed);
            removed &= removed - 1;
            removeContribution(square, WHITE);
            removeContribution(square, BLACK);
        }

        long toUpdate = impacted;
        while (toUpdate != 0) {
            int square = Long.numberOfTrailingZeros(toUpdate);
            toUpdate &= toUpdate - 1;
            addContribution(currentBoard, square, true);
            addContribution(currentBoard, square, false);
        }

        updateScoreCache();
        dirty = false;
        return true;
    }

    private void rebuildSide(EvaluationContext.BoardView board, boolean isWhite) {
        long candidates = 0L;
        if (isWhite) {
            candidates |= board.whiteBishops();
            candidates |= board.whiteRooks();
            candidates |= board.whiteQueens();
        } else {
            candidates |= board.blackBishops();
            candidates |= board.blackRooks();
            candidates |= board.blackQueens();
        }
        int colorIndex = isWhite ? WHITE : BLACK;
        while (candidates != 0) {
            long bit = candidates & -candidates;
            int square = Long.numberOfTrailingZeros(bit);
            BatteryScore score = evaluateSquare(board, isWhite, square);
            perSquareMidgame[colorIndex][square] = score.midgame;
            perSquareEndgame[colorIndex][square] = score.endgame;
            midgameTotals[colorIndex] += score.midgame;
            endgameTotals[colorIndex] += score.endgame;
            candidates ^= bit;
        }
    }

    private void addContribution(EvaluationContext.BoardView board, int square, boolean white) {
        int colorIndex = white ? WHITE : BLACK;
        PieceType type = board.getPieceTypeAtIndex(square);
        if (type == null) {
            return;
        }
        long mask = 1L << square;
        long pieces = white ? board.whitePieces() : board.blackPieces();
        if ((pieces & mask) == 0) {
            return;
        }
        if (!isSlider(type)) {
            return;
        }
        BatteryScore score = evaluateSquare(board, white, square);
        perSquareMidgame[colorIndex][square] = score.midgame;
        perSquareEndgame[colorIndex][square] = score.endgame;
        midgameTotals[colorIndex] += score.midgame;
        endgameTotals[colorIndex] += score.endgame;
    }

    private void removeContribution(int square, int colorIndex) {
        int mid = perSquareMidgame[colorIndex][square];
        int end = perSquareEndgame[colorIndex][square];
        if (mid == 0 && end == 0) {
            return;
        }
        midgameTotals[colorIndex] -= mid;
        endgameTotals[colorIndex] -= end;
        perSquareMidgame[colorIndex][square] = 0;
        perSquareEndgame[colorIndex][square] = 0;
    }

    private BatteryScore evaluateSquare(EvaluationContext.BoardView board, boolean isWhite, int square) {
        BatteryScore score = new BatteryScore();
        PieceType type = board.getPieceTypeAtIndex(square);
        if (type == null) {
            return score;
        }

        long occupancy = board.allPieces();
        long friendlyPieces = isWhite ? board.whitePieces() : board.blackPieces();
        long enemyPieces = isWhite ? board.blackPieces() : board.whitePieces();

        if (supportsDirection(type, true)) {
            accumulateFromSquare(board, square, DIAGONAL_DIRECTIONS, occupancy, friendlyPieces,
                    enemyPieces, true, score);
        }
        if (supportsDirection(type, false)) {
            accumulateFromSquare(board, square, ORTHOGONAL_DIRECTIONS, occupancy, friendlyPieces,
                    enemyPieces, false, score);
        }
        return score;
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

    private long collectAnchorSquares(int move) {
        int from = MoveHelper.deriveFromIndex(move);
        int to = MoveHelper.deriveToIndex(move);
        long anchors = (1L << from) | (1L << to);
        if (MoveHelper.isEnPassantMove(move)) {
            int capture = MoveHelper.isWhitesMove(move) ? to - 8 : to + 8;
            anchors |= 1L << capture;
        }
        return anchors;
    }

    private long collectSliderSquares(EvaluationContext.BoardView board, long anchors) {
        if (board == null) {
            return 0L;
        }
        long impacted = 0L;
        long remaining = anchors;
        while (remaining != 0) {
            int square = Long.numberOfTrailingZeros(remaining);
            impacted |= slidersAlongDirections(board, square);
            remaining &= remaining - 1;
        }
        return impacted;
    }

    private long slidersAlongDirections(EvaluationContext.BoardView board, int square) {
        long impacted = 0L;
        for (Direction direction : ALL_DIRECTIONS) {
            int rank = square / 8 + direction.rankDelta;
            int file = square % 8 + direction.fileDelta;
            while (rank >= 0 && rank < 8 && file >= 0 && file < 8) {
                int target = rank * 8 + file;
                PieceType type = board.getPieceTypeAtIndex(target);
                if (type != null) {
                    if (isSliderInDirection(type, direction)) {
                        impacted |= 1L << target;
                    }
                    break;
                }
                rank += direction.rankDelta;
                file += direction.fileDelta;
            }
        }
        return impacted;
    }

    private boolean isSlider(PieceType type) {
        return type == PieceType.BISHOP || type == PieceType.ROOK || type == PieceType.QUEEN;
    }

    private boolean isSliderInDirection(PieceType type, Direction direction) {
        if (!isSlider(type)) {
            return false;
        }
        boolean diagonal = Math.abs(direction.rankDelta) == Math.abs(direction.fileDelta);
        if (diagonal) {
            return type == PieceType.BISHOP || type == PieceType.QUEEN;
        }
        return type == PieceType.ROOK || type == PieceType.QUEEN;
    }

    private void updateScoreCache() {
        midgameScoreCache = midgameTotals[WHITE] - midgameTotals[BLACK];
        endgameScoreCache = endgameTotals[WHITE] - endgameTotals[BLACK];
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

    private static final class BatteryScore {
        private int midgame;
        private int endgame;
    }
}
