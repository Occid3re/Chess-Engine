package julius.game.chessengine.evaluation;

import java.util.Arrays;
import java.util.Objects;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.tuning.TunableParameter;
/**
 * Tracks per-side material using incremental updates so the evaluation pipeline can
 * access midgame and endgame material totals without rescanning the board.
 */
public final class MaterialModule implements EvaluationModule {

    public static final int DEFAULT_PAWN_VALUE = 100;
    public static final int DEFAULT_KNIGHT_VALUE = 320;
    public static final int DEFAULT_BISHOP_VALUE = 330;
    public static final int DEFAULT_ROOK_VALUE = 500;
    public static final int DEFAULT_QUEEN_VALUE = 900;
    public static final int DEFAULT_BISHOP_PAIR_BONUS = 40;

    private static final TunableParameter PAWN_VALUE = TunableParameter.of("material.pawnValue", DEFAULT_PAWN_VALUE);
    private static final TunableParameter KNIGHT_VALUE = TunableParameter.of("material.knightValue", DEFAULT_KNIGHT_VALUE);
    private static final TunableParameter BISHOP_VALUE = TunableParameter.of("material.bishopValue", DEFAULT_BISHOP_VALUE);
    private static final TunableParameter ROOK_VALUE = TunableParameter.of("material.rookValue", DEFAULT_ROOK_VALUE);
    private static final TunableParameter QUEEN_VALUE = TunableParameter.of("material.queenValue", DEFAULT_QUEEN_VALUE);
    private static final TunableParameter BISHOP_PAIR_BONUS = TunableParameter.of("material.bishopPairBonus", DEFAULT_BISHOP_PAIR_BONUS);

    public interface PawnChangeListener {
        void onPawnAdded(boolean isWhite, int squareIndex);
        void onPawnRemoved(boolean isWhite, int squareIndex);
    }

    private static final int WHITE = 0;
    private static final int BLACK = 1;

    private static final int PAWN = MoveHelper.pieceTypeToInt(PieceType.PAWN);
    private static final int KNIGHT = MoveHelper.pieceTypeToInt(PieceType.KNIGHT);
    private static final int BISHOP = MoveHelper.pieceTypeToInt(PieceType.BISHOP);
    private static final int ROOK = MoveHelper.pieceTypeToInt(PieceType.ROOK);
    private static final int QUEEN = MoveHelper.pieceTypeToInt(PieceType.QUEEN);
    private static final int KING = MoveHelper.pieceTypeToInt(PieceType.KING);

    private final int[] midgameValues = new int[7];
    private final int[] endgameValues = new int[7];

    private final int[] midgameMaterial = new int[2];
    private final int[] endgameMaterial = new int[2];
    private final int[] bishopCounts = new int[2];
    private final int bishopPairBonus;

    private PawnChangeListener pawnChangeListener;
    private boolean dirty = true;
    private int midgameScoreCache;
    private int endgameScoreCache;

    public MaterialModule() {
        midgameValues[PAWN] = pawnValue();
        midgameValues[KNIGHT] = knightValue();
        midgameValues[BISHOP] = bishopValue();
        midgameValues[ROOK] = rookValue();
        midgameValues[QUEEN] = queenValue();

        endgameValues[PAWN] = pawnValue();
        endgameValues[KNIGHT] = knightValue();
        endgameValues[BISHOP] = bishopValue();
        endgameValues[ROOK] = rookValue();
        endgameValues[QUEEN] = queenValue();

        this.bishopPairBonus = bishopPairBonus();
    }

    public static int pawnValue() {
        return PAWN_VALUE.getInt();
    }

    public static int knightValue() {
        return KNIGHT_VALUE.getInt();
    }

    public static int bishopValue() {
        return BISHOP_VALUE.getInt();
    }

    public static int rookValue() {
        return ROOK_VALUE.getInt();
    }

    public static int queenValue() {
        return QUEEN_VALUE.getInt();
    }

    public static int bishopPairBonus() {
        return BISHOP_PAIR_BONUS.getInt();
    }

    @Override
    public void initialize(EvaluationContext context) {
        rebuildFromContext(Objects.requireNonNull(context, "context"));
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
        boolean forward = isForwardMove(moveContext);
        updateMaterial(moveContext.getMove(), !forward);
    }

    @Override
    public void undoMove(MoveContext moveContext) {
        boolean forward = isForwardMove(moveContext);
        updateMaterial(moveContext.getMove(), !forward);
    }

    public void setPawnChangeListener(PawnChangeListener listener) {
        this.pawnChangeListener = listener;
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

    private boolean isForwardMove(MoveContext moveContext) {
        EvaluationContext previous = moveContext.getPreviousContext();
        if (previous == null) {
            return true;
        }
        boolean moveIsWhite = MoveHelper.isWhitesMove(moveContext.getMove());
        return previous.isWhiteToMove() == moveIsWhite;
    }

    private void updateMaterial(int move, boolean undo) {
        int mover = MoveHelper.isWhitesMove(move) ? WHITE : BLACK;
        int promotion = MoveHelper.derivePromotionPieceTypeBits(move);
        int captured = MoveHelper.deriveCapturedPieceTypeBits(move);
        int movedPiece = MoveHelper.derivePieceTypeBits(move);
        boolean moverIsWhite = mover == WHITE;
        boolean opponentIsWhite = !moverIsWhite;

        if (!undo) {
            if (captured != 0) {
                if (pawnChangeListener != null && captured == PAWN) {
                    pawnChangeListener.onPawnRemoved(opponentIsWhite, captureSquare(move));
                }
                removePiece(mover ^ 1, captured);
            }
            if (promotion != 0) {
                if (pawnChangeListener != null && movedPiece == PAWN) {
                    pawnChangeListener.onPawnRemoved(moverIsWhite, MoveHelper.deriveFromIndex(move));
                }
                removePiece(mover, movedPiece);
                addPiece(mover, promotion);
            }
        } else {
            if (promotion != 0) {
                removePiece(mover, promotion);
                addPiece(mover, movedPiece);
                if (pawnChangeListener != null && movedPiece == PAWN) {
                    pawnChangeListener.onPawnAdded(moverIsWhite, MoveHelper.deriveFromIndex(move));
                }
            }
            if (captured != 0) {
                addPiece(mover ^ 1, captured);
                if (pawnChangeListener != null && captured == PAWN) {
                    pawnChangeListener.onPawnAdded(opponentIsWhite, captureSquare(move));
                }
            }
        }

        updateScoreCaches();
    }

    private void rebuildFromContext(EvaluationContext context) {
        Arrays.fill(midgameMaterial, 0);
        Arrays.fill(endgameMaterial, 0);
        Arrays.fill(bishopCounts, 0);

        accumulatePieces(WHITE, PAWN, Long.bitCount(context.getWhitePawns()));
        accumulatePieces(WHITE, KNIGHT, Long.bitCount(context.getWhiteKnights()));
        accumulatePieces(WHITE, BISHOP, Long.bitCount(context.getWhiteBishops()));
        accumulatePieces(WHITE, ROOK, Long.bitCount(context.getWhiteRooks()));
        accumulatePieces(WHITE, QUEEN, Long.bitCount(context.getWhiteQueens()));

        accumulatePieces(BLACK, PAWN, Long.bitCount(context.getBlackPawns()));
        accumulatePieces(BLACK, KNIGHT, Long.bitCount(context.getBlackKnights()));
        accumulatePieces(BLACK, BISHOP, Long.bitCount(context.getBlackBishops()));
        accumulatePieces(BLACK, ROOK, Long.bitCount(context.getBlackRooks()));
        accumulatePieces(BLACK, QUEEN, Long.bitCount(context.getBlackQueens()));

        updateScoreCaches();
        dirty = false;
    }

    private void accumulatePieces(int colorIndex, int pieceType, int count) {
        if (count <= 0 || pieceType == 0 || pieceType == KING) {
            return;
        }
        midgameMaterial[colorIndex] += count * midgameValues[pieceType];
        endgameMaterial[colorIndex] += count * endgameValues[pieceType];
        if (pieceType == BISHOP) {
            int previous = bishopCounts[colorIndex];
            bishopCounts[colorIndex] = previous + count;
            if (previous < 2 && bishopCounts[colorIndex] >= 2) {
                midgameMaterial[colorIndex] += bishopPairBonus;
                endgameMaterial[colorIndex] += bishopPairBonus;
            }
        }
    }

    private void addPiece(int colorIndex, int pieceType) {
        if (pieceType == 0 || pieceType == KING) {
            return;
        }
        midgameMaterial[colorIndex] += midgameValues[pieceType];
        endgameMaterial[colorIndex] += endgameValues[pieceType];
        if (pieceType == BISHOP) {
            int previous = bishopCounts[colorIndex];
            bishopCounts[colorIndex] = previous + 1;
            if (previous == 1) {
                midgameMaterial[colorIndex] += bishopPairBonus;
                endgameMaterial[colorIndex] += bishopPairBonus;
            }
        }
    }

    private void removePiece(int colorIndex, int pieceType) {
        if (pieceType == 0 || pieceType == KING) {
            return;
        }
        midgameMaterial[colorIndex] -= midgameValues[pieceType];
        endgameMaterial[colorIndex] -= endgameValues[pieceType];
        if (pieceType == BISHOP) {
            int previous = bishopCounts[colorIndex];
            bishopCounts[colorIndex] = previous - 1;
            if (previous == 2) {
                midgameMaterial[colorIndex] -= bishopPairBonus;
                endgameMaterial[colorIndex] -= bishopPairBonus;
            }
        }
    }

    private int captureSquare(int move) {
        if (MoveHelper.isEnPassantMove(move)) {
            int toIndex = MoveHelper.deriveToIndex(move);
            return MoveHelper.isWhitesMove(move) ? toIndex - 8 : toIndex + 8;
        }
        return MoveHelper.deriveToIndex(move);
    }

    private void updateScoreCaches() {
        midgameScoreCache = midgameMaterial[WHITE] - midgameMaterial[BLACK];
        endgameScoreCache = endgameMaterial[WHITE] - endgameMaterial[BLACK];
        dirty = false;
    }
}
