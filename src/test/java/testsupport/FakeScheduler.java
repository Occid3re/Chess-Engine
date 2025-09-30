package testsupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simple {@link ScheduledExecutorService} that never spawns threads. Tests manually advance time by calling
 * {@link #tick()} or {@link #tick(int)} which synchronously execute scheduled tasks.
 */
public final class FakeScheduler extends AbstractExecutorService implements ScheduledExecutorService {

    private static final Logger log = LogManager.getLogger(FakeScheduler.class);

    private final List<FakeScheduledFuture<?>> tasks = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean shutdown = new AtomicBoolean();

    @Override
    public void shutdown() {
        shutdown.set(true);
        tasks.clear();
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown();
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    @Override
    public boolean isTerminated() {
        return isShutdown();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return isShutdown();
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        if (isShutdown()) {
            return;
        }
        command.run();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException("Single-shot scheduling not implemented for tests");
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException("Single-shot scheduling not implemented for tests");
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        Objects.requireNonNull(command, "command");
        if (isShutdown()) {
            throw new IllegalStateException("Scheduler already shut down");
        }
        FakeScheduledFuture<?> future = new FakeScheduledFuture<>(command, period, unit);
        tasks.add(future);
        log.debug("Scheduled periodic task (delay={} {}, period={} {})", initialDelay, unit, period, unit);
        if (initialDelay <= 0) {
            future.runOnce();
        }
        return future;
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException("Fixed-delay scheduling not implemented for tests");
    }

    public void tick() {
        tick(1);
    }

    public void tick(int times) {
        if (times <= 0) {
            return;
        }
        List<FakeScheduledFuture<?>> snapshot;
        synchronized (tasks) {
            snapshot = new ArrayList<>(tasks);
        }
        for (int i = 0; i < times; i++) {
            for (FakeScheduledFuture<?> task : snapshot) {
                if (!task.isCancelled() && !isShutdown()) {
                    task.runOnce();
                }
            }
        }
    }

    private static final class FakeScheduledFuture<V> implements ScheduledFuture<V> {
        private final Runnable command;
        private final long periodNanos;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private FakeScheduledFuture(Runnable command, long period, TimeUnit unit) {
            this.command = command;
            this.periodNanos = unit.toNanos(period);
        }

        private void runOnce() {
            if (cancelled.get()) {
                return;
            }
            try {
                command.run();
            } catch (Throwable t) {
                log.error("FakeScheduler task threw", t);
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(periodNanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }
    }
}

