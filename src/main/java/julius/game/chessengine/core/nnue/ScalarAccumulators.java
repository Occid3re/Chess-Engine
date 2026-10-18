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
    protected void refreshPly(Position p, int ply) {
        net.refresh(p, white[ply], black[ply]);
    }

    @Override
    protected void copyPly(int from, int to) {
        System.arraycopy(white[from], 0, white[to], 0, net.hidden);
        System.arraycopy(black[from], 0, black[to], 0, net.hidden);
    }

    @Override
    protected void updatePly(int from, int to, int[] d) {
        net.update(white[from], white[to], d[0], d[1], d[2], d[3]);
        net.update(black[from], black[to], d[4], d[5], d[6], d[7]);
    }

    @Override
    protected int evaluatePly(int ply, boolean whiteToMove, int bucket) {
        return whiteToMove ? net.evaluate(white[ply], black[ply], bucket) : net.evaluate(black[ply], white[ply], bucket);
    }
}
