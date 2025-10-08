package julius.game.chessengine.tuning;

/**
 * Late-move reduction table parameters exposed through the tuning system. The snapshot can
 * generate the reduction lookup table for a given search footprint.
 */
public final class LmrParameters {

    private LmrParameters() {
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                Math.max(1, Tuning.searchLmrHistoryBuckets()),
                Tuning.searchLmrHistoryWeightSlope(),
                Tuning.searchLmrScaleDivisor(),
                Tuning.searchLmrDepthLogOffset(),
                Tuning.searchLmrMoveLogOffset()
        );
    }

    public record Snapshot(
            int historyBuckets,
            double historyWeightSlope,
            double scaleDivisor,
            double depthLogOffset,
            double moveLogOffset
    ) {

        public int[][][] buildReductionTable(int maxDepth, int maxMoves) {
            int buckets = Math.max(1, historyBuckets);
            int[][][] table = new int[maxDepth + 1][maxMoves][buckets];
            double[] normalized = normalizedBuckets(buckets);
            double safeScale = scaleDivisor <= 0.0 ? 1.0 : scaleDivisor;

            for (int depth = 0; depth <= maxDepth; depth++) {
                double logDepth = Math.log(Math.max(1e-9, depthLogOffset + depth));
                for (int moveIndex = 0; moveIndex < maxMoves; moveIndex++) {
                    double logMove = Math.log(Math.max(1e-9, moveLogOffset + moveIndex));
                    double base = logDepth * logMove;
                    for (int bucket = 0; bucket < buckets; bucket++) {
                        double weight = 1.0 - historyWeightSlope * normalized[bucket];
                        double scaled = base * weight / safeScale;
                        int reduction = (int) Math.floor(scaled);
                        int maxReduction = Math.max(0, depth - 1);
                        if (reduction < 0) {
                            reduction = 0;
                        }
                        if (reduction > maxReduction) {
                            reduction = maxReduction;
                        }
                        table[depth][moveIndex][bucket] = reduction;
                    }
                }
            }
            return table;
        }

        private static double[] normalizedBuckets(int count) {
            double[] normalized = new double[count];
            if (count == 1) {
                normalized[0] = 0.0;
                return normalized;
            }
            double denominator = count - 1.0;
            for (int i = 0; i < count; i++) {
                normalized[i] = i / denominator;
            }
            return normalized;
        }
    }
}
