package julius.game.chessengine.ai;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects per-worker timing and job-count metrics for diagnostics. Each search
 * worker records its idle and active time, and the instrumentation can be
 * summarised (and reset) between searches.
 */
final class WorkerInstrumentation {

    private LongAdder[] idleNanos;
    private LongAdder[] activeNanos;
    private LongAdder[] jobCounters;

    WorkerInstrumentation(int threads) {
        ensureCapacityInternal(Math.max(1, threads));
    }

    synchronized void ensureCapacity(int threads) {
        ensureCapacityInternal(Math.max(1, threads));
    }

    void recordIdle(int workerIndex, long nanos) {
        if (idleNanos == null || workerIndex < 0 || workerIndex >= idleNanos.length || nanos <= 0L) {
            return;
        }
        idleNanos[workerIndex].add(nanos);
    }

    void recordActive(int workerIndex, long nanos) {
        if (activeNanos == null || workerIndex < 0 || workerIndex >= activeNanos.length || nanos <= 0L) {
            return;
        }
        activeNanos[workerIndex].add(nanos);
    }

    void incrementJobs(int workerIndex) {
        if (jobCounters == null || workerIndex < 0 || workerIndex >= jobCounters.length) {
            return;
        }
        jobCounters[workerIndex].increment();
    }

    synchronized String buildSummaryAndReset(int workers) {
        if (workers <= 0) {
            resetAll();
            return "";
        }
        ensureCapacityInternal(workers);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < workers; i++) {
            long active = activeNanos[i].sum();
            long idle = idleNanos[i].sum();
            long jobs = jobCounters[i].sum();
            long total = active + idle;
            double utilisation = total > 0 ? (active * 100.0) / total : 0.0;
            if (i > 0) {
                sb.append("; ");
            }
            sb.append("worker=").append(i)
                    .append(" jobs=").append(jobs)
                    .append(" activeMs=").append(TimeUnit.NANOSECONDS.toMillis(active))
                    .append(" idleMs=").append(TimeUnit.NANOSECONDS.toMillis(idle))
                    .append(" util=").append(String.format(Locale.ROOT, "%.1f%%", utilisation));
        }
        resetAll();
        return sb.toString();
    }

    private void resetAll() {
        if (idleNanos != null) {
            for (LongAdder adder : idleNanos) {
                adder.reset();
            }
        }
        if (activeNanos != null) {
            for (LongAdder adder : activeNanos) {
                adder.reset();
            }
        }
        if (jobCounters != null) {
            for (LongAdder adder : jobCounters) {
                adder.reset();
            }
        }
    }

    private void ensureCapacityInternal(int threads) {
        if (idleNanos != null && idleNanos.length >= threads) {
            return;
        }
        idleNanos = grow(idleNanos, threads);
        activeNanos = grow(activeNanos, threads);
        jobCounters = grow(jobCounters, threads);
    }

    private static LongAdder[] grow(LongAdder[] original, int newLength) {
        LongAdder[] expanded = new LongAdder[newLength];
        int existing = original != null ? original.length : 0;
        for (int i = 0; i < newLength; i++) {
            if (i < existing && original[i] != null) {
                expanded[i] = original[i];
            } else {
                expanded[i] = new LongAdder();
            }
        }
        return expanded;
    }
}
