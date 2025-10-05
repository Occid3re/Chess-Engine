package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.ImmutableBoardView;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.tuning.Tuning;

import static julius.game.chessengine.evaluation.MaterialModule.bishopValue;
import static julius.game.chessengine.evaluation.MaterialModule.knightValue;
import static julius.game.chessengine.evaluation.MaterialModule.pawnValue;
import static julius.game.chessengine.evaluation.MaterialModule.queenValue;
import static julius.game.chessengine.evaluation.MaterialModule.rookValue;

import java.util.Objects;

import static julius.game.chessengine.helper.PawnMoveTables.PAWN_ATTACKS;

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

    private final int[] hangingPenalties = new int[7];
    private final int[] pawnThreatPenalties = new int[7];

    public ThreatModule() {
        hangingPenalties[PAWN] = Tuning.hangingPawnPenalty();
        hangingPenalties[KNIGHT] = Tuning.hangingKnightPenalty();
        hangingPenalties[BISHOP] = Tuning.hangingBishopPenalty();
        hangingPenalties[ROOK] = Tuning.hangingRookPenalty();
        hangingPenalties[QUEEN] = Tuning.hangingQueenPenalty();

        pawnThreatPenalties[KNIGHT] = Tuning.pawnThreatKnightPenalty();
        pawnThreatPenalties[BISHOP] = Tuning.pawnThreatBishopPenalty();
        pawnThreatPenalties[ROOK] = Tuning.pawnThreatRookPenalty();
        pawnThreatPenalties[QUEEN] = Tuning.pawnThreatQueenPenalty();
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
        ImmutableBoardView board = context.getBoardView();
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

    private int evaluateSide(ImmutableBoardView board, boolean isWhite, long friendlyAttacks, long enemyAttacks) {
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
            if ((friendlyAttacks & mask) != 0) {
                remaining ^= bit;
                continue;
            }

            penalty += ensurePieceLossPenalty(typeBits, hangingPenalties[typeBits]);
            if (typeBits > PAWN && (enemyPawnAttacks & mask) != 0) {
                penalty += ensurePawnThreatPenalty(typeBits, pawnThreatPenalties[typeBits]);
            }
            remaining ^= bit;
        }
        return penalty;
    }

    private static int ensurePieceLossPenalty(int pieceType, int configuredPenalty) {
        int materialValue = materialValue(pieceType);
        if (materialValue <= 0) {
            return configuredPenalty;
        }
        return Math.min(configuredPenalty, -materialValue);
    }

    private static int ensurePawnThreatPenalty(int pieceType, int configuredPenalty) {
        int materialValue = materialValue(pieceType);
        if (materialValue <= 0) {
            return configuredPenalty;
        }
        int pawnTradeLoss = Math.max(0, materialValue - pawnValue());
        if (pawnTradeLoss == 0) {
            return configuredPenalty;
        }
        return Math.min(configuredPenalty, -pawnTradeLoss);
    }

    private static int materialValue(int pieceType) {
        if (pieceType == PAWN) {
            return pawnValue();
        }
        if (pieceType == KNIGHT) {
            return knightValue();
        }
        if (pieceType == BISHOP) {
            return bishopValue();
        }
        if (pieceType == ROOK) {
            return rookValue();
        }
        if (pieceType == QUEEN) {
            return queenValue();
        }
        return 0;
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
