package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.RookHelper;

import java.util.Arrays;
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

    private final int[][] squarePenalties = new int[2][64];
    private final int[] totals = new int[2];
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
        rebuildFromContext(Objects.requireNonNull(context, "context"));
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

    private void rebuildFromContext(EvaluationContext context) {
        EvaluationContext.BoardView board = context.board();
        Arrays.fill(totals, 0);
        for (int[] penalties : squarePenalties) {
            Arrays.fill(penalties, 0);
        }

        long whiteAttacks = context.whiteAttackMap();
        long blackAttacks = context.blackAttackMap();

        rebuildSide(board, true, whiteAttacks, blackAttacks);
        rebuildSide(board, false, blackAttacks, whiteAttacks);

        midgameScoreCache = totals[WHITE] - totals[BLACK];
        endgameScoreCache = midgameScoreCache;
        dirty = false;
    }

    private void rebuildSide(EvaluationContext.BoardView board, boolean isWhite,
                             long friendlyAttacks, long enemyAttacks) {
        int color = isWhite ? WHITE : BLACK;
        long pieces = isWhite ? board.whitePieces() : board.blackPieces();
        long enemyPawns = isWhite ? board.blackPawns() : board.whitePawns();
        int enemyPawnColor = isWhite ? BLACK : WHITE;
        long enemyPawnAttacks = computePawnAttackMask(enemyPawns, enemyPawnColor);

        long remaining = pieces;
        while (remaining != 0) {
            long bit = remaining & -remaining;
            int square = Long.numberOfTrailingZeros(bit);
            int penalty = computePiecePenalty(board, isWhite, square, friendlyAttacks,
                    enemyAttacks, enemyPawnAttacks);
            squarePenalties[color][square] = penalty;
            totals[color] += penalty;
            remaining ^= bit;
        }
    }

    private int computePiecePenalty(EvaluationContext.BoardView board, boolean isWhite, int square,
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
        int penalty = 0;
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
        return penalty;
    }

    private void updateForMove(MoveContext moveContext) {
        EvaluationContext current = moveContext.currentContext();
        if (current == null) {
            Arrays.fill(totals, 0);
            for (int[] penalties : squarePenalties) {
                Arrays.fill(penalties, 0);
            }
            midgameScoreCache = 0;
            endgameScoreCache = 0;
            dirty = false;
            return;
        }
        EvaluationContext previous = moveContext.previousContext();
        if (previous == null) {
            rebuildFromContext(current);
            return;
        }

        if (dirty) {
            rebuildFromContext(current);
            return;
        }

        boolean[] impactedWhite = new boolean[64];
        boolean[] impactedBlack = new boolean[64];

        markMoveSquares(moveContext.move(), impactedWhite, impactedBlack);
        markAttackDifferences(previous, current, impactedWhite, impactedBlack);

        if (hasNotImpacted(impactedWhite) && hasNotImpacted(impactedBlack)) {
            return;
        }

        EvaluationContext.BoardView board = current.board();
        long whiteFriendlyAttacks = current.whiteAttackMap();
        long blackFriendlyAttacks = current.blackAttackMap();
        long whiteEnemyAttacks = current.blackAttackMap();
        long blackEnemyAttacks = current.whiteAttackMap();
        long whiteEnemyPawnAttacks = computePawnAttackMask(board.blackPawns(), BLACK);
        long blackEnemyPawnAttacks = computePawnAttackMask(board.whitePawns(), WHITE);

        updateSidePenalties(true, board, whiteFriendlyAttacks, whiteEnemyAttacks,
                whiteEnemyPawnAttacks, impactedWhite);
        updateSidePenalties(false, board, blackFriendlyAttacks, blackEnemyAttacks,
                blackEnemyPawnAttacks, impactedBlack);

        midgameScoreCache = totals[WHITE] - totals[BLACK];
        endgameScoreCache = midgameScoreCache;
    }

    private void markMoveSquares(int move, boolean[] impactedWhite, boolean[] impactedBlack) {
        int from = MoveHelper.deriveFromIndex(move);
        int to = MoveHelper.deriveToIndex(move);
        boolean moverIsWhite = MoveHelper.isWhitesMove(move);
        markSquare(moverIsWhite ? impactedWhite : impactedBlack, from);
        markSquare(moverIsWhite ? impactedWhite : impactedBlack, to);

        int capturedPiece = MoveHelper.deriveCapturedPieceTypeBits(move);
        if (capturedPiece != 0) {
            int captureSquare = MoveHelper.isEnPassantMove(move)
                    ? (MoveHelper.isWhitesMove(move) ? to - 8 : to + 8)
                    : to;
            markSquare(moverIsWhite ? impactedBlack : impactedWhite, captureSquare);
        }

        if (MoveHelper.isCastlingMove(move)) {
            markCastlingSquares(MoveHelper.isWhitesMove(move), to,
                    moverIsWhite ? impactedWhite : impactedBlack);
        }
    }

    private void markAttackDifferences(EvaluationContext previous, EvaluationContext current,
                                       boolean[] impactedWhite, boolean[] impactedBlack) {
        long prevWhiteAttacks = previous.whiteAttackMap();
        long currWhiteAttacks = current.whiteAttackMap();
        long prevBlackAttacks = previous.blackAttackMap();
        long currBlackAttacks = current.blackAttackMap();

        EvaluationContext.BoardView prevBoard = previous.board();
        EvaluationContext.BoardView currBoard = current.board();

        long whitePiecesCombined = prevBoard.whitePieces() | currBoard.whitePieces();
        long blackPiecesCombined = prevBoard.blackPieces() | currBoard.blackPieces();

        long attackDiffUnion = (prevBlackAttacks ^ currBlackAttacks) | (prevWhiteAttacks ^ currWhiteAttacks);

        markSquaresFromMask(impactedWhite, whitePiecesCombined & attackDiffUnion);
        markSquaresFromMask(impactedBlack, blackPiecesCombined & attackDiffUnion);

        long prevBlackPawnAttacks = computePawnAttackMask(prevBoard.blackPawns(), BLACK);
        long currBlackPawnAttacks = computePawnAttackMask(currBoard.blackPawns(), BLACK);
        long prevWhitePawnAttacks = computePawnAttackMask(prevBoard.whitePawns(), WHITE);
        long currWhitePawnAttacks = computePawnAttackMask(currBoard.whitePawns(), WHITE);

        markSquaresFromMask(impactedWhite, whitePiecesCombined & (prevBlackPawnAttacks ^ currBlackPawnAttacks));
        markSquaresFromMask(impactedBlack, blackPiecesCombined & (prevWhitePawnAttacks ^ currWhitePawnAttacks));
    }

    private void updateSidePenalties(boolean isWhite, EvaluationContext.BoardView board,
                                     long friendlyAttacks, long enemyAttacks,
                                     long enemyPawnAttacks, boolean[] impacted) {
        int color = isWhite ? WHITE : BLACK;
        long pieces = isWhite ? board.whitePieces() : board.blackPieces();
        for (int square = 0; square < impacted.length; square++) {
            if (!impacted[square]) {
                continue;
            }
            int old = squarePenalties[color][square];
            totals[color] -= old;
            squarePenalties[color][square] = 0;
            if ((pieces & (1L << square)) == 0) {
                continue;
            }
            int penalty = computePiecePenalty(board, isWhite, square, friendlyAttacks,
                    enemyAttacks, enemyPawnAttacks);
            squarePenalties[color][square] = penalty;
            totals[color] += penalty;
        }
    }

    private static void markCastlingSquares(boolean whiteMove, int kingDestination, boolean[] impacted) {
        if (whiteMove) {
            if (kingDestination == 6) {
                markSquare(impacted, 5);
                markSquare(impacted, 7);
            } else {
                markSquare(impacted, 3);
                markSquare(impacted, 0);
            }
        } else {
            if (kingDestination == 62) {
                markSquare(impacted, 61);
                markSquare(impacted, 63);
            } else {
                markSquare(impacted, 59);
                markSquare(impacted, 56);
            }
        }
    }

    private static void markSquare(boolean[] impacted, int square) {
        if (square >= 0 && square < impacted.length) {
            impacted[square] = true;
        }
    }

    private static void markSquaresFromMask(boolean[] impacted, long mask) {
        while (mask != 0) {
            int square = Long.numberOfTrailingZeros(mask);
            impacted[square] = true;
            mask &= mask - 1;
        }
    }

    private static boolean hasNotImpacted(boolean[] impacted) {
        for (boolean flag : impacted) {
            if (flag) {
                return false;
            }
        }
        return true;
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
