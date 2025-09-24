package julius.game.chessengine.engine.search.time;

import julius.game.chessengine.engine.search.config.SearchLimits;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Centralises all time-control responsibilities for a single search invocation.
 *
 * <p>The manager is initialised with the limits that apply to the next search and
 * exposes helpers to query elapsed time, per-iteration deadlines and stop flags.
 * It can be supplied with a custom time source which makes the behaviour fully
 * deterministic in tests.</p>
 */
public final class TimeManager {

    private static final int MAX_ASPIRATION_ATTEMPTS = 10;

    private final LongSupplier nanoSource;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private volatile SearchLimits limits = SearchLimits.unlimited();
    private volatile long searchStartTimeNanos = -1L;
    private volatile long softDeadlineNanos = Long.MAX_VALUE;
    private volatile long hardDeadlineNanos = Long.MAX_VALUE;

    public TimeManager() {
        this(System::nanoTime);
    }

    public TimeManager(LongSupplier nanoSource) {
        this.nanoSource = Objects.requireNonNull(nanoSource, "nanoSource");
    }

    /**
     * Begin a new search using the supplied limits.  The start time and absolute
     * deadlines are computed lazily from the provided time source which allows
     * tests to supply a deterministic clock.
     */
    public synchronized void beginSearch(SearchLimits limits) {
        this.limits = limits != null ? limits : SearchLimits.unlimited();
        this.searchStartTimeNanos = nanoSource.getAsLong();
        this.stopRequested.set(false);
        computeDeadlines();
    }

    private void computeDeadlines() {
        long start = searchStartTimeNanos;
        if (start < 0L) {
            softDeadlineNanos = Long.MAX_VALUE;
            hardDeadlineNanos = Long.MAX_VALUE;
            return;
        }

        SearchLimits active = this.limits;
        hardDeadlineNanos = active.hardDeadlineNanos(start);

        long relativeSoft = active.getSoftDeadlineNanos();
        if (relativeSoft <= 0L) {
            softDeadlineNanos = hardDeadlineNanos;
        } else if (relativeSoft == Long.MAX_VALUE) {
            softDeadlineNanos = Long.MAX_VALUE;
        } else {
            softDeadlineNanos = saturatingAdd(start, relativeSoft, Long.MAX_VALUE);
            if (softDeadlineNanos > hardDeadlineNanos) {
                softDeadlineNanos = hardDeadlineNanos;
            }
        }
    }

    private long saturatingAdd(long base, long delta, long maxFallback) {
        try {
            return Math.addExact(base, delta);
        } catch (ArithmeticException ex) {
            return maxFallback;
        }
    }

    public long getSearchStartTimeNanos() {
        return searchStartTimeNanos;
    }

    public long getSoftDeadlineNanos() {
        return softDeadlineNanos;
    }

    public long getHardDeadlineNanos() {
        return hardDeadlineNanos;
    }

    /**
     * Returns {@code true} if the current search should stop according to the
     * configured limits or because a stop was explicitly requested.
     */
    public boolean shouldStop(long nodesSoFar, int plyFromRoot) {
        if (stopRequested.get()) {
            return true;
        }

        SearchLimits active = this.limits;

        long nodesLimit = active.getNodesLimit();
        if (nodesLimit > 0 && nodesSoFar >= nodesLimit) {
            return true;
        }

        int fixedDepth = active.getFixedDepth();
        if (fixedDepth > 0 && plyFromRoot >= fixedDepth) {
            return true;
        }

        long hard = hardDeadlineNanos;
        if (hard != Long.MAX_VALUE) {
            long now = nanoSource.getAsLong();
            if (now >= hard) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns the deadline (absolute nanoseconds) for the given aspiration
     * re-search attempt within the current iterative-deepening iteration.
     *
     * <p>The first attempt uses the soft deadline so that a failing aspiration
     * window can be retried while keeping enough slack to honour the overall
     * hard deadline.  Subsequent attempts approach the hard deadline using a
     * geometric distribution which keeps the progression deterministic while
     * reserving diminishing slices of the remaining slack.</p>
     */
    public long deadlineForAspirationAttempt(int attemptIndex) {
        if (attemptIndex <= 0) {
            return softDeadlineNanos;
        }

        long hard = hardDeadlineNanos;
        long soft = softDeadlineNanos;
        if (hard == Long.MAX_VALUE || soft == Long.MAX_VALUE) {
            return hard;
        }

        if (soft >= hard) {
            return hard;
        }

        if (attemptIndex >= MAX_ASPIRATION_ATTEMPTS) {
            return hard;
        }

        long slack = hard - soft;
        int shift = attemptIndex;
        long remainder = slack >>> shift; // geometric decay: 1/2^attempt
        long delta = slack - remainder;
        return Math.min(hard, saturatingAdd(soft, delta, hard));
    }

    /**
     * @return elapsed time in milliseconds since {@link #beginSearch(SearchLimits)}
     * was invoked. Returns {@code 0} if the search has not started yet or the
     * elapsed time would be negative due to the supplied clock.
     */
    public long getSearchElapsedMillis() {
        long start = searchStartTimeNanos;
        if (start < 0L) {
            return 0L;
        }
        long now = nanoSource.getAsLong();
        if (now <= start) {
            return 0L;
        }
        long elapsed = now - start;
        if (elapsed <= 0L) {
            return 0L;
        }
        return TimeUnit.NANOSECONDS.toMillis(elapsed);
    }

    public void requestStop() {
        stopRequested.set(true);
    }

    public AtomicBoolean stopSignal() {
        return stopRequested;
    }

    public synchronized void reset() {
        stopRequested.set(false);
        limits = SearchLimits.unlimited();
        searchStartTimeNanos = -1L;
        softDeadlineNanos = Long.MAX_VALUE;
        hardDeadlineNanos = Long.MAX_VALUE;
    }
}

