package julius.game.chessengine.ai;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight tracking of heuristics-lock contention for diagnostic purposes.
 */
final class LockMetrics {

    private final AtomicLong maxReadWait = new AtomicLong();
    private final AtomicLong maxWriteWait = new AtomicLong();
    private final LongAdder readAcquisitions = new LongAdder();
    private final LongAdder writeAcquisitions = new LongAdder();
    private final LongAdder optimisticSnapshots = new LongAdder();
    private final LongAdder optimisticFallbacks = new LongAdder();

    void recordReadAcquisition(long waitNanos) {
        readAcquisitions.increment();
        updateMax(maxReadWait, waitNanos);
    }

    void recordWriteAcquisition(long waitNanos) {
        writeAcquisitions.increment();
        updateMax(maxWriteWait, waitNanos);
    }

    void recordOptimisticSnapshot() {
        optimisticSnapshots.increment();
    }

    void recordOptimisticFallback() {
        optimisticFallbacks.increment();
    }

    private static void updateMax(AtomicLong target, long value) {
        target.accumulateAndGet(value, Math::max);
    }
}
