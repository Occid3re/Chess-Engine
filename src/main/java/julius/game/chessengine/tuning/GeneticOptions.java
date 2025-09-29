package julius.game.chessengine.tuning;

/**
 * Configuration options that control the genetic optimisation loop.
 */
public record GeneticOptions(int generations,
                             int retainCount,
                             int populationSize,
                             int matchesPerPair,
                             double mutationStrength,
                             long moveTimeMillis,
                             int maxPlies,
                             int matchParallelism) {

    public GeneticOptions {
        if (generations < 1) generations = 1;
        if (retainCount < 1) retainCount = 1;
        if (populationSize < 2) populationSize = 2;
        if (matchesPerPair < 1) matchesPerPair = 1;
        if (mutationStrength < 0.0) mutationStrength = 0.0;
        if (moveTimeMillis <= 0L) moveTimeMillis = 50L;
        if (maxPlies <= 0) maxPlies = 512;
        if (matchParallelism < 1) {
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            matchParallelism = Math.max(1, availableProcessors);
        }
    }

    public static GeneticOptions defaults() {
        return new GeneticOptions(5, 4, 8, 2, 0.15, 50L, 512,
                Math.max(1, Runtime.getRuntime().availableProcessors()));
    }
}
