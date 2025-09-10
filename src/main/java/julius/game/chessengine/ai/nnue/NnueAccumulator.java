package julius.game.chessengine.ai.nnue;

import julius.game.chessengine.board.BitBoard;

import java.util.Arrays;
import java.util.List;

/**
 * Incremental accumulator for NNUE evaluations. The current implementation
 * falls back to full recomputation on each move for correctness.
 */
public final class NnueAccumulator {

    private int[] whiteBucket;
    private int[] blackBucket;

    public void init(BitBoard b, NnueWeights w) {
        whiteBucket = new int[w.H];
        blackBucket = new int[w.H];
        Arrays.fill(whiteBucket, 0);
        Arrays.fill(blackBucket, 0);
        for (int h = 0; h < w.H; h++) {
            whiteBucket[h] = w.hb[h];
            blackBucket[h] = w.hb[h];
        }
        List<Integer> feats = HalfKPFeature.featuresFor(b);
        int featuresPerSide = 64 * HalfKPFeature.NUM_PIECE_KINDS * 64;
        int H = w.H;
        for (int f : feats) {
            int side = f / featuresPerSide;
            int base = f * H;
            int[] bucket = side == 0 ? whiteBucket : blackBucket;
            for (int h = 0; h < H; h++) {
                bucket[h] += w.hw[base + h];
            }
        }
    }

    public void applyMove(BitBoard before, int move, BitBoard after, NnueWeights w) {
        // Fallback to full recomputation for simplicity and correctness
        init(after, w);
    }

    public int evalCp(NnueWeights w) {
        int H = w.H;
        int[] hidden = new int[H];
        for (int h = 0; h < H; h++) {
            hidden[h] = whiteBucket[h] + blackBucket[h];
            if (hidden[h] < 0) hidden[h] = 0;
        }
        long sum = w.ob;
        for (int h = 0; h < H; h++) {
            sum += (long) hidden[h] * w.ow[h];
        }
        if (sum > 30000) return 30000;
        if (sum < -30000) return -30000;
        return (int) sum;
    }
}
