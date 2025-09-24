package julius.game.chessengine.engine.search.config;

import java.util.concurrent.TimeUnit;

/**
 * Per-search limits that control when the search should stop.  The limits are immutable
 * so callers can safely share instances across threads.
 */
public final class SearchLimits {

    private final TimeControl timeControl;
    private final int fixedDepth;
    private final long nodesLimit;
    private final long moveTimeMillis;
    private final boolean ponder;
    private final long softDeadlineNanos;
    private final long hardDeadlineNanos;

    private SearchLimits(Builder builder) {
        this.timeControl = builder.timeControl;
        this.fixedDepth = builder.fixedDepth;
        this.nodesLimit = builder.nodesLimit;
        this.moveTimeMillis = builder.moveTimeMillis;
        this.ponder = builder.ponder;
        this.softDeadlineNanos = builder.softDeadlineNanos;
        this.hardDeadlineNanos = builder.hardDeadlineNanos;
    }

    public static SearchLimits unlimited() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public TimeControl getTimeControl() {
        return timeControl;
    }

    public int getFixedDepth() {
        return fixedDepth;
    }

    public long getNodesLimit() {
        return nodesLimit;
    }

    public long getMoveTimeMillis() {
        return moveTimeMillis;
    }

    public boolean isPonder() {
        return ponder;
    }

    public long getSoftDeadlineNanos() {
        return softDeadlineNanos;
    }

    public long getHardDeadlineNanos() {
        return hardDeadlineNanos;
    }

    public boolean hasTimeLimit() {
        if (moveTimeMillis > 0) {
            return true;
        }
        if (softDeadlineNanos > 0 || hardDeadlineNanos > 0) {
            return true;
        }
        if (timeControl == null) {
            return false;
        }
        return timeControl.whiteTimeMillis > 0
                || timeControl.blackTimeMillis > 0
                || timeControl.whiteIncrementMillis > 0
                || timeControl.blackIncrementMillis > 0;
    }

    public long hardDeadlineNanos(long startNanos) {
        long limit = hardDeadlineNanos > 0 ? hardDeadlineNanos : softDeadlineNanos;
        if (limit <= 0 && moveTimeMillis > 0) {
            limit = safeMultiply(moveTimeMillis, TimeUnit.MILLISECONDS.toNanos(1));
        }
        if (limit <= 0) {
            return Long.MAX_VALUE;
        }
        if (limit >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        try {
            return Math.addExact(startNanos, limit);
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private long safeMultiply(long value, long factor) {
        try {
            return Math.multiplyExact(value, factor);
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    public static final class Builder {
        private TimeControl timeControl;
        private int fixedDepth;
        private long nodesLimit;
        private long moveTimeMillis;
        private boolean ponder;
        private long softDeadlineNanos;
        private long hardDeadlineNanos;

        private Builder() {
        }

        public Builder timeControl(long wtime, long btime, long winc, long binc, int movesToGo) {
            this.timeControl = new TimeControl(wtime, btime, winc, binc, movesToGo);
            return this;
        }

        public Builder fixedDepth(int depth) {
            if (depth > 0) {
                this.fixedDepth = Math.max(1, depth);
            } else {
                this.fixedDepth = 0;
            }
            return this;
        }

        public Builder nodesLimit(long nodes) {
            if (nodes < 0) {
                this.nodesLimit = 0;
            } else {
                this.nodesLimit = nodes;
            }
            return this;
        }

        public Builder moveTimeMillis(long moveTimeMillis) {
            if (moveTimeMillis < 0) {
                this.moveTimeMillis = 0;
            } else {
                this.moveTimeMillis = moveTimeMillis;
            }
            return this;
        }

        public Builder ponder(boolean ponder) {
            this.ponder = ponder;
            return this;
        }

        public Builder softDeadlineNanos(long nanos) {
            this.softDeadlineNanos = clampNonNegative(nanos);
            return this;
        }

        public Builder hardDeadlineNanos(long nanos) {
            this.hardDeadlineNanos = clampNonNegative(nanos);
            return this;
        }

        public SearchLimits build() {
            if (hardDeadlineNanos > 0 && softDeadlineNanos > hardDeadlineNanos) {
                softDeadlineNanos = hardDeadlineNanos;
            }
            return new SearchLimits(this);
        }

        private long clampNonNegative(long value) {
            return Math.max(0L, value);
        }
    }

    public static final class TimeControl {
        private final long whiteTimeMillis;
        private final long blackTimeMillis;
        private final long whiteIncrementMillis;
        private final long blackIncrementMillis;
        private final int movesToGo;

        private TimeControl(long wtime, long btime, long winc, long binc, int movesToGo) {
            this.whiteTimeMillis = clampNonNegative(wtime);
            this.blackTimeMillis = clampNonNegative(btime);
            this.whiteIncrementMillis = clampNonNegative(winc);
            this.blackIncrementMillis = clampNonNegative(binc);
            this.movesToGo = Math.max(0, movesToGo);
        }

        public long whiteTimeMillis() {
            return whiteTimeMillis;
        }

        public long blackTimeMillis() {
            return blackTimeMillis;
        }

        public long whiteIncrementMillis() {
            return whiteIncrementMillis;
        }

        public long blackIncrementMillis() {
            return blackIncrementMillis;
        }

        public int movesToGo() {
            return movesToGo;
        }

        private long clampNonNegative(long value) {
            return Math.max(0L, value);
        }
    }
}
