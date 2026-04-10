package julius.game.chessengine.evaluation.nn;

import julius.game.chessengine.board.ImmutableBoardView;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;

/**
 * Ply-indexed NNUE accumulator with lazy incremental updates.
 *
 * <p>Design: each ply has its own accumulator slot. On applyMove, we just
 * record the move and increment the ply. The new ply is marked "dirty".
 * On evaluate(), if the current ply is dirty, we copy from the nearest
 * clean ancestor ply and replay the deltas forward. On undoMove, we just
 * decrement the ply — zero cost.
 *
 * <p>Why this is fast: in alpha-beta with heavy pruning, most nodes never
 * call evaluate(). Only leaf nodes and nodes checking static eval do.
 * For those, the parent is typically clean (was itself evaluated or is the
 * root), so the replay is usually just 1 ply — one arraycopy + 2-4 feature
 * ops = ~1500 operations. Pruned nodes pay ZERO accumulator cost.
 *
 * <p>Comparison:
 * <ul>
 *   <li>Copy-on-make: ~1KB arraycopy on EVERY make/unmake = expensive</li>
 *   <li>Full rebuild per evaluate: ~4K ops per eval = expensive</li>
 *   <li>Lazy incremental (this): ~1.5K ops per eval, 0 on pruned nodes</li>
 * </ul>
 */
public final class NNUEAccumulator {

    private static final int SIZE = NNUEFeatures.ACCUMULATOR_SIZE;
    private static final int WHITE = 0;
    private static final int BLACK = 1;
    private static final int MAX_PLY = 128;

    // Per-ply accumulator state
    private final short[][][] accum;      // [ply][perspective][neuron]
    private final int[][] kingSq;         // [ply][perspective]
    private final boolean[] computed;     // [ply] — true if this ply's accumulator is valid
    private final int[] moves;            // [ply] — move that transitions ply-1 → ply
    private final boolean[] moveIsWhite;  // [ply] — which side made the move
    private int ply = 0;

    public NNUEAccumulator() {
        accum = new short[MAX_PLY][2][SIZE];
        kingSq = new int[MAX_PLY][2];
        computed = new boolean[MAX_PLY];
        moves = new int[MAX_PLY];
        moveIsWhite = new boolean[MAX_PLY];
    }

    /**
     * Full rebuild at current ply from board state. Called on initialization.
     */
    public void rebuildFromScratch(ImmutableBoardView board, NNUENetwork net) {
        int wk = Long.numberOfTrailingZeros(board.getWhiteKing());
        int bk = Long.numberOfTrailingZeros(board.getBlackKing());
        kingSq[ply][WHITE] = wk;
        kingSq[ply][BLACK] = bk;

        short[] biases = net.getL1Biases();
        System.arraycopy(biases, 0, accum[ply][WHITE], 0, SIZE);
        System.arraycopy(biases, 0, accum[ply][BLACK], 0, SIZE);

        // Add all non-king pieces
        short[] l1w = net.getL1Weights();
        long allPieces = (board.getWhitePieces() | board.getBlackPieces())
                & ~board.getWhiteKing() & ~board.getBlackKing();
        PieceType[] pieceBoard = board.getPieceBoard();

        while (allPieces != 0) {
            int sq = Long.numberOfTrailingZeros(allPieces);
            allPieces &= allPieces - 1;
            PieceType pt = pieceBoard[sq];
            if (pt == null || pt == PieceType.KING) continue;
            int ptBits = MoveHelper.pieceTypeToInt(pt);
            boolean isWhite = (board.getWhitePieces() & (1L << sq)) != 0;

            int wIdx = NNUEFeatures.featureIndex(wk, sq, ptBits, isWhite, true);
            if (wIdx >= 0) addFeatureInPlace(accum[ply][WHITE], l1w, wIdx);

            int bIdx = NNUEFeatures.featureIndex(bk, sq, ptBits, isWhite, false);
            if (bIdx >= 0) addFeatureInPlace(accum[ply][BLACK], l1w, bIdx);
        }

        computed[ply] = true;
    }

    /**
     * Record a move. Increments ply, marks new ply dirty. Zero-cost.
     */
    public void applyMove(int move, boolean whiteMoving) {
        if (ply + 1 >= MAX_PLY) return;
        ply++;
        moves[ply] = move;
        moveIsWhite[ply] = whiteMoving;
        computed[ply] = false;
    }

    /**
     * Undo last move. Zero-cost — just decrement ply.
     */
    public void undoMove() {
        if (ply > 0) ply--;
    }

    /**
     * Ensure the accumulator at the current ply is computed.
     * Walks back to find the nearest clean ancestor, copies it, and replays deltas.
     */
    public void ensureComputed(ImmutableBoardView board, NNUENetwork net) {
        if (computed[ply]) return;

        // Find nearest computed ancestor
        int ancestor = ply - 1;
        while (ancestor >= 0 && !computed[ancestor]) {
            ancestor--;
        }

        if (ancestor < 0) {
            // No clean ancestor — full rebuild
            rebuildFromScratch(board, net);
            return;
        }

        // Copy ancestor accumulator to current ply
        System.arraycopy(accum[ancestor][WHITE], 0, accum[ply][WHITE], 0, SIZE);
        System.arraycopy(accum[ancestor][BLACK], 0, accum[ply][BLACK], 0, SIZE);
        kingSq[ply][WHITE] = kingSq[ancestor][WHITE];
        kingSq[ply][BLACK] = kingSq[ancestor][BLACK];

        // Replay moves from ancestor+1 to ply
        short[] l1w = net.getL1Weights();
        for (int p = ancestor + 1; p <= ply; p++) {
            int move = moves[p];
            boolean isWhiteMove = moveIsWhite[p];
            applyMoveDelta(p, move, isWhiteMove, l1w, net);
        }

        computed[ply] = true;
    }

    /**
     * Apply a single move's delta to the accumulator at the given ply.
     */
    private void applyMoveDelta(int p, int move, boolean isWhiteMove, short[] l1w, NNUENetwork net) {
        int from = MoveHelper.deriveFromIndex(move);
        int to = MoveHelper.deriveToIndex(move);
        int movedPiece = MoveHelper.derivePieceTypeBits(move);
        int capturedPiece = MoveHelper.deriveCapturedPieceTypeBits(move);
        int promoPiece = MoveHelper.derivePromotionPieceTypeBits(move);
        boolean isCastling = MoveHelper.isCastlingMove(move);

        // King moves require full rebuild for that perspective
        if (movedPiece == 6) {
            kingSq[p][isWhiteMove ? WHITE : BLACK] = to;
            // We can't incrementally update king moves — mark for rebuild
            // For now, do a lightweight perspective rebuild using the OTHER ply's data
            // Actually, just mark this ply as needing full rebuild
            computed[p] = false;
            return;
        }

        int effectivePiece = (promoPiece > 0) ? promoPiece : movedPiece;
        int wk = kingSq[p][WHITE];
        int bk = kingSq[p][BLACK];

        for (int persp = 0; persp < 2; persp++) {
            boolean perspWhite = (persp == WHITE);
            int ks = perspWhite ? wk : bk;
            short[] acc = accum[p][persp];

            // Remove piece from old square
            int oldIdx = NNUEFeatures.featureIndex(ks, from, movedPiece, isWhiteMove, perspWhite);
            if (oldIdx >= 0) subFeatureInPlace(acc, l1w, oldIdx);

            // Add piece to new square (with promotion)
            int newIdx = NNUEFeatures.featureIndex(ks, to, effectivePiece, isWhiteMove, perspWhite);
            if (newIdx >= 0) addFeatureInPlace(acc, l1w, newIdx);

            // Capture
            if (capturedPiece > 0 && capturedPiece < 6) {
                int capSq = to;
                // En passant: captured pawn on different square
                if (movedPiece == 1 && capturedPiece == 1 && (from & 7) != (to & 7)) {
                    capSq = isWhiteMove ? (to - 8) : (to + 8);
                }
                int capIdx = NNUEFeatures.featureIndex(ks, capSq, capturedPiece, !isWhiteMove, perspWhite);
                if (capIdx >= 0) subFeatureInPlace(acc, l1w, capIdx);
            }

            // Castling rook
            if (isCastling) {
                boolean kingside = to > from;
                int rookFrom = isWhiteMove ? (kingside ? 7 : 0) : (kingside ? 63 : 56);
                int rookTo = kingside ? (rookFrom - 2) : (rookFrom + 3);
                int rookOld = NNUEFeatures.featureIndex(ks, rookFrom, 4, isWhiteMove, perspWhite);
                if (rookOld >= 0) subFeatureInPlace(acc, l1w, rookOld);
                int rookNew = NNUEFeatures.featureIndex(ks, rookTo, 4, isWhiteMove, perspWhite);
                if (rookNew >= 0) addFeatureInPlace(acc, l1w, rookNew);
            }
        }
    }

    /**
     * Get clipped accumulator output for the forward pass.
     */
    public void getClippedOutput(boolean whiteToMove, short[] output) {
        short[] stm = whiteToMove ? accum[ply][WHITE] : accum[ply][BLACK];
        short[] other = whiteToMove ? accum[ply][BLACK] : accum[ply][WHITE];
        for (int i = 0; i < SIZE; i++) {
            output[i] = (short) Math.max(0, Math.min(127, stm[i]));
        }
        for (int i = 0; i < SIZE; i++) {
            output[SIZE + i] = (short) Math.max(0, Math.min(127, other[i]));
        }
    }

    public boolean isComputed() {
        return computed[ply];
    }

    public boolean needsRefresh() {
        return !computed[ply];
    }

    public void markNeedsRefresh() {
        computed[ply] = false;
    }

    public void resetPly() {
        ply = 0;
        computed[0] = false;
    }

    // ── Inline feature add/sub (zero allocation) ──

    private static void addFeatureInPlace(short[] acc, short[] l1w, int featureIndex) {
        int offset = featureIndex * SIZE;
        for (int i = 0; i < SIZE; i++) {
            acc[i] += l1w[offset + i];
        }
    }

    private static void subFeatureInPlace(short[] acc, short[] l1w, int featureIndex) {
        int offset = featureIndex * SIZE;
        for (int i = 0; i < SIZE; i++) {
            acc[i] -= l1w[offset + i];
        }
    }
}
