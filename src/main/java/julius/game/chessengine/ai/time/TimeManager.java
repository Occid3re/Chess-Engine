package julius.game.chessengine.ai.time;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Computes per-move time budgets from the current clock information.
 * <p>
 * The manager maintains both a <em>soft</em> and a <em>hard</em> deadline so the
 * search can gracefully finish the current iteration while still respecting
 * the absolute time control. It also supports ponder searches: the initial
 * ponder search has no deadline and {@link #promotePonderHit()} converts it
 * into a regular move search when the GUI sends {@code ponderhit}.
 */
public final class TimeManager {

    private static final double SOFT_DEADLINE_RATIO = 0.85;

    /** Clock parameters for the next move to search. */
    public record Request(long timeLeftMillis,
                          long incrementMillis,
                          long moveTimeMillis,
                          int movesToGo,
                          int moveOverheadMillis,
                          boolean ponder) {

        public Request {
            timeLeftMillis = Math.max(0L, timeLeftMillis);
            incrementMillis = Math.max(0L, incrementMillis);
            moveTimeMillis = Math.max(0L, moveTimeMillis);
            movesToGo = Math.max(0, movesToGo);
            moveOverheadMillis = Math.max(0, moveOverheadMillis);
        }
    }

    /**
     * The concrete time allocation for the current search.
     * {@code hardDeadlineNanos} can be {@link Long#MAX_VALUE} for ponder searches.
     */
    public static final class TimeBudget {
        private final long allocationMillis;
        private final long softDeadlineNanos;
        private final long hardDeadlineNanos;
        private final boolean ponder;

        private TimeBudget(long allocationMillis, long softDeadlineNanos, long hardDeadlineNanos, boolean ponder) {
            this.allocationMillis = Math.max(1L, allocationMillis);
            this.softDeadlineNanos = softDeadlineNanos;
            this.hardDeadlineNanos = hardDeadlineNanos;
            this.ponder = ponder;
        }

        public long allocationMillis() {
            return allocationMillis;
        }

        public long softDeadlineNanos() {
            return softDeadlineNanos;
        }

        public long hardDeadlineNanos() {
            return hardDeadlineNanos;
        }

        public boolean isPonder() {
            return ponder;
        }
    }

    private final LongSupplier nanoSource;
    private long defaultPerMoveMillis;
    private Request pendingRequest;
    private boolean awaitingPonderHit;
    private long ponderSearchStartNanos;
    private TimeBudget activeBudget;

    public TimeManager(long defaultPerMoveMillis) {
        this(defaultPerMoveMillis, System::nanoTime);
    }

    TimeManager(long defaultPerMoveMillis, LongSupplier nanoSource) {
        this.defaultPerMoveMillis = Math.max(1L, defaultPerMoveMillis);
        this.nanoSource = Objects.requireNonNull(nanoSource, "nanoSource");
    }

    public synchronized void setDefaultPerMoveMillis(long millis) {
        this.defaultPerMoveMillis = Math.max(1L, millis);
    }

    public synchronized long getDefaultPerMoveMillis() {
        return defaultPerMoveMillis;
    }

    public synchronized void submit(Request request) {
        this.pendingRequest = Objects.requireNonNull(request, "request");
        this.awaitingPonderHit = request.ponder();
    }

    public synchronized TimeBudget beginSearch() {
        if (pendingRequest == null) {
            return activateBudget(createBudget(defaultPerMoveMillis, false));
        }

        Request request = pendingRequest;
        long now = nanoSource.getAsLong();
        if (request.ponder()) {
            ponderSearchStartNanos = now;
            TimeBudget ponderBudget = new TimeBudget(1L, Long.MAX_VALUE, Long.MAX_VALUE, true);
            activeBudget = ponderBudget;
            return ponderBudget;
        }

        pendingRequest = null;
        return activateBudget(createBudget(now, request));
    }

    public synchronized TimeBudget beginSearchWithOverride(long moveTimeMillis) {
        pendingRequest = null;
        long now = nanoSource.getAsLong();
        long allocation = Math.max(1L, moveTimeMillis);
        return activateBudget(createBudget(now, allocation, false));
    }

    public synchronized TimeBudget promotePonderHit() {
        if (!awaitingPonderHit || pendingRequest == null) {
            return activeBudget;
        }
        long now = nanoSource.getAsLong();
        long elapsedMillis = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(now - ponderSearchStartNanos));
        Request request = pendingRequest;
        long adjustedTimeLeft = request.timeLeftMillis() > 0
                ? Math.max(0L, request.timeLeftMillis() - elapsedMillis)
                : 0L;
        Request promoted = new Request(adjustedTimeLeft,
                request.incrementMillis(),
                request.moveTimeMillis(),
                request.movesToGo(),
                request.moveOverheadMillis(),
                false);
        pendingRequest = null;
        awaitingPonderHit = false;
        return activateBudget(createBudget(now, promoted));
    }

    public synchronized TimeBudget activeBudget() {
        return activeBudget;
    }

    private TimeBudget activateBudget(TimeBudget budget) {
        activeBudget = budget;
        if (!budget.isPonder()) {
            awaitingPonderHit = false;
            pendingRequest = null;
        }
        return budget;
    }

    private TimeBudget createBudget(long now, Request request) {
        long allocation = computeAllocationMillis(request);
        return createBudget(now, allocation, false);
    }

    private TimeBudget createBudget(long allocationMillis, boolean ponder) {
        long now = nanoSource.getAsLong();
        return createBudget(now, Math.max(1L, allocationMillis), ponder);
    }

    private TimeBudget createBudget(long now, long allocationMillis, boolean ponder) {
        if (ponder) {
            return new TimeBudget(Math.max(1L, allocationMillis), Long.MAX_VALUE, Long.MAX_VALUE, true);
        }
        long hardDeadline = now + TimeUnit.MILLISECONDS.toNanos(allocationMillis);
        long softMillis = Math.max(1L, Math.min(allocationMillis - 1L,
                Math.round(allocationMillis * SOFT_DEADLINE_RATIO)));
        if (softMillis >= allocationMillis) {
            softMillis = Math.max(1L, allocationMillis - 1L);
        }
        long softDeadline = now + TimeUnit.MILLISECONDS.toNanos(softMillis);
        return new TimeBudget(allocationMillis, softDeadline, hardDeadline, false);
    }

    static long computeAllocationMillis(Request request) {
        if (request.moveTimeMillis() > 0) {
            long adjusted = Math.max(1L, request.moveTimeMillis() - request.moveOverheadMillis());
            if (request.timeLeftMillis() > request.moveOverheadMillis()) {
                long cap = request.timeLeftMillis() - request.moveOverheadMillis();
                if (adjusted > cap) {
                    adjusted = cap;
                }
            }
            return adjusted;
        }

        long movesToGo = request.movesToGo() > 0
                ? request.movesToGo()
                : estimateMovesToGo(request.timeLeftMillis(), request.incrementMillis());
        if (movesToGo <= 0) {
            movesToGo = 1;
        }

        long share = request.timeLeftMillis() > 0 ? request.timeLeftMillis() / movesToGo : 0;
        long allocation = share + request.incrementMillis() - request.moveOverheadMillis();

        if (request.timeLeftMillis() > request.moveOverheadMillis()) {
            long cap = request.timeLeftMillis() - request.moveOverheadMillis();
            if (allocation > cap) {
                allocation = cap;
            }
        }
        return Math.max(1L, allocation);
    }

    public static long estimateMovesToGo(long timeLeft, long increment) {
        if (timeLeft <= 0) {
            return 1;
        }
        if (increment <= 0 && timeLeft <= 75_000L) {
            return 60;
        }
        if (timeLeft <= 300_000L) {
            return 40;
        }
        return 30;
    }
}
