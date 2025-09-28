package julius.game.chessengine.tuning;

import java.util.Objects;
import java.util.Random;

/**
 * Immutable representation of the configurable parameters used by the search component. The
 * mutation helpers are intentionally conservative to keep generated offspring close to their
 * parents unless a large mutation strength is requested.
 */
public final class AiTuning {

    private final int searchThreads;
    private final int lazySmpThreads;
    private final int hashSizeMb;
    private final int maxDepth;
    private final long timeLimitMillis;
    private final boolean nullMovePruning;

    private AiTuning(Builder builder) {
        this.searchThreads = builder.searchThreads;
        this.lazySmpThreads = builder.lazySmpThreads;
        this.hashSizeMb = builder.hashSizeMb;
        this.maxDepth = builder.maxDepth;
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
        private int searchThreads = Integer.getInteger("chessengine.searchThreads", 1);
        private int lazySmpThreads = Math.max(1, Integer.getInteger("chessengine.lazySmpThreads", 1));
        private int hashSizeMb = 16;
        private int maxDepth = 32;
        private long timeLimitMillis = 50L;
        private boolean nullMovePruning = Boolean.parseBoolean(System.getProperty("chessengine.nullMove", "true"));

        private Builder() {
        }

        private Builder(AiTuning source) {
            this.searchThreads = source.searchThreads;
            this.lazySmpThreads = source.lazySmpThreads;
            this.hashSizeMb = source.hashSizeMb;
            this.maxDepth = source.maxDepth;
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
            this.hashSizeMb = Math.max(1, hashSizeMb);
            return this;
        }

        public Builder maxDepth(int maxDepth) {
            this.maxDepth = Math.max(4, maxDepth);
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
    }
}
