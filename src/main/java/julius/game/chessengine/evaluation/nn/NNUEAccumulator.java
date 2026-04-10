package julius.game.chessengine.evaluation.nn;

import julius.game.chessengine.board.ImmutableBoardView;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;

import java.util.Arrays;

/**
 * Incrementally-updatable first-layer accumulator for NNUE evaluation.
 *
 * <p>Stores two perspectives (white/black), each a {@code short[ACCUMULATOR_SIZE]}
 * vector representing the sum of L1 weight rows for all active HalfKP features.
 * On make/unmake, only the changed features are added/subtracted — typically 2-4
 * vector operations of {@link NNUEFeatures#ACCUMULATOR_SIZE} shorts.
 *
 * <p>Thread safety: each search thread should own its own accumulator stack
 * (managed by {@link NNUEModule} via ThreadLocal). The weight arrays in
 * {@link NNUENetwork} are shared and read-only.
 */
public final class NNUEAccumulator {

    private static final int SIZE = NNUEFeatures.ACCUMULATOR_SIZE;
    private static final int WHITE = 0;
    private static final int BLACK = 1;

    /** Accumulator values: [perspective][neuron]. */
    private final short[][] values = new short[2][SIZE];

    /** King squares for each perspective (used to detect king-bucket changes). */
    private final int[] kingSq = new int[2];

    /** Whether each perspective needs a full rebuild. */
    private final boolean[] needsRefresh = {true, true};

    // ── Accumulator stack for copy-on-make ──

    /** Stack of saved accumulator states for unmake. */
    private final short[][][] stack;
    private final int[][] stackKingSq;
    private int stackDepth = 0;
    private static final int MAX_PLY = 128;

    public NNUEAccumulator() {
        stack = new short[MAX_PLY][2][SIZE];
        stackKingSq = new int[MAX_PLY][2];
    }

    /**
     * Full rebuild: scan all pieces and compute accumulator from scratch.
     * Called on initialization and when king moves to a new square.
     */
    public void rebuildFromScratch(ImmutableBoardView board, NNUENetwork net) {
        int wk = Long.numberOfTrailingZeros(board.getWhiteKing());
        int bk = Long.numberOfTrailingZeros(board.getBlackKing());
        kingSq[WHITE] = wk;
        kingSq[BLACK] = bk;

        // Start with biases
        short[] biases = net.getL1Biases();
        System.arraycopy(biases, 0, values[WHITE], 0, SIZE);
        System.arraycopy(biases, 0, values[BLACK], 0, SIZE);

        // Add features for all non-king pieces
        PieceType[] pieceBoard = board.getPieceBoard();
        long whitePieces = board.getWhitePieces() & ~board.getWhiteKing();
        long blackPieces = board.getBlackPieces() & ~board.getBlackKing();

        addPieces(whitePieces, pieceBoard, true, net);
        addPieces(blackPieces, pieceBoard, false, net);

        needsRefresh[WHITE] = false;
        needsRefresh[BLACK] = false;
    }

    private void addPieces(long pieces, PieceType[] pieceBoard, boolean isWhite, NNUENetwork net) {
        while (pieces != 0) {
            int sq = Long.numberOfTrailingZeros(pieces);
            pieces &= pieces - 1;
            PieceType pt = pieceBoard[sq];
            if (pt == null || pt == PieceType.KING) continue;
            int ptBits = MoveHelper.pieceTypeToInt(pt);

            // Add to white perspective
            int wIdx = NNUEFeatures.featureIndex(kingSq[WHITE], sq, ptBits, isWhite, true);
            if (wIdx >= 0) addFeature(WHITE, wIdx, net);

            // Add to black perspective
            int bIdx = NNUEFeatures.featureIndex(kingSq[BLACK], sq, ptBits, isWhite, false);
            if (bIdx >= 0) addFeature(BLACK, bIdx, net);
        }
    }

    /**
     * Push current state onto stack (copy-on-make).
     */
    public void push() {
        if (stackDepth >= MAX_PLY) return;
        System.arraycopy(values[WHITE], 0, stack[stackDepth][WHITE], 0, SIZE);
        System.arraycopy(values[BLACK], 0, stack[stackDepth][BLACK], 0, SIZE);
        stackKingSq[stackDepth][WHITE] = kingSq[WHITE];
        stackKingSq[stackDepth][BLACK] = kingSq[BLACK];
        stackDepth++;
    }

    /**
     * Pop state from stack (undo-make).
     */
    public void pop() {
        if (stackDepth <= 0) return;
        stackDepth--;
        System.arraycopy(stack[stackDepth][WHITE], 0, values[WHITE], 0, SIZE);
        System.arraycopy(stack[stackDepth][BLACK], 0, values[BLACK], 0, SIZE);
        kingSq[WHITE] = stackKingSq[stackDepth][WHITE];
        kingSq[BLACK] = stackKingSq[stackDepth][BLACK];
        needsRefresh[WHITE] = false;
        needsRefresh[BLACK] = false;
    }

    /**
     * Update accumulator for a move. Called after the move is made on the board.
     *
     * @param move        packed move integer
     * @param newBoard    board state AFTER the move
     * @param net         network (for L1 weight access)
     */
    public void applyMove(int move, ImmutableBoardView newBoard, NNUENetwork net) {
        push();

        int from = MoveHelper.deriveFromIndex(move);
        int to = MoveHelper.deriveToIndex(move);
        int movedPiece = MoveHelper.derivePieceTypeBits(move);
        int capturedPiece = MoveHelper.deriveCapturedPieceTypeBits(move);
        int promoPiece = MoveHelper.derivePromotionPieceTypeBits(move);
        boolean isWhiteMove = !newBoard.isWhitesTurn(); // side that MADE the move

        // Check if king moved — need full rebuild for that perspective
        if (movedPiece == 6) { // KING
            int newKingSq = to;
            int persp = isWhiteMove ? WHITE : BLACK;
            kingSq[persp] = isWhiteMove ? newKingSq : newKingSq;
            // Must rebuild this perspective from scratch
            rebuildPerspective(persp, newBoard, net);
            // Other perspective: just update the moved piece (but king isn't a feature, so nothing)
            // However captures still need handling for the other perspective
            if (capturedPiece > 0 && capturedPiece < 6) {
                int otherPersp = 1 - persp;
                int capIdx = NNUEFeatures.featureIndex(kingSq[otherPersp], to, capturedPiece, !isWhiteMove, otherPersp == WHITE);
                if (capIdx >= 0) removeFeature(otherPersp, capIdx, net);
            }
            return;
        }

        // Normal piece move: remove from old square, add to new square (both perspectives)
        int effectivePiece = (promoPiece > 0) ? promoPiece : movedPiece;

        for (int persp = 0; persp < 2; persp++) {
            boolean perspWhite = (persp == WHITE);

            // Remove piece from old square
            int oldIdx = NNUEFeatures.featureIndex(kingSq[persp], from, movedPiece, isWhiteMove, perspWhite);
            if (oldIdx >= 0) removeFeature(persp, oldIdx, net);

            // Add piece to new square (with possible promotion)
            int newIdx = NNUEFeatures.featureIndex(kingSq[persp], to, effectivePiece, isWhiteMove, perspWhite);
            if (newIdx >= 0) addFeature(persp, newIdx, net);

            // Handle capture
            if (capturedPiece > 0 && capturedPiece < 6) {
                int capIdx = NNUEFeatures.featureIndex(kingSq[persp], to, capturedPiece, !isWhiteMove, perspWhite);
                if (capIdx >= 0) removeFeature(persp, capIdx, net);
            }

            // Handle en passant: captured pawn is not on 'to' square
            if (movedPiece == 1 && capturedPiece == 1) { // pawn captures pawn
                // En passant: the captured pawn is on a different square
                int capSq = isWhiteMove ? (to - 8) : (to + 8);
                if (capSq != to && capSq >= 0 && capSq < 64) {
                    // The capture was already handled above at 'to', but for EP the pawn
                    // was actually on capSq. We need to remove from capSq instead.
                    // First undo the wrong removal at 'to':
                    int wrongCapIdx = NNUEFeatures.featureIndex(kingSq[persp], to, 1, !isWhiteMove, perspWhite);
                    if (wrongCapIdx >= 0) addFeature(persp, wrongCapIdx, net); // re-add wrong removal
                    // Then remove from correct square:
                    int epCapIdx = NNUEFeatures.featureIndex(kingSq[persp], capSq, 1, !isWhiteMove, perspWhite);
                    if (epCapIdx >= 0) removeFeature(persp, epCapIdx, net);
                }
            }

            // Handle castling: also move the rook
            if (movedPiece == 6) continue; // already handled above
            if (MoveHelper.isCastlingMove(move)) {
                // Rook moves as part of castling
                int rookFrom, rookTo;
                if (to > from) { // kingside
                    rookFrom = isWhiteMove ? 7 : 63;
                    rookTo = isWhiteMove ? 5 : 61;
                } else { // queenside
                    rookFrom = isWhiteMove ? 0 : 56;
                    rookTo = isWhiteMove ? 3 : 59;
                }
                int rookOldIdx = NNUEFeatures.featureIndex(kingSq[persp], rookFrom, 4, isWhiteMove, perspWhite);
                if (rookOldIdx >= 0) removeFeature(persp, rookOldIdx, net);
                int rookNewIdx = NNUEFeatures.featureIndex(kingSq[persp], rookTo, 4, isWhiteMove, perspWhite);
                if (rookNewIdx >= 0) addFeature(persp, rookNewIdx, net);
            }
        }
    }

    /**
     * Undo the last move (pop from stack).
     */
    public void undoMove() {
        pop();
    }

    /**
     * Rebuild a single perspective from scratch.
     */
    private void rebuildPerspective(int persp, ImmutableBoardView board, NNUENetwork net) {
        boolean perspWhite = (persp == WHITE);
        int ks = perspWhite
                ? Long.numberOfTrailingZeros(board.getWhiteKing())
                : Long.numberOfTrailingZeros(board.getBlackKing());
        kingSq[persp] = ks;

        // Reset to biases
        System.arraycopy(net.getL1Biases(), 0, values[persp], 0, SIZE);

        // Add all non-king pieces for this perspective
        PieceType[] pieceBoard = board.getPieceBoard();
        long allPieces = (board.getWhitePieces() | board.getBlackPieces())
                & ~board.getWhiteKing() & ~board.getBlackKing();
        while (allPieces != 0) {
            int sq = Long.numberOfTrailingZeros(allPieces);
            allPieces &= allPieces - 1;
            PieceType pt = pieceBoard[sq];
            if (pt == null || pt == PieceType.KING) continue;
            int ptBits = MoveHelper.pieceTypeToInt(pt);

            // Determine piece color from bitboards
            boolean pieceIsWhite = (board.getWhitePieces() & (1L << sq)) != 0;
            int idx = NNUEFeatures.featureIndex(ks, sq, ptBits, pieceIsWhite, perspWhite);
            if (idx >= 0) addFeature(persp, idx, net);
        }
        needsRefresh[persp] = false;
    }

    // ── Low-level accumulator operations ──

    private void addFeature(int perspective, int featureIndex, NNUENetwork net) {
        short[] weights = net.getL1Weights();
        int offset = net.getL1Offset(featureIndex);
        short[] acc = values[perspective];
        for (int i = 0; i < SIZE; i++) {
            acc[i] += weights[offset + i];
        }
    }

    private void removeFeature(int perspective, int featureIndex, NNUENetwork net) {
        short[] weights = net.getL1Weights();
        int offset = net.getL1Offset(featureIndex);
        short[] acc = values[perspective];
        for (int i = 0; i < SIZE; i++) {
            acc[i] -= weights[offset + i];
        }
    }

    /**
     * Get the accumulator values for the forward pass.
     * Returns clipped ReLU output: max(0, min(127, x)).
     */
    public void getClippedOutput(boolean whiteToMove, short[] output) {
        // Concatenate: [side-to-move perspective, other perspective]
        short[] stm = whiteToMove ? values[WHITE] : values[BLACK];
        short[] other = whiteToMove ? values[BLACK] : values[WHITE];
        for (int i = 0; i < SIZE; i++) {
            output[i] = (short) Math.max(0, Math.min(127, stm[i]));
        }
        for (int i = 0; i < SIZE; i++) {
            output[SIZE + i] = (short) Math.max(0, Math.min(127, other[i]));
        }
    }

    public boolean needsRefresh() {
        return needsRefresh[WHITE] || needsRefresh[BLACK];
    }

    public void markNeedsRefresh() {
        needsRefresh[WHITE] = true;
        needsRefresh[BLACK] = true;
    }
}
