package julius.game.chessengine.evaluation;

import java.util.Arrays;
import java.util.Objects;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.tuning.Tuning;
import lombok.Setter;

/**
 * Tracks per-side material using incremental updates so the evaluation pipeline can
 * access midgame and endgame material totals without rescanning the board.
 */
public final class MaterialModule implements EvaluationModule {

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
    private final int[] midgamePst = new int[2];
    private final int[] endgamePst = new int[2];
    private final int[] bishopCounts = new int[2];
    private final int bishopPairBonus;

    @Setter
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
        return Tuning.pawnValue();
    }

    public static int knightValue() {
        return Tuning.knightValue();
    }

    public static int bishopValue() {
        return Tuning.bishopValue();
    }

    public static int rookValue() {
        return Tuning.rookValue();
    }

    public static int queenValue() {
        return Tuning.queenValue();
    }

    public static int bishopPairBonus() {
        return Tuning.bishopPairBonus();
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
        int fromSq = MoveHelper.deriveFromIndex(move);
        int toSq = MoveHelper.deriveToIndex(move);

        if (!undo) {
            // PST: piece moves from -> to
            removePst(mover, movedPiece, fromSq);
            addPst(mover, promotion != 0 ? promotion : movedPiece, toSq);

            if (captured != 0) {
                removePst(mover ^ 1, captured, captureSquare(move));
                if (pawnChangeListener != null && captured == PAWN) {
                    pawnChangeListener.onPawnRemoved(opponentIsWhite, captureSquare(move));
                }
                removePiece(mover ^ 1, captured);
            }
            if (promotion != 0) {
                if (pawnChangeListener != null && movedPiece == PAWN) {
                    pawnChangeListener.onPawnRemoved(moverIsWhite, fromSq);
                }
                removePiece(mover, movedPiece);
                addPiece(mover, promotion);
            }
            if (MoveHelper.isCastlingMove(move)) {
                updateCastlingRookPst(mover, toSq, false);
            }
        } else {
            if (MoveHelper.isCastlingMove(move)) {
                updateCastlingRookPst(mover, toSq, true);
            }
            // PST: reverse
            removePst(mover, promotion != 0 ? promotion : movedPiece, toSq);
            addPst(mover, movedPiece, fromSq);

            if (promotion != 0) {
                removePiece(mover, promotion);
                addPiece(mover, movedPiece);
                if (pawnChangeListener != null && movedPiece == PAWN) {
                    pawnChangeListener.onPawnAdded(moverIsWhite, fromSq);
                }
            }
            if (captured != 0) {
                addPst(mover ^ 1, captured, captureSquare(move));
                addPiece(mover ^ 1, captured);
                if (pawnChangeListener != null && captured == PAWN) {
                    pawnChangeListener.onPawnAdded(opponentIsWhite, captureSquare(move));
                }
            }
        }

        updateScoreCaches();
    }

    private void updateCastlingRookPst(int color, int kingTo, boolean undo) {
        int rookFrom, rookTo;
        if (kingTo == 6 || kingTo == 62) { // kingside
            rookFrom = (color == WHITE) ? 7 : 63;
            rookTo = (color == WHITE) ? 5 : 61;
        } else { // queenside
            rookFrom = (color == WHITE) ? 0 : 56;
            rookTo = (color == WHITE) ? 3 : 59;
        }
        if (undo) {
            removePst(color, ROOK, rookTo);
            addPst(color, ROOK, rookFrom);
        } else {
            removePst(color, ROOK, rookFrom);
            addPst(color, ROOK, rookTo);
        }
    }

    private void rebuildFromContext(EvaluationContext context) {
        Arrays.fill(midgameMaterial, 0);
        Arrays.fill(endgameMaterial, 0);
        Arrays.fill(midgamePst, 0);
        Arrays.fill(endgamePst, 0);
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

        // PSTs from all pieces including kings
        accumulatePst(WHITE, PAWN, context.getWhitePawns());
        accumulatePst(WHITE, KNIGHT, context.getWhiteKnights());
        accumulatePst(WHITE, BISHOP, context.getWhiteBishops());
        accumulatePst(WHITE, ROOK, context.getWhiteRooks());
        accumulatePst(WHITE, QUEEN, context.getWhiteQueens());
        accumulatePst(WHITE, KING, context.getWhiteKing());
        accumulatePst(BLACK, PAWN, context.getBlackPawns());
        accumulatePst(BLACK, KNIGHT, context.getBlackKnights());
        accumulatePst(BLACK, BISHOP, context.getBlackBishops());
        accumulatePst(BLACK, ROOK, context.getBlackRooks());
        accumulatePst(BLACK, QUEEN, context.getBlackQueens());
        accumulatePst(BLACK, KING, context.getBlackKing());

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

    private void accumulatePst(int colorIndex, int pieceType, long bitboard) {
        while (bitboard != 0) {
            int sq = Long.numberOfTrailingZeros(bitboard);
            bitboard &= bitboard - 1;
            int pstSq = (colorIndex == WHITE) ? sq : (sq ^ 56);
            midgamePst[colorIndex] += PieceSquareTables.midgame(pieceType, pstSq);
            endgamePst[colorIndex] += PieceSquareTables.endgame(pieceType, pstSq);
        }
    }

    private void addPst(int colorIndex, int pieceType, int square) {
        if (pieceType == 0) return;
        int pstSq = (colorIndex == WHITE) ? square : (square ^ 56);
        midgamePst[colorIndex] += PieceSquareTables.midgame(pieceType, pstSq);
        endgamePst[colorIndex] += PieceSquareTables.endgame(pieceType, pstSq);
    }

    private void removePst(int colorIndex, int pieceType, int square) {
        if (pieceType == 0) return;
        int pstSq = (colorIndex == WHITE) ? square : (square ^ 56);
        midgamePst[colorIndex] -= PieceSquareTables.midgame(pieceType, pstSq);
        endgamePst[colorIndex] -= PieceSquareTables.endgame(pieceType, pstSq);
    }

    private void updateScoreCaches() {
        midgameScoreCache = (midgameMaterial[WHITE] + midgamePst[WHITE])
                          - (midgameMaterial[BLACK] + midgamePst[BLACK]);
        endgameScoreCache = (endgameMaterial[WHITE] + endgamePst[WHITE])
                          - (endgameMaterial[BLACK] + endgamePst[BLACK]);
        dirty = false;
    }
}
