package julius.game.chessengine.evaluation;

import java.util.Arrays;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;
/**
 * Tracks per-side material using incremental updates so the evaluation pipeline can
 * access midgame and endgame material totals without rescanning the board.
 */
public final class MaterialModule implements EvaluationModule {

    public static final int PAWN_VALUE = 100;
    public static final int KNIGHT_VALUE = 320;
    public static final int BISHOP_VALUE = 330;
    public static final int ROOK_VALUE = 500;
    public static final int QUEEN_VALUE = 900;
    public static final int BISHOP_PAIR_BONUS = 40;

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

    private static final int[] MIDGAME_VALUES = new int[7];
    private static final int[] ENDGAME_VALUES = new int[7];

    static {
        MIDGAME_VALUES[PAWN] = PAWN_VALUE;
        MIDGAME_VALUES[KNIGHT] = KNIGHT_VALUE;
        MIDGAME_VALUES[BISHOP] = BISHOP_VALUE;
        MIDGAME_VALUES[ROOK] = ROOK_VALUE;
        MIDGAME_VALUES[QUEEN] = QUEEN_VALUE;

        ENDGAME_VALUES[PAWN] = PAWN_VALUE;
        ENDGAME_VALUES[KNIGHT] = KNIGHT_VALUE;
        ENDGAME_VALUES[BISHOP] = BISHOP_VALUE;
        ENDGAME_VALUES[ROOK] = ROOK_VALUE;
        ENDGAME_VALUES[QUEEN] = QUEEN_VALUE;
    }

    private final int[] midgameMaterial = new int[2];
    private final int[] endgameMaterial = new int[2];
    private final int[] bishopCounts = new int[2];

    private PawnChangeListener pawnChangeListener;
    private boolean dirty = true;
    private int midgameScoreCache;
    private int endgameScoreCache;

    @Override
    public void initialize(EvaluationContext context) {
        rebuildFromBoard(context.getBoard());
    }

    @Override
    public void evaluate(EvaluationContext context) {
        if (!dirty) {
            return;
        }
        rebuildFromBoard(context.getBoard());
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

    private void rebuildFromBoard(EvaluationContext.BoardView board) {
        Arrays.fill(midgameMaterial, 0);
        Arrays.fill(endgameMaterial, 0);
        Arrays.fill(bishopCounts, 0);

        if (board == null) {
            updateScoreCaches();
            dirty = false;
            return;
        }

        accumulatePieces(WHITE, PAWN, Long.bitCount(board.getWhitePawns()));
        accumulatePieces(WHITE, KNIGHT, Long.bitCount(board.getWhiteKnights()));
        accumulatePieces(WHITE, BISHOP, Long.bitCount(board.getWhiteBishops()));
        accumulatePieces(WHITE, ROOK, Long.bitCount(board.getWhiteRooks()));
        accumulatePieces(WHITE, QUEEN, Long.bitCount(board.getWhiteQueens()));

        accumulatePieces(BLACK, PAWN, Long.bitCount(board.getBlackPawns()));
        accumulatePieces(BLACK, KNIGHT, Long.bitCount(board.getBlackKnights()));
        accumulatePieces(BLACK, BISHOP, Long.bitCount(board.getBlackBishops()));
        accumulatePieces(BLACK, ROOK, Long.bitCount(board.getBlackRooks()));
        accumulatePieces(BLACK, QUEEN, Long.bitCount(board.getBlackQueens()));

        updateScoreCaches();
        dirty = false;
    }

    private void accumulatePieces(int colorIndex, int pieceType, int count) {
        if (count <= 0 || pieceType == 0 || pieceType == KING) {
            return;
        }
        midgameMaterial[colorIndex] += count * MIDGAME_VALUES[pieceType];
        endgameMaterial[colorIndex] += count * ENDGAME_VALUES[pieceType];
        if (pieceType == BISHOP) {
            int previous = bishopCounts[colorIndex];
            bishopCounts[colorIndex] = previous + count;
            if (previous < 2 && bishopCounts[colorIndex] >= 2) {
                midgameMaterial[colorIndex] += BISHOP_PAIR_BONUS;
                endgameMaterial[colorIndex] += BISHOP_PAIR_BONUS;
            }
        }
    }

    private void addPiece(int colorIndex, int pieceType) {
        if (pieceType == 0 || pieceType == KING) {
            return;
        }
        midgameMaterial[colorIndex] += MIDGAME_VALUES[pieceType];
        endgameMaterial[colorIndex] += ENDGAME_VALUES[pieceType];
        if (pieceType == BISHOP) {
            int previous = bishopCounts[colorIndex];
            bishopCounts[colorIndex] = previous + 1;
            if (previous == 1) {
                midgameMaterial[colorIndex] += BISHOP_PAIR_BONUS;
                endgameMaterial[colorIndex] += BISHOP_PAIR_BONUS;
            }
        }
    }

    private void removePiece(int colorIndex, int pieceType) {
        if (pieceType == 0 || pieceType == KING) {
            return;
        }
        midgameMaterial[colorIndex] -= MIDGAME_VALUES[pieceType];
        endgameMaterial[colorIndex] -= ENDGAME_VALUES[pieceType];
        if (pieceType == BISHOP) {
            int previous = bishopCounts[colorIndex];
            bishopCounts[colorIndex] = previous - 1;
            if (previous == 2) {
                midgameMaterial[colorIndex] -= BISHOP_PAIR_BONUS;
                endgameMaterial[colorIndex] -= BISHOP_PAIR_BONUS;
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
