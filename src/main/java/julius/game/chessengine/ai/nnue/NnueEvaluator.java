package julius.game.chessengine.ai.nnue;

import julius.game.chessengine.board.BitBoard;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Performs forward evaluation using NNUE weights.
 */
public final class NnueEvaluator {

    public static final NnueEvaluator INSTANCE = new NnueEvaluator();

    private NnueWeights weights;

    public NnueEvaluator() {
    }

    public void loadFromResource(String path) throws IOException {
        try (InputStream is = NnueEvaluator.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found: " + path);
            }
            this.weights = NnueWeights.load(is);
        }
    }

    // Convenience for tests
    public void load(NnueWeights w) {
        this.weights = w;
    }

    public boolean isLoaded() {
        return weights != null;
    }

    public int evalCp(BitBoard b) {
        if (weights == null) {
            return 0;
        }
        int H = weights.H;
        int[] hidden = new int[H];
        for (int h = 0; h < H; h++) {
            hidden[h] = weights.hb[h];
        }
        List<Integer> feats = HalfKPFeature.featuresFor(b);
        for (int f : feats) {
            int base = f * H;
            for (int h = 0; h < H; h++) {
                hidden[h] += weights.hw[base + h];
            }
        }
        for (int h = 0; h < H; h++) {
            if (hidden[h] < 0) {
                hidden[h] = 0;
            }
        }
        long sum = weights.ob;
        for (int h = 0; h < H; h++) {
            sum += (long) hidden[h] * weights.ow[h];
        }
        if (sum > 30000) return 30000;
        if (sum < -30000) return -30000;
        return (int) sum;
    }
}
