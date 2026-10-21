package julius.game.chessengine.core.nnue;

import julius.game.chessengine.core.Position;

/** int32 accumulators updated by plain loops (C2 auto-vectorises them); the fallback without the Vector API. */
final class ScalarAccumulators extends Accumulators {

    private final int[][] white;
    private final int[][] black;
    /** Finny table accumulators [perspective][frame]. */
    private final int[][][] cache;

    ScalarAccumulators(Network net, int maxPly) {
        super(net, maxPly);
        white = new int[maxPly][net.hidden];
        black = new int[maxPly][net.hidden];
        cache = new int[2][net.frames()][];
        for (int[][] side : cache) {
            for (int f = 0; f < side.length; f++) side[f] = net.l1Bias.clone();
        }
    }

    @Override
    protected void cacheUpdate(int persp, int frame, int[] subs, int ns, int[] adds, int na) {
        int[] acc = cache[persp][frame];
        for (int i = 0; i < ns; i++) net.sub(acc, subs[i]);
        for (int i = 0; i < na; i++) net.add(acc, adds[i]);
    }

    @Override
    protected void cacheToPly(int persp, int frame, int ply) {
        System.arraycopy(cache[persp][frame], 0, persp == Position.WHITE ? white[ply] : black[ply], 0, net.hidden);
    }

    @Override
    protected void copyPly(int persp, int from, int to) {
        int[][] s = persp == Position.WHITE ? white : black;
        System.arraycopy(s[from], 0, s[to], 0, net.hidden);
    }

    @Override
    protected void updatePly(int persp, int from, int to, int sub1, int sub2, int add1, int add2) {
        int[][] s = persp == Position.WHITE ? white : black;
        net.update(s[from], s[to], sub1, sub2, add1, add2);
    }

    @Override
    protected void activationsPly(int ply, boolean whiteToMove, float[] out) {
        int[] us = whiteToMove ? white[ply] : black[ply];
        int[] them = whiteToMove ? black[ply] : white[ply];
        int h = us.length, q = net.qa;
        float inv = 1f / q;
        for (int i = 0; i < h; i++) {
            float a = Math.min(Math.max(us[i], 0), q) * inv;
            float b = Math.min(Math.max(them[i], 0), q) * inv;
            out[i] = a * a;
            out[h + i] = b * b;
        }
    }

    @Override
    protected int evaluatePly(int ply, boolean whiteToMove, int bucket) {
        return whiteToMove ? net.evaluate(white[ply], black[ply], bucket) : net.evaluate(black[ply], white[ply], bucket);
    }

    @Override
    protected int uncertaintyPly(int ply, boolean whiteToMove, int bucket) {
        return whiteToMove ? net.uncertainty(white[ply], black[ply], bucket)
                : net.uncertainty(black[ply], white[ply], bucket);
    }
}
