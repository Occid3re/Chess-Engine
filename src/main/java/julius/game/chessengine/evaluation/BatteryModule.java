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

    private static final Direction[] ALL_DIRECTIONS = {
            new Direction(1, 0),    // north
            new Direction(-1, 0),   // south
            new Direction(0, 1),    // east
            new Direction(0, -1),   // west
            new Direction(1, 1),    // north-east
            new Direction(1, -1),   // north-west
            new Direction(-1, 1),   // south-east
            new Direction(-1, -1)   // south-west
    };

    private final int[][] midgameContributions = new int[2][64];
    private final int[][] endgameContributions = new int[2][64];
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
        rebuildFromBoard(context == null ? null : context.board());
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        updateForMove(moveContext);
    }

    @Override
    public void undoMove(MoveContext moveContext) {
        updateForMove(moveContext);
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
        for (int i = 0; i < 2; i++) {
            Arrays.fill(midgameContributions[i], 0);
            Arrays.fill(endgameContributions[i], 0);
            midgameTotals[i] = 0;
            endgameTotals[i] = 0;
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

    private void rebuildSide(EvaluationContext.BoardView board, boolean isWhite) {
        int colorIndex = isWhite ? 0 : 1;
        long occupancy = board.allPieces();
        long friendlyPieces = isWhite ? board.whitePieces() : board.blackPieces();
        long enemyPieces = isWhite ? board.blackPieces() : board.whitePieces();

        long queens = isWhite ? board.whiteQueens() : board.blackQueens();
        long diagonalCandidates = (isWhite ? board.whiteBishops() : board.blackBishops()) | queens;
        long orthogonalCandidates = (isWhite ? board.whiteRooks() : board.blackRooks()) | queens;

        accumulateCandidates(board, diagonalCandidates, colorIndex, occupancy, friendlyPieces,
                enemyPieces, true);
        accumulateCandidates(board, orthogonalCandidates, colorIndex, occupancy, friendlyPieces,
                enemyPieces, false);
    }

    private void accumulateCandidates(EvaluationContext.BoardView board, long candidates,
                                      int colorIndex, long occupancy, long friendlyPieces,
                                      long enemyPieces, boolean diagonal) {
        long remaining = candidates;
        while (remaining != 0) {
            long bit = remaining & -remaining;
            int square = Long.numberOfTrailingZeros(bit);
            updateContribution(board, colorIndex, square, occupancy, friendlyPieces, enemyPieces,
                    diagonal, true);
            remaining ^= bit;
        }
    }

    private void updateContribution(EvaluationContext.BoardView board, int colorIndex, int square,
                                    long occupancy, long friendlyPieces, long enemyPieces,
                                    boolean diagonal, boolean additive) {
        PieceType type = board.getPieceTypeAtIndex(square);
        if (type == null) {
            return;
        }
        boolean isDiagonalSlider = isDiagonalSlider(type);
        boolean isOrthSlider = isOrthogonalSlider(type);

        if ((diagonal && !isDiagonalSlider) || (!diagonal && !isOrthSlider)) {
            return;
        }

        BatteryScore contribution = computeContribution(board, square,
                diagonal ? DIAGONAL_DIRECTIONS : ORTHOGONAL_DIRECTIONS,
                occupancy, friendlyPieces, enemyPieces, diagonal);

        applyContribution(colorIndex, square, contribution, additive);
    }

    private BatteryScore computeContribution(EvaluationContext.BoardView board, int square,
                                             Direction[] directions, long occupancy,
                                             long friendlyPieces, long enemyPieces,
                                             boolean diagonal) {
        BatteryScore score = new BatteryScore();
        for (Direction direction : directions) {
            accumulateInDirection(board, square, direction, occupancy, friendlyPieces,
                    enemyPieces, diagonal, score);
        }
        return score;
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

    private void applyContribution(int colorIndex, int square, BatteryScore contribution,
                                   boolean additive) {
        int deltaMid = contribution.midgame;
        int deltaEnd = contribution.endgame;
        if (!additive) {
            deltaMid = -deltaMid;
            deltaEnd = -deltaEnd;
        }
        midgameTotals[colorIndex] += deltaMid;
        endgameTotals[colorIndex] += deltaEnd;
        midgameContributions[colorIndex][square] += deltaMid;
        endgameContributions[colorIndex][square] += deltaEnd;
    }

    private void updateForMove(MoveContext moveContext) {
        EvaluationContext current = moveContext.currentContext();
        if (current == null || current.board() == null) {
            rebuildFromBoard(null);
            return;
        }
        EvaluationContext previous = moveContext.previousContext();
        if (previous == null || previous.board() == null) {
            rebuildFromBoard(current.board());
            return;
        }

        if (dirty) {
            rebuildFromBoard(current.board());
            return;
        }

        if (!updateIncrementally(moveContext.move(), previous.board(), current.board())) {
            rebuildFromBoard(current.board());
        }
    }

    private boolean updateIncrementally(int move, EvaluationContext.BoardView previousBoard,
                                        EvaluationContext.BoardView currentBoard) {
        boolean[] impacted = new boolean[64];
        markChangedSquares(move, impacted);
        collectImpactedSliders(previousBoard, impacted);
        collectImpactedSliders(currentBoard, impacted);

        boolean any = false;
        for (boolean flag : impacted) {
            if (flag) {
                any = true;
                break;
            }
        }
        if (!any) {
            return true;
        }

        for (int square = 0; square < 64; square++) {
            if (!impacted[square]) {
                continue;
            }
            removeContribution(square);
            recomputeContribution(currentBoard, square);
        }

        updateScoreCache();
        return true;
    }

    private void markChangedSquares(int move, boolean[] impacted) {
        int from = MoveHelper.deriveFromIndex(move);
        int to = MoveHelper.deriveToIndex(move);
        addImpacted(impacted, from);
        addImpacted(impacted, to);

        int capturedPiece = MoveHelper.deriveCapturedPieceTypeBits(move);
        if (capturedPiece != 0) {
            int captureSquare = MoveHelper.isEnPassantMove(move)
                    ? enPassantCaptureSquare(to, MoveHelper.isWhitesMove(move))
                    : to;
            addImpacted(impacted, captureSquare);
        }

        if (MoveHelper.isCastlingMove(move)) {
            handleCastlingSquares(MoveHelper.isWhitesMove(move), to, impacted);
        }
    }

    private void addImpacted(boolean[] impacted, int square) {
        if (square >= 0 && square < impacted.length) {
            impacted[square] = true;
        }
    }

    private void collectImpactedSliders(EvaluationContext.BoardView board, boolean[] impacted) {
        if (board == null) {
            return;
        }
        for (int square = 0; square < impacted.length; square++) {
            if (!impacted[square]) {
                continue;
            }
            for (Direction direction : ALL_DIRECTIONS) {
                int r = square / 8 + direction.rankDelta;
                int f = square % 8 + direction.fileDelta;
                while (r >= 0 && r < 8 && f >= 0 && f < 8) {
                    int target = r * 8 + f;
                    PieceType type = board.getPieceTypeAtIndex(target);
                    if (type != null) {
                        if (isDirectionalSlider(type, direction)) {
                            impacted[target] = true;
                            r += direction.rankDelta;
                            f += direction.fileDelta;
                            continue;
                        }
                        break;
                    }
                    r += direction.rankDelta;
                    f += direction.fileDelta;
                }
            }
        }
    }

    private void removeContribution(int square) {
        for (int color = 0; color < 2; color++) {
            int deltaMid = midgameContributions[color][square];
            int deltaEnd = endgameContributions[color][square];
            if (deltaMid == 0 && deltaEnd == 0) {
                continue;
            }
            midgameTotals[color] -= deltaMid;
            endgameTotals[color] -= deltaEnd;
            midgameContributions[color][square] = 0;
            endgameContributions[color][square] = 0;
        }
    }

    private void recomputeContribution(EvaluationContext.BoardView board, int square) {
        PieceType type = board.getPieceTypeAtIndex(square);
        if (type == null) {
            return;
        }
        boolean isWhite = (board.whitePieces() & (1L << square)) != 0;

        int colorIndex = isWhite ? 0 : 1;
        long occupancy = board.allPieces();
        long friendlyPieces = isWhite ? board.whitePieces() : board.blackPieces();
        long enemyPieces = isWhite ? board.blackPieces() : board.whitePieces();

        if (isDiagonalSlider(type)) {
            BatteryScore diagonal = computeContribution(board, square, DIAGONAL_DIRECTIONS,
                    occupancy, friendlyPieces, enemyPieces, true);
            applyContribution(colorIndex, square, diagonal, true);
        }
        if (isOrthogonalSlider(type)) {
            BatteryScore orthogonal = computeContribution(board, square, ORTHOGONAL_DIRECTIONS,
                    occupancy, friendlyPieces, enemyPieces, false);
            applyContribution(colorIndex, square, orthogonal, true);
        }
    }

    private void updateScoreCache() {
        midgameScoreCache = midgameTotals[0] - midgameTotals[1];
        endgameScoreCache = endgameTotals[0] - endgameTotals[1];
    }

    private static int enPassantCaptureSquare(int to, boolean moverIsWhite) {
        return moverIsWhite ? to - 8 : to + 8;
    }

    private static void handleCastlingSquares(boolean whiteMove, int kingDestination,
                                               boolean[] impacted) {
        if (whiteMove) {
            if (kingDestination == 6) {
                impacted[5] = true;
                impacted[7] = true;
            } else {
                impacted[3] = true;
                impacted[0] = true;
            }
        } else {
            if (kingDestination == 62) {
                impacted[61] = true;
                impacted[63] = true;
            } else {
                impacted[59] = true;
                impacted[56] = true;
            }
        }
    }

    private static boolean isDirectionalSlider(PieceType type, Direction direction) {
        boolean diagonal = Math.abs(direction.rankDelta) == Math.abs(direction.fileDelta);
        return diagonal ? isDiagonalSlider(type) : isOrthogonalSlider(type);
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
