package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class HeuristicsLockStressTest {

    private static final String SEARCH_THREADS_PROP = "chessengine.searchThreads";
    private static final String LAZY_SMP_THREADS_PROP = "chessengine.lazySmpThreads";

    @BeforeEach
    void configureThreads() {
        System.setProperty(SEARCH_THREADS_PROP, "24");
        System.setProperty(LAZY_SMP_THREADS_PROP, "24");
    }

    @AfterEach
    void clearThreadProperties() {
        System.clearProperty(SEARCH_THREADS_PROP);
        System.clearProperty(LAZY_SMP_THREADS_PROP);
    }

    @Test
    void iterativeDeepeningKeepsHeuristicsLockContentionLow() throws Exception {
        Engine engine = new Engine();
        AI ai = new AI(engine);
        ai.setMaxDepth(4);

        Engine rootSnapshot = engine.createSimulation();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
        SearchTask task = new SearchTask(1L, engine.getBoardStateHash(), engine.whitesTurn(), deadline, 24, rootSnapshot);

        Method iterativeDeepening = AI.class.getDeclaredMethod(
                "iterativeDeepening", SearchTask.class, Engine.class, SplittableRandom.class);
        iterativeDeepening.setAccessible(true);

        Field threadSearchTaskField = AI.class.getDeclaredField("threadSearchTask");
        threadSearchTaskField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<SearchTask> threadSearchTask = (ThreadLocal<SearchTask>) threadSearchTaskField.get(ai);

        ExecutorService pool = Executors.newFixedThreadPool(24);
        CountDownLatch ready = new CountDownLatch(24);
        CountDownLatch start = new CountDownLatch(1);

        for (int workerIndex = 0; workerIndex < 24; workerIndex++) {
            final int index = workerIndex;
            pool.submit(() -> {
                Engine workerEngine = task.getRootSnapshot().createSimulation();
                SplittableRandom rng = new SplittableRandom(
                        task.getBoardHash() ^ (0x9E3779B97F4A7C15L * (index + 1L)));
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }

                threadSearchTask.set(task);
                try {
                    invokeIterativeDeepening(iterativeDeepening, ai, task, workerEngine, rng);
                } finally {
                    threadSearchTask.remove();
                    task.workerDone();
                }
            });
        }

        ready.await();
        start.countDown();

        task.awaitCompletion();
        task.requestStop();

        pool.shutdownNow();
        pool.awaitTermination(2, TimeUnit.SECONDS);

        AI.LockMetricsSnapshot metrics = ai.snapshotHeuristicsLockMetrics();
        Assertions.assertTrue(metrics.getWriteAcquisitions() > 0,
                "expected at least one write lock acquisition");
        Assertions.assertTrue(metrics.getReadAcquisitions() > 0,
                "expected at least one read lock acquisition");

        long maxWriteMillis = TimeUnit.NANOSECONDS.toMillis(metrics.getMaxWriteWaitNanos());
        long maxReadMillis = TimeUnit.NANOSECONDS.toMillis(metrics.getMaxReadWaitNanos());
        Assertions.assertTrue(maxWriteMillis < 100,
                "write lock wait exceeded threshold: " + maxWriteMillis + "ms");
        Assertions.assertTrue(maxReadMillis < 100,
                "read lock wait exceeded threshold: " + maxReadMillis + "ms");
    }

    private static void invokeIterativeDeepening(Method method,
                                                 AI ai,
                                                 SearchTask task,
                                                 Engine engine,
                                                 SplittableRandom rng) {
        try {
            method.invoke(ai, task, engine, rng);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to access iterativeDeepening", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("Unexpected exception", cause);
        }
    }
}
