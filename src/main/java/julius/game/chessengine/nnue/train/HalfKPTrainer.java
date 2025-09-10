package julius.game.chessengine.nnue.train;

import julius.game.chessengine.ai.nnue.HalfKPFeature;

import java.nio.file.Path;

/**
 * Minimal placeholder trainer that exports zeroed weights. This satisfies the
 * command-line interface required for training without implementing the full
 * optimisation pipeline.
 */
public final class HalfKPTrainer {

    private HalfKPTrainer() {}

    public static void main(String[] args) throws Exception {
        Path data = null;
        int hidden = 192;
        Path out = Path.of("build/nnue/current.nnuev1");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data" -> data = Path.of(args[++i]);
                case "--hidden" -> hidden = Integer.parseInt(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> {
                }
            }
        }
        // Allocate zero weights
        int F = HalfKPFeature.NUM_FEATURES;
        float[] hb = new float[hidden];
        float[] hw = new float[F * hidden];
        float[] ow = new float[hidden];
        float ob = 0f;
        NnueExporter.export(F, hidden, hb, hw, ob, ow, out);
    }
}
