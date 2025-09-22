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
    private static final int[] BATTERY_THREAT_PENALTIES = new int[7];

    private static final int[] ROOK_DELTAS = {8, -8, 1, -1};
    private static final int[] BISHOP_DELTAS = {9, -9, 7, -7};

    private static final int BATTERY_FORMATION_BONUS = 12;
    private static final int BATTERY_KING_PRESSURE = 24;

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

        BATTERY_THREAT_PENALTIES[PAWN] = scaledBatteryPenalty(MaterialModule.PAWN_VALUE);
        BATTERY_THREAT_PENALTIES[KNIGHT] = scaledBatteryPenalty(MaterialModule.KNIGHT_VALUE);
        BATTERY_THREAT_PENALTIES[BISHOP] = scaledBatteryPenalty(MaterialModule.BISHOP_VALUE);
        BATTERY_THREAT_PENALTIES[ROOK] = scaledBatteryPenalty(MaterialModule.ROOK_VALUE);
        BATTERY_THREAT_PENALTIES[QUEEN] = scaledBatteryPenalty(MaterialModule.QUEEN_VALUE);
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
        EvaluationContext.BoardView board = context.getBoard();
        if (board == null) {
            midgameScoreCache = 0;
            endgameScoreCache = 0;
            dirty = false;
            return;
        }

        long whiteAttacks = context.getWhiteAttackMap();
        long blackAttacks = context.getBlackAttackMap();

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
        long pieces = isWhite ? board.getWhitePieces() : board.getBlackPieces();
        long enemyPawns = isWhite ? board.getBlackPawns() : board.getWhitePawns();
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
        penalty += evaluateBatteryContribution(board, isWhite);
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
        long occupancy = board.getAllPieces();

        long pawns = isWhite ? board.getWhitePawns() : board.getBlackPawns();
        minValue = findCheapestDefender(pawns, MaterialModule.PAWN_VALUE, minValue,
                square -> (PAWN_ATTACKS[colorIndex][square] & mask) != 0);
        if (minValue == MaterialModule.PAWN_VALUE) {
            return minValue;
        }

        long knights = isWhite ? board.getWhiteKnights() : board.getBlackKnights();
        minValue = findCheapestDefender(knights, MaterialModule.KNIGHT_VALUE, minValue,
                square -> (julius.game.chessengine.helper.KnightHelper.knightMoveTable[square] & mask) != 0);
        if (minValue == MaterialModule.PAWN_VALUE) {
            return minValue;
        }

        long bishops = isWhite ? board.getWhiteBishops() : board.getBlackBishops();
        minValue = findCheapestDefender(bishops, MaterialModule.BISHOP_VALUE, minValue,
                square -> (BISHOP_HELPER.calculateBishopMoves(square, occupancy) & mask) != 0);
        if (minValue == MaterialModule.PAWN_VALUE) {
            return minValue;
        }

        long rooks = isWhite ? board.getWhiteRooks() : board.getBlackRooks();
        minValue = findCheapestDefender(rooks, MaterialModule.ROOK_VALUE, minValue,
                square -> (ROOK_HELPER.calculateRookMoves(square, occupancy) & mask) != 0);
        if (minValue == MaterialModule.PAWN_VALUE) {
            return minValue;
        }

        long queens = isWhite ? board.getWhiteQueens() : board.getBlackQueens();
        minValue = findCheapestDefender(queens, MaterialModule.QUEEN_VALUE, minValue,
                square -> ((BISHOP_HELPER.calculateBishopMoves(square, occupancy)
                        | ROOK_HELPER.calculateRookMoves(square, occupancy)) & mask) != 0);
        if (minValue == MaterialModule.PAWN_VALUE) {
            return minValue;
        }

        long king = isWhite ? board.getWhiteKing() : board.getBlackKing();
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

    private static int scaledBatteryPenalty(int materialValue) {
        return Math.max(6, materialValue / 16);
    }

    private int evaluateBatteryContribution(EvaluationContext.BoardView board, boolean isWhite) {
        if (board == null) {
            return 0;
        }
        long ownPieces = isWhite ? board.getWhitePieces() : board.getBlackPieces();
        long enemyPieces = isWhite ? board.getBlackPieces() : board.getWhitePieces();
        long ownKing = isWhite ? board.getWhiteKing() : board.getBlackKing();
        long enemyKing = isWhite ? board.getBlackKing() : board.getWhiteKing();

        int score = 0;
        score += evaluateBatteries(board, isWhite, enemyPieces, enemyKing, true);
        score += evaluateBatteries(board, !isWhite, ownPieces, ownKing, false);
        return score;
    }

    private int evaluateBatteries(EvaluationContext.BoardView board,
                                   boolean batteryColorIsWhite,
                                   long targetPieces,
                                   long targetKing,
                                   boolean sameSide) {
        long occupancy = board.getAllPieces();
        long friendlyPieces = batteryColorIsWhite ? board.getWhitePieces() : board.getBlackPieces();

        long bishops = batteryColorIsWhite ? board.getWhiteBishops() : board.getBlackBishops();
        long rooks = batteryColorIsWhite ? board.getWhiteRooks() : board.getBlackRooks();
        long queens = batteryColorIsWhite ? board.getWhiteQueens() : board.getBlackQueens();

        int score = 0;
        score += evaluateBatteryRays(board, bishops | queens, occupancy, friendlyPieces,
                targetPieces, targetKing, sameSide, BISHOP_DELTAS);
        score += evaluateBatteryRays(board, rooks | queens, occupancy, friendlyPieces,
                targetPieces, targetKing, sameSide, ROOK_DELTAS);
        return score;
    }

    private int evaluateBatteryRays(EvaluationContext.BoardView board,
                                     long sliders,
                                     long occupancy,
                                     long friendlyPieces,
                                     long targetPieces,
                                     long targetKing,
                                     boolean sameSide,
                                     int[] deltas) {
        int score = 0;
        long remaining = sliders;
        while (remaining != 0) {
            long bit = remaining & -remaining;
            int square = Long.numberOfTrailingZeros(bit);
            PieceType rearType = board.getPieceTypeAtIndex(square);
            if (rearType == null) {
                remaining ^= bit;
                continue;
            }
            for (int delta : deltas) {
                int frontSquare = locateFrontSlider(board, square, delta, occupancy, friendlyPieces);
                if (frontSquare == -1 || square >= frontSquare) {
                    continue;
                }
                PieceType frontType = board.getPieceTypeAtIndex(frontSquare);
                if (!supportsDirection(frontType, delta)) {
                    continue;
                }
                long attackMask = rayBeyond(frontSquare, delta, occupancy);
                long effectiveMask = attackMask & ~friendlyPieces;
                if (effectiveMask == 0) {
                    continue;
                }
                if (sameSide) {
                    score += batteryFormationBonus(rearType, frontType);
                }
                if (!sameSide) {
                    long threatenedPieces = effectiveMask & targetPieces;
                    if (threatenedPieces != 0) {
                        score += batteryThreatPenalty(board, threatenedPieces);
                    }
                    if ((effectiveMask & targetKing) != 0) {
                        score -= BATTERY_KING_PRESSURE;
                    }
                }
            }
            remaining ^= bit;
        }
        return score;
    }

    private int locateFrontSlider(EvaluationContext.BoardView board,
                                  int square,
                                  int delta,
                                  long occupancy,
                                  long friendlyPieces) {
        int current = step(square, delta);
        while (current != -1) {
            long mask = 1L << current;
            if ((occupancy & mask) != 0) {
                if ((friendlyPieces & mask) != 0) {
                    return current;
                }
                return -1;
            }
            current = step(current, delta);
        }
        return -1;
    }

    private long rayBeyond(int square, int delta, long occupancy) {
        long mask = 0L;
        int current = square;
        while (true) {
            current = step(current, delta);
            if (current == -1) {
                break;
            }
            long bit = 1L << current;
            mask |= bit;
            if ((occupancy & bit) != 0) {
                break;
            }
        }
        return mask;
    }

    private int batteryThreatPenalty(EvaluationContext.BoardView board, long threatenedPieces) {
        int penalty = 0;
        long remaining = threatenedPieces;
        while (remaining != 0) {
            long bit = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(bit);
            PieceType type = board.getPieceTypeAtIndex(index);
            if (type != null) {
                int typeBits = MoveHelper.pieceTypeToInt(type);
                penalty -= BATTERY_THREAT_PENALTIES[typeBits];
            }
            remaining ^= bit;
        }
        return penalty;
    }

    private static boolean supportsDirection(PieceType type, int delta) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case BISHOP -> isDiagonalDelta(delta);
            case ROOK -> isOrthogonalDelta(delta);
            case QUEEN -> true;
            default -> false;
        };
    }

    private static boolean isDiagonalDelta(int delta) {
        return delta == 9 || delta == -9 || delta == 7 || delta == -7;
    }

    private static boolean isOrthogonalDelta(int delta) {
        return delta == 8 || delta == -8 || delta == 1 || delta == -1;
    }

    private int batteryFormationBonus(PieceType rearType, PieceType frontType) {
        if (rearType == null || frontType == null) {
            return 0;
        }
        int bonus = BATTERY_FORMATION_BONUS;
        if (rearType == PieceType.QUEEN || frontType == PieceType.QUEEN) {
            bonus += 4;
        }
        if (rearType == PieceType.ROOK && frontType == PieceType.ROOK) {
            bonus += 2;
        }
        return bonus;
    }

    private static int step(int square, int delta) {
        int file = square & 7;
        int rank = square >>> 3;
        return switch (delta) {
            case 1 -> file == 7 ? -1 : square + 1;
            case -1 -> file == 0 ? -1 : square - 1;
            case 8 -> rank == 7 ? -1 : square + 8;
            case -8 -> rank == 0 ? -1 : square - 8;
            case 9 -> (file == 7 || rank == 7) ? -1 : square + 9;
            case -9 -> (file == 0 || rank == 0) ? -1 : square - 9;
            case 7 -> (file == 0 || rank == 7) ? -1 : square + 7;
            case -7 -> (file == 7 || rank == 0) ? -1 : square - 7;
            default -> -1;
        };
    }
}
