package julius.game.chessengine.ai;

import java.util.concurrent.locks.StampedLock;

/**
 * Manages the global and thread-local {@link Heuristics} instances used during
 * search. Provides optimistic/read/write lock access to the shared state and
 * handles snapshot capture, merge, and decay operations.
 */
final class HeuristicsManager {

    private final Heuristics globalHeuristics;
    private final ThreadLocal<Heuristics> threadHeuristics;
    private final StampedLock heuristicsLock = new StampedLock();
    private final LockMetrics heuristicsLockMetrics = new LockMetrics();

    HeuristicsManager(int initialDepth) {
        this.globalHeuristics = new Heuristics(initialDepth);
        this.threadHeuristics = ThreadLocal.withInitial(() -> new Heuristics(initialDepth));
    }

    Heuristics getGlobal() {
        return globalHeuristics;
    }

    Heuristics getThreadLocal() {
        return threadHeuristics.get();
    }

    ThreadLocal<Heuristics> getThreadHeuristicsRef() {
        return threadHeuristics;
    }

    // ---- Lock helpers ----

    long acquireWriteLock() {
        long start = System.nanoTime();
        long stamp = heuristicsLock.writeLock();
        heuristicsLockMetrics.recordWriteAcquisition(System.nanoTime() - start);
        return stamp;
    }

    long acquireReadLock() {
        long start = System.nanoTime();
        long stamp = heuristicsLock.readLock();
        heuristicsLockMetrics.recordReadAcquisition(System.nanoTime() - start);
        return stamp;
    }

    void releaseWriteLock(long stamp) {
        heuristicsLock.unlockWrite(stamp);
    }

    void releaseReadLock(long stamp) {
        heuristicsLock.unlockRead(stamp);
    }

    // ---- Snapshot / merge ----

    Heuristics.Snapshot captureSnapshot(int requiredDepth) {
        long stamp = heuristicsLock.tryOptimisticRead();
        if (stamp != 0L) {
            Heuristics.Snapshot optimistic = globalHeuristics.snapshot(requiredDepth);
            if (heuristicsLock.validate(stamp)) {
                heuristicsLockMetrics.recordOptimisticSnapshot();
                return optimistic;
            }
        }

        long readStamp = acquireReadLock();
        try {
            heuristicsLockMetrics.recordOptimisticFallback();
            return globalHeuristics.snapshot(requiredDepth);
        } finally {
            releaseReadLock(readStamp);
        }
    }

    void mergeThreadHeuristics(Heuristics heuristics) {
        if (!heuristics.hasPendingUpdates()) {
            return;
        }
        long stamp = acquireWriteLock();
        try {
            heuristics.mergeInto(globalHeuristics);
        } finally {
            releaseWriteLock(stamp);
        }
    }

    void clearHistory() {
        long stamp = acquireWriteLock();
        try {
            globalHeuristics.clearHistory();
            globalHeuristics.clearCounter();
        } finally {
            releaseWriteLock(stamp);
        }
    }

    void ensureCapacity(int depth) {
        globalHeuristics.ensureCapacity(depth);
    }

    void decayHistory(int divisor) {
        globalHeuristics.decayHistory(divisor);
    }
}
