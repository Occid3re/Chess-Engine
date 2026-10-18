package julius.game.chessengine.core.nnue;

import julius.game.chessengine.core.Position;

/** int32 accumulators updated by plain loops (C2 auto-vectorises them); the fallback without the Vector API. */
final class ScalarAccumulators extends Accumulators {

    private final int[][] white;
    private final int[][] black;

    ScalarAccumulators(Network net, int maxPly) {
        super(net, maxPly);
        white = new int[maxPly][net.hidden];
        black = new int[maxPly][net.hidden];
    }

    @Override
    protected void refreshPly(int persp, int ply, int[] features, int n) {
        int[] acc = persp == Position.WHITE ? white[ply] : black[ply];
        System.arraycopy(net.l1Bias, 0, acc, 0, net.hidden);
        for (int i = 0; i < n; i++) net.add(acc, features[i]);
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
    protected int evaluatePly(int ply, boolean whiteToMove, int bucket) {
        return whiteToMove ? net.evaluate(white[ply], black[ply], bucket) : net.evaluate(black[ply], white[ply], bucket);
    }
}
