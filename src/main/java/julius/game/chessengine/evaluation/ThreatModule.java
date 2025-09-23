package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.RookHelper;

import java.util.Objects;

import static julius.game.chessengine.helper.PawnMoveTables.PAWN_ATTACKS;
import static julius.game.chessengine.helper.KingHelper.KING_ATTACKS;

/**
 * Evaluates tactical vulnerabilities such as hanging pieces and pawn attacks on higher valued
 * pieces. The module recomputes its contribution when marked dirty rather than maintaining
 * incremental state.
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
            midgameScoreCache = 0;
            endgameScoreCache = 0;
            dirty = false;
            return;
        }

        long whiteAttacks = context.whiteAttackMap();
        long blackAttacks = context.blackAttackMap();

        int whitePenalty = evaluateSide(board, true, whiteAttacks, blackAttacks);
        int blackPenalty = evaluateSide(board, false, blackAttacks, whiteAttacks);

        midgameScoreCache = whitePenalty - blackPenalty;
        endgameScoreCache = midgameScoreCache;
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

    private int evaluateSide(EvaluationContext.BoardView board, boolean isWhite, long friendlyAttacks, long enemyAttacks) {
        long pieces = isWhite ? board.whitePieces() : board.blackPieces();
        long enemyPawns = isWhite ? board.blackPawns() : board.whitePawns();
        int enemyPawnColor = isWhite ? BLACK : WHITE;
        long enemyPawnAttacks = computePawnAttackMask(enemyPawns, enemyPawnColor);

        int penalty = 0;
        long remaining = pieces;
        while (remaining != 0) {
            long bit = remaining & -remaining;
            int square = Long.numberOfTrailingZeros(bit);
            PieceType type = board.getPieceTypeAtIndex(square);
            if (type == null) {
                remaining ^= bit;
                continue;
            }
            int typeBits = MoveHelper.pieceTypeToInt(type);
            if (typeBits == KING) {
                remaining ^= bit;
                continue;
            }
            long mask = 1L << square;
            if ((enemyAttacks & mask) == 0) {
                remaining ^= bit;
                continue;
            }
            boolean defended = (friendlyAttacks & mask) != 0;
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
            remaining ^= bit;
        }
        return penalty;
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

    private int findCheapestDefender(long pieces, int value, int currentMin,
                                     java.util.function.IntPredicate attacksSquare) {
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
}
