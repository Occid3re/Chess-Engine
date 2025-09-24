package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.RookHelper;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntPredicate;

import static julius.game.chessengine.helper.KingHelper.KING_ATTACKS;
import static julius.game.chessengine.helper.PawnMoveTables.PAWN_ATTACKS;

/**
 * Evaluates tactical vulnerabilities such as hanging pieces and pawn attacks on higher valued
 * pieces. The module keeps per-piece penalty caches so that only the squares affected by a move
 * need to be refreshed.
 */
public final class ThreatModule implements EvaluationModule {

    private static final int WHITE = 0;
    private static final int BLACK = 1;

    private static final int PAWN = MoveHelper.pieceTypeToInt(PieceType.PAWN);
    private static final int KNIGHT = MoveHelper.pieceTypeToInt(PieceType.KNIGHT);
    private static final int BISHOP = MoveHelper.pieceTypeToInt(PieceType.BISHOP);
    private static final int ROOK = MoveHelper.pieceTypeToInt(PieceType.ROOK);
    private static final int QUEEN = MoveHelper.pieceTypeToInt(PieceType.QUEEN);
    private static final int KING = MoveHelper.pieceTypeToInt(PieceType.KING);

    private static final int[] HANGING_PENALTIES = new int[7];
    private static final int[] PAWN_THREAT_PENALTIES = new int[7];

    private static final BishopHelper BISHOP_HELPER = BishopHelper.getInstance();
    private static final RookHelper ROOK_HELPER = RookHelper.getInstance();

    static {
        HANGING_PENALTIES[PAWN] = -12;
        HANGING_PENALTIES[KNIGHT] = -30;
        HANGING_PENALTIES[BISHOP] = -30;
        HANGING_PENALTIES[ROOK] = -45;
        HANGING_PENALTIES[QUEEN] = -70;

        PAWN_THREAT_PENALTIES[KNIGHT] = -10;
        PAWN_THREAT_PENALTIES[BISHOP] = -10;
        PAWN_THREAT_PENALTIES[ROOK] = -18;
        PAWN_THREAT_PENALTIES[QUEEN] = -25;
    }

    private final int[][] squarePenalties = new int[2][64];
    private final int[] sideTotals = new int[2];

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
        Objects.requireNonNull(context, "context");
        EvaluationContext.BoardView board = context.board();
        if (board == null) {
            resetCaches();
            return;
        }

        rebuildFromBoard(board, context.whiteAttackMap(), context.blackAttackMap());
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        if (!updateForMove(moveContext)) {
            EvaluationContext current = moveContext.currentContext();
            EvaluationContext.BoardView board = current == null ? null : current.board();
            if (board == null) {
                resetCaches();
            } else {
                rebuildFromBoard(board, current.whiteAttackMap(), current.blackAttackMap());
            }
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

    private void rebuildFromBoard(EvaluationContext.BoardView board, long whiteAttacks, long blackAttacks) {
        Arrays.fill(sideTotals, 0);
        for (int[] cache : squarePenalties) {
            Arrays.fill(cache, 0);
        }

        long whitePawnAttacks = computePawnAttackMask(board.whitePawns(), WHITE);
        long blackPawnAttacks = computePawnAttackMask(board.blackPawns(), BLACK);

        evaluateSide(board, true, whiteAttacks, blackAttacks, blackPawnAttacks);
        evaluateSide(board, false, blackAttacks, whiteAttacks, whitePawnAttacks);
        updateScoreCache();
    }

    private boolean updateForMove(MoveContext moveContext) {
        EvaluationContext current = moveContext.currentContext();
        EvaluationContext previous = moveContext.previousContext();
        if (current == null) {
            resetCaches();
            return true;
        }
        if (previous == null) {
            rebuildFromBoard(current.board(), current.whiteAttackMap(), current.blackAttackMap());
            return true;
        }

        EvaluationContext.BoardView currentBoard = current.board();
        EvaluationContext.BoardView previousBoard = previous.board();
        if (currentBoard == null || previousBoard == null) {
            rebuildFromBoard(currentBoard, current.whiteAttackMap(), current.blackAttackMap());
            return true;
        }

        long impacted = collectAnchorSquares(moveContext.move());
        long attackDiff = previous.whiteAttackMap() ^ current.whiteAttackMap();
        attackDiff |= previous.blackAttackMap() ^ current.blackAttackMap();
        impacted |= attackDiff;

        long removalMask = impacted;
        while (removalMask != 0) {
            int square = Long.numberOfTrailingZeros(removalMask);
            removalMask &= removalMask - 1;
            removePenalty(square, WHITE);
            removePenalty(square, BLACK);
        }

        long whitePawnAttacks = computePawnAttackMask(currentBoard.whitePawns(), WHITE);
        long blackPawnAttacks = computePawnAttackMask(currentBoard.blackPawns(), BLACK);

        long updateMask = impacted;
        while (updateMask != 0) {
            int square = Long.numberOfTrailingZeros(updateMask);
            updateMask &= updateMask - 1;
            addPenalty(currentBoard, square, true, current.whiteAttackMap(), current.blackAttackMap(),
                    blackPawnAttacks);
            addPenalty(currentBoard, square, false, current.blackAttackMap(), current.whiteAttackMap(),
                    whitePawnAttacks);
        }

        updateScoreCache();
        dirty = false;
        return true;
    }

    private void evaluateSide(EvaluationContext.BoardView board, boolean isWhite, long friendlyAttacks,
                              long enemyAttacks, long enemyPawnAttacks) {
        long pieces = isWhite ? board.whitePieces() : board.blackPieces();
        int colorIndex = isWhite ? WHITE : BLACK;
        long remaining = pieces;
        while (remaining != 0) {
            long bit = remaining & -remaining;
            int square = Long.numberOfTrailingZeros(bit);
            int penalty = evaluatePiece(board, isWhite, square, friendlyAttacks, enemyAttacks, enemyPawnAttacks);
            squarePenalties[colorIndex][square] = penalty;
            sideTotals[colorIndex] += penalty;
            remaining ^= bit;
        }
    }

    private void addPenalty(EvaluationContext.BoardView board, int square, boolean isWhite,
                            long friendlyAttacks, long enemyAttacks, long enemyPawnAttacks) {
        int colorIndex = isWhite ? WHITE : BLACK;
        PieceType type = board.getPieceTypeAtIndex(square);
        if (type == null) {
            return;
        }
        long mask = 1L << square;
        long pieces = isWhite ? board.whitePieces() : board.blackPieces();
        if ((pieces & mask) == 0) {
            return;
        }
        int penalty = evaluatePiece(board, isWhite, square, friendlyAttacks, enemyAttacks, enemyPawnAttacks);
        squarePenalties[colorIndex][square] = penalty;
        sideTotals[colorIndex] += penalty;
    }

    private void removePenalty(int square, int colorIndex) {
        int penalty = squarePenalties[colorIndex][square];
        if (penalty == 0) {
            return;
        }
        sideTotals[colorIndex] -= penalty;
        squarePenalties[colorIndex][square] = 0;
    }

    private int evaluatePiece(EvaluationContext.BoardView board, boolean isWhite, int square,
                               long friendlyAttacks, long enemyAttacks, long enemyPawnAttacks) {
        PieceType type = board.getPieceTypeAtIndex(square);
        if (type == null) {
            return 0;
        }
        int typeBits = MoveHelper.pieceTypeToInt(type);
        if (typeBits == KING) {
            return 0;
        }
        long mask = 1L << square;
        if ((enemyAttacks & mask) == 0) {
            return 0;
        }
        boolean defended = (friendlyAttacks & mask) != 0;
        int penalty = 0;
        if (!defended) {
            penalty += HANGING_PENALTIES[typeBits];
        }
        if (typeBits > PAWN && (enemyPawnAttacks & mask) != 0) {
            int defenderValue = defended
                    ? leastValuableDefenderValue(board, isWhite, square)
                    : materialValueFor(typeBits);
            if (defenderValue <= 0) {
                defenderValue = materialValueFor(typeBits);
            }
            penalty += scalePawnThreatPenalty(typeBits, defenderValue);
        }
        return penalty;
    }

    private void updateScoreCache() {
        midgameScoreCache = sideTotals[WHITE] - sideTotals[BLACK];
        endgameScoreCache = midgameScoreCache;
        dirty = false;
    }

    private void resetCaches() {
        Arrays.fill(sideTotals, 0);
        for (int[] cache : squarePenalties) {
            Arrays.fill(cache, 0);
        }
        midgameScoreCache = 0;
        endgameScoreCache = 0;
        dirty = false;
    }

    private static int materialValueFor(int pieceType) {
        return switch (pieceType) {
            case 1 -> MaterialModule.PAWN_VALUE;
            case 2 -> MaterialModule.KNIGHT_VALUE;
            case 3 -> MaterialModule.BISHOP_VALUE;
            case 4 -> MaterialModule.ROOK_VALUE;
            case 5 -> MaterialModule.QUEEN_VALUE;
            case 6 -> MaterialModule.QUEEN_VALUE * 2;
            default -> 0;
        };
    }

    private int scalePawnThreatPenalty(int pieceType, int defenderValue) {
        int basePenalty = PAWN_THREAT_PENALTIES[pieceType];
        if (basePenalty == 0) {
            return 0;
        }
        int scaled = (basePenalty * defenderValue) / MaterialModule.PAWN_VALUE;
        if (basePenalty < 0) {
            return Math.min(basePenalty, scaled);
        }
        return Math.max(basePenalty, scaled);
    }

    private int leastValuableDefenderValue(EvaluationContext.BoardView board, boolean isWhite, int targetSquare) {
        long mask = 1L << targetSquare;
        int colorIndex = isWhite ? WHITE : BLACK;
        int minValue = Integer.MAX_VALUE;
        long occupancy = board.allPieces();

        long pawns = isWhite ? board.whitePawns() : board.blackPawns();
        minValue = findCheapestDefender(pawns, MaterialModule.PAWN_VALUE, minValue,
                square -> (PAWN_ATTACKS[colorIndex][square] & mask) != 0);
        if (minValue == MaterialModule.PAWN_VALUE) {
            return minValue;
        }

        long knights = isWhite ? board.whiteKnights() : board.blackKnights();
        minValue = findCheapestDefender(knights, MaterialModule.KNIGHT_VALUE, minValue,
                square -> (julius.game.chessengine.helper.KnightHelper.knightMoveTable[square] & mask) != 0);
        if (minValue == MaterialModule.PAWN_VALUE) {
            return minValue;
        }

        long bishops = isWhite ? board.whiteBishops() : board.blackBishops();
        minValue = findCheapestDefender(bishops, MaterialModule.BISHOP_VALUE, minValue,
                square -> (BISHOP_HELPER.calculateBishopMoves(square, occupancy) & mask) != 0);
        if (minValue == MaterialModule.PAWN_VALUE) {
            return minValue;
        }

        long rooks = isWhite ? board.whiteRooks() : board.blackRooks();
        minValue = findCheapestDefender(rooks, MaterialModule.ROOK_VALUE, minValue,
                square -> (ROOK_HELPER.calculateRookMoves(square, occupancy) & mask) != 0);
        if (minValue == MaterialModule.PAWN_VALUE) {
            return minValue;
        }

        long queens = isWhite ? board.whiteQueens() : board.blackQueens();
        minValue = findCheapestDefender(queens, MaterialModule.QUEEN_VALUE, minValue,
                square -> ((BISHOP_HELPER.calculateBishopMoves(square, occupancy)
                        | ROOK_HELPER.calculateRookMoves(square, occupancy)) & mask) != 0);
        if (minValue == MaterialModule.PAWN_VALUE) {
            return minValue;
        }

        long king = isWhite ? board.whiteKing() : board.blackKing();
        minValue = findCheapestDefender(king, materialValueFor(KING), minValue,
                square -> (KING_ATTACKS[square] & mask) != 0);

        if (minValue == Integer.MAX_VALUE) {
            return 0;
        }
        return minValue;
    }

    private int findCheapestDefender(long pieces, int value, int currentMin, IntPredicate attacksSquare) {
        long remaining = pieces;
        while (remaining != 0 && currentMin > MaterialModule.PAWN_VALUE) {
            long bit = remaining & -remaining;
            int square = Long.numberOfTrailingZeros(bit);
            if (attacksSquare.test(square)) {
                currentMin = Math.min(currentMin, value);
                if (currentMin == MaterialModule.PAWN_VALUE) {
                    return currentMin;
                }
            }
            remaining ^= bit;
        }
        return currentMin;
    }

    private static long computePawnAttackMask(long pawns, int pawnColor) {
        long mask = 0L;
        long remaining = pawns;
        while (remaining != 0) {
            long pawn = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(pawn);
            mask |= PAWN_ATTACKS[pawnColor][index];
            remaining ^= pawn;
        }
        return mask;
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
}

