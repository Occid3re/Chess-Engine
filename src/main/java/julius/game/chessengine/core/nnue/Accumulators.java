package julius.game.chessengine.core.nnue;

import julius.game.chessengine.core.Move;
import julius.game.chessengine.core.Position;

import static julius.game.chessengine.core.Position.*;

/**
 * Per-ply accumulator stack for a search thread, updated lazily: {@link #push} only records which features
 * the move changes, and the accumulators are brought up to date when a position is evaluated. Nodes that never
 * evaluate (TT cutoffs, draws, nodes in check) cost no accumulator work at all.
 * {@link #push} must be called before the move is made on the position (it needs the pre-move board),
 * {@link #pushNull} before a null move.
 */
public final class Accumulators {

    private final Network net;
    private final int[][] white;
    private final int[][] black;
    /** Whether the accumulators of a ply are up to date. */
    private final boolean[] computed;
    /**
     * Feature changes leading to a ply: [white sub1, sub2, add1, add2, black sub1, sub2, add1, add2], -1 = none;
     * all -1 for a null move (the accumulators are copied unchanged).
     */
    private final int[][] delta;

    public Accumulators(Network net, int maxPly) {
        this.net = net;
        white = new int[maxPly][net.hidden];
        black = new int[maxPly][net.hidden];
        computed = new boolean[maxPly];
        delta = new int[maxPly][8];
    }

    public void refresh(Position p, int ply) {
        net.refresh(p, white[ply], black[ply]);
        computed[ply] = true;
    }

    public void pushNull(int ply) {
        java.util.Arrays.fill(delta[ply + 1], -1);
        computed[ply + 1] = false;
    }

    public void push(Position p, int m, int ply) {
        int from = Move.from(m), to = Move.to(m), flag = Move.flag(m);
        int pc = p.board[from];
        int us = color(pc);
        int subPc2 = NO_PIECE, subSq2 = -1, addPc2 = NO_PIECE, addSq2 = -1;
        int addPc1 = pc;
        if ((flag & Move.PROMO_N) != 0) addPc1 = piece(us, KNIGHT + (flag & 3));
        if (flag == Move.EP_CAPTURE) {
            subSq2 = to ^ 8;
            subPc2 = p.board[subSq2];
        } else if ((flag & Move.CAPTURE) != 0) {
            subSq2 = to;
            subPc2 = p.board[to];
        } else if (flag == Move.KING_CASTLE || flag == Move.QUEEN_CASTLE) {
            int rookFrom = flag == Move.KING_CASTLE ? to + 1 : to - 2;
            int rookTo = flag == Move.KING_CASTLE ? to - 1 : to + 1;
            subSq2 = rookFrom;
            subPc2 = p.board[rookFrom];
            addSq2 = rookTo;
            addPc2 = subPc2;
        }
        int[] d = delta[ply + 1];
        for (int persp = 0; persp < 2; persp++) {
            int o = persp * 4;
            d[o] = Network.featureIndex(persp, pc, from);
            d[o + 1] = subPc2 == NO_PIECE ? -1 : Network.featureIndex(persp, subPc2, subSq2);
            d[o + 2] = Network.featureIndex(persp, addPc1, to);
            d[o + 3] = addPc2 == NO_PIECE ? -1 : Network.featureIndex(persp, addPc2, addSq2);
        }
        computed[ply + 1] = false;
    }

    /** Brings the accumulators of ply up to date from the nearest computed ancestor. */
    private void ensure(int ply) {
        if (computed[ply]) return;
        int k = ply - 1;
        while (!computed[k]) k--;
        for (int j = k + 1; j <= ply; j++) {
            int[] d = delta[j];
            if (d[0] < 0) {
                System.arraycopy(white[j - 1], 0, white[j], 0, net.hidden);
                System.arraycopy(black[j - 1], 0, black[j], 0, net.hidden);
            } else {
                net.update(white[j - 1], white[j], d[0], d[1], d[2], d[3]);
                net.update(black[j - 1], black[j], d[4], d[5], d[6], d[7]);
            }
            computed[j] = true;
        }
    }

    /** Evaluation in centipawns from the side to move's perspective. */
    public int evaluate(Position p, int ply) {
        ensure(ply);
        int bucket = Network.bucketFor(Long.bitCount(p.all), net.buckets);
        return p.side == WHITE ? net.evaluate(white[ply], black[ply], bucket) : net.evaluate(black[ply], white[ply], bucket);
    }
}
