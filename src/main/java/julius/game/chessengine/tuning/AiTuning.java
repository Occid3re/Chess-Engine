package julius.game.chessengine.tuning;

import julius.game.chessengine.ai.SearchConcurrencyPlanner;
import java.util.Objects;
import java.util.Random;

/**
 * Immutable representation of the configurable parameters used by the search component. The
 * mutation helpers are intentionally conservative to keep generated offspring close to their
 * parents unless a large mutation strength is requested.
 */
public final class AiTuning {

    public static final int MIN_HASH_SIZE_MB = 1;
    public static final int MAX_HASH_SIZE_MB = 4096;

    private final int searchThreads;
    private final int lazySmpThreads;
    private final int hashSizeMb;
    private final int maxDepth;
    private final int rootParallelLimit;
    private final long timeLimitMillis;
    private final boolean nullMovePruning;

    private AiTuning(Builder builder) {
        this.searchThreads = builder.searchThreads;
        this.lazySmpThreads = builder.lazySmpThreads;
        this.hashSizeMb = builder.hashSizeMb;
        this.maxDepth = builder.maxDepth;
        this.rootParallelLimit = builder.rootParallelLimit;
        this.timeLimitMillis = builder.timeLimitMillis;
        this.nullMovePruning = builder.nullMovePruning;
    }

    public static AiTuning defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public int searchThreads() {
        return searchThreads;
    }

    public int lazySmpThreads() {
        return lazySmpThreads;
    }

    public int hashSizeMb() {
        return hashSizeMb;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int rootParallelLimit() {
        return rootParallelLimit;
    }

    public long timeLimitMillis() {
        return timeLimitMillis;
    }

    public boolean nullMovePruning() {
        return nullMovePruning;
    }

    public AiTuning mutate(Random random, double strength) {
        Objects.requireNonNull(random, "random");
        return this;
    }

    public static final class Builder {
        private int searchThreads;
        private int lazySmpThreads;
        private int hashSizeMb;
        private int maxDepth = 32;
        private int rootParallelLimit;
        private long timeLimitMillis = 50L;
        private boolean nullMovePruning = Boolean.parseBoolean(System.getProperty("chessengine.nullMove", "true"));

        private Builder() {
            SearchConcurrencyPlanner.Plan plan = SearchConcurrencyPlanner.resolve();
            this.searchThreads = Math.max(1, plan.searchThreads());
            this.lazySmpThreads = Math.max(1, plan.lazySmpThreads());
            this.rootParallelLimit = Math.max(1, plan.rootParallelLimit());
            this.hashSizeMb = clampHashSize(plan.transpositionTableMb());
        }

        private Builder(AiTuning source) {
            this.searchThreads = source.searchThreads;
            this.lazySmpThreads = source.lazySmpThreads;
            this.hashSizeMb = clampHashSize(source.hashSizeMb);
            this.maxDepth = source.maxDepth;
            this.rootParallelLimit = source.rootParallelLimit;
            this.timeLimitMillis = source.timeLimitMillis;
            this.nullMovePruning = source.nullMovePruning;
        }

        public Builder searchThreads(int searchThreads) {
            this.searchThreads = Math.max(1, searchThreads);
            return this;
        }

        public Builder lazySmpThreads(int lazySmpThreads) {
            this.lazySmpThreads = Math.max(1, lazySmpThreads);
            return this;
        }

        public Builder hashSizeMb(int hashSizeMb) {
            this.hashSizeMb = clampHashSize(hashSizeMb);
            return this;
        }

        public Builder maxDepth(int maxDepth) {
            this.maxDepth = Math.max(4, maxDepth);
            return this;
        }

        public Builder rootParallelLimit(int rootParallelLimit) {
            this.rootParallelLimit = Math.max(1, rootParallelLimit);
            return this;
        }

        public Builder timeLimitMillis(long timeLimitMillis) {
            this.timeLimitMillis = Math.max(5L, timeLimitMillis);
            return this;
        }

        public Builder nullMovePruning(boolean nullMovePruning) {
            this.nullMovePruning = nullMovePruning;
            return this;
        }

        public AiTuning build() {
            return new AiTuning(this);
        }

        private static int clampHashSize(int value) {
            return Math.max(MIN_HASH_SIZE_MB, Math.min(MAX_HASH_SIZE_MB, value));
        }
    }
}
