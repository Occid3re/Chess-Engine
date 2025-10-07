package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.ai.time.TimeManager;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.AiTuning;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import testsupport.FakeScheduler;
import testsupport.TestLoggingExtension;
import testsupport.TestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.*;

@Log4j2
@ExtendWith(TestLoggingExtension.class)
class AITest_ConcurrencyAndStops {

    @Test
    @DisplayName("Stale best move is discarded without performing it")
    void staleBestMoveIsDropped() throws Exception {
        Engine engine = new Engine();
        AI ai = new AI(engine, AiTuning.defaults());

        IntArrayList moves = engine.getAllLegalMoves();
        int candidate = moves.getInt(0);

        long liveHash = engine.getBoardStateHash();
        TestUtils.writeField(ai, "currentBestMove", candidate);
        TestUtils.writeField(ai, "bestMoveForHash", liveHash ^ 0xFFL);
        TestUtils.writeField(ai, "searchResultReady", true);

        ai.performMove();

        assertEquals(0, engine.getLine().size(), "Stale move must not be executed");
        assertEquals(-1, TestUtils.readField(ai, "currentBestMove"));
        assertEquals(-1L, TestUtils.readField(ai, "bestMoveForHash"));
        assertEquals(Boolean.FALSE, TestUtils.readField(ai, "searchResultReady"));
    }

    @Test
    @Timeout(5)
    @DisplayName("stopCalculation interrupts workers and clears queues")
    void stopCalculationResetsState() throws Exception {
        Engine engine = new Engine();
        AI ai = new AI(engine, AiTuning.defaults());

        Method start = AI.class.getDeclaredMethod("startCalculationThread");
        start.setAccessible(true);
        start.invoke(ai);

        // Wait briefly for threads to start
        Thread.sleep(100);

        ai.stopCalculation();

        BlockingQueue<?> requests = (BlockingQueue<?>) TestUtils.readField(ai, "calculationRequests");
        BlockingQueue<?> jobs = (BlockingQueue<?>) TestUtils.readField(ai, "searchJobs");

        assertTrue(requests.isEmpty(), "Calculation request queue should be empty after stop");
        assertTrue(jobs.isEmpty(), "Search job queue should be empty after stop");
        assertEquals(-1, TestUtils.readField(ai, "currentBestMove"));
        assertNull(TestUtils.readField(ai, "calculationThreads"));
        assertNull(TestUtils.readField(ai, "calculationCoordinator"));
    }

    @Test
    @Timeout(15)
    @DisplayName("Updating Threads option rebuilds parallel infrastructure")
    void switchingThreadsReconfiguresExecutorAndTables() throws Exception {
        Engine engine = new Engine();
        AI ai = new AI(engine, AiTuning.defaults());
        ai.setMaxDepth(4);
        ai.setTimeLimit(300);

        Object initialMain = TestUtils.readField(ai, "transpositionTable");
        Object initialCapture = TestUtils.readField(ai, "captureTranspositionTable");

        assertTrue(initialMain instanceof PlainFixedSizeTranspositionTable,
                "Single-thread mode should use the plain transposition table");
        assertTrue(initialCapture instanceof PlainFixedSizeTranspositionTable,
                "Single-thread mode should use the plain capture table");
        assertNull(TestUtils.readField(ai, "searchPool"),
                "Single-thread mode must not allocate a search pool");

        try {
            ai.setSearchThreads(3);

            Object concurrentMain = TestUtils.readField(ai, "transpositionTable");
            Object concurrentCapture = TestUtils.readField(ai, "captureTranspositionTable");

            assertTrue(concurrentMain instanceof FixedSizeTranspositionTable,
                    "Multi-thread mode should use the concurrent main table");
            assertTrue(concurrentCapture instanceof FixedSizeTranspositionTable,
                    "Multi-thread mode should use the concurrent capture table");

            ExecutorService pool = (ExecutorService) TestUtils.readField(ai, "searchPool");
            assertNotNull(pool, "Multi-thread mode must allocate a search pool");
            assertFalse(pool.isShutdown(), "New search pool should be active");

            ThreadPoolExecutor executor = (ThreadPoolExecutor) pool;
            assertEquals(3, executor.getCorePoolSize(),
                    "Search pool should match configured thread count");

            long completedBefore = executor.getCompletedTaskCount();
            MoveAndScore result = ai.searchBestMoveBlocking(300);
            assertNotNull(result, "Search should produce a move when time remains");

            long completedAfter = executor.getCompletedTaskCount();
            assertTrue(completedAfter > completedBefore,
                    "Parallel search must submit work to the executor");
        } finally {
            ai.shutdown();
        }
    }

    @Test
    @DisplayName("Follower threads skip the heuristics write lock")
    void prepareIterationStateSkipsWriteLockForFollowers() throws Exception {
        Engine engine = new Engine();
        AI ai = new AI(engine, AiTuning.defaults());

        Engine sim = engine.createSimulation();
        SearchTask task = new SearchTask(
                42L,
                sim.getBoardStateHash(),
                sim.whitesTurn(),
                new TimeManager(1_000L).beginSearchWithOverride(TimeUnit.SECONDS.toMillis(1)),
                1,
                sim.createSimulation()
        );

        @SuppressWarnings("unchecked")
        ThreadLocal<Object> heuristicsLocal = (ThreadLocal<Object>) TestUtils.readField(ai, "threadHeuristics");
        Object heuristics = heuristicsLocal.get();

        Class<?> heuristicsClass = heuristics.getClass();
        Method prepare = AI.class.getDeclaredMethod("prepareIterationState", SearchTask.class, heuristicsClass, int.class, boolean.class);
        prepare.setAccessible(true);

        Object metrics = TestUtils.readField(ai, "heuristicsLockMetrics");
        Field writesField = metrics.getClass().getDeclaredField("writeAcquisitions");
        writesField.setAccessible(true);
        LongAdder writeAcquisitions = (LongAdder) writesField.get(metrics);

        long baseline = writeAcquisitions.longValue();

        prepare.invoke(ai, task, heuristics, 1, true);
        long afterLeader = writeAcquisitions.longValue();
        assertEquals(baseline + 1, afterLeader, "Leader should acquire the write lock once");

        prepare.invoke(ai, task, heuristics, 1, false);
        long afterFollower = writeAcquisitions.longValue();
        assertEquals(afterLeader, afterFollower, "Follower should not take the write lock again");

        ai.shutdown();
    }

    @Test
    @DisplayName("Lazy SMP leader stays deterministic while followers randomize")
    void lazySmpLeaderRemainsDeterministic() throws Exception {
        AiTuning tuning = AiTuning.builder()
                .searchThreads(1)
                .lazySmpThreads(3)
                .timeLimitMillis(200)
                .build();

        Engine engine = new Engine();
        AI ai = new AI(engine, tuning);

        Engine sim = engine.createSimulation();
        SearchTask task = new SearchTask(
                77L,
                sim.getBoardStateHash(),
                sim.whitesTurn(),
                new TimeManager(1_000L).beginSearchWithOverride(TimeUnit.SECONDS.toMillis(1)),
                tuning.lazySmpThreads(),
                sim.createSimulation()
        );

        Method factory = AI.class.getDeclaredMethod("createLazyWorkerRng", SearchTask.class, int.class);
        factory.setAccessible(true);

        SplittableRandom leader = (SplittableRandom) factory.invoke(ai, task, 0);
        assertNull(leader, "Leader thread should not randomize search parameters");

        long baseSeed = task.getBoardHash();
        SplittableRandom followerOne = (SplittableRandom) factory.invoke(ai, task, 1);
        assertNotNull(followerOne, "Follower threads should receive an RNG");
        SplittableRandom expectedOne = new SplittableRandom(baseSeed ^ (0x9E3779B97F4A7C15L * 2L));
        assertEquals(expectedOne.nextLong(), followerOne.nextLong(),
                "Follower one should use deterministic diversification seed");

        SplittableRandom followerTwo = (SplittableRandom) factory.invoke(ai, task, 2);
        assertNotNull(followerTwo, "Additional followers should also receive RNGs");
        SplittableRandom expectedTwo = new SplittableRandom(baseSeed ^ (0x9E3779B97F4A7C15L * 3L));
        assertEquals(expectedTwo.nextLong(), followerTwo.nextLong(),
                "Follower two should use a unique deterministic seed");

        AiTuning singleLazy = AiTuning.builder()
                .searchThreads(1)
                .lazySmpThreads(1)
                .timeLimitMillis(200)
                .build();
        Engine singleEngine = new Engine();
        AI single = new AI(singleEngine, singleLazy);
        Engine singleSim = singleEngine.createSimulation();
        SearchTask singleTask = new SearchTask(
                88L,
                singleSim.getBoardStateHash(),
                singleSim.whitesTurn(),
                new TimeManager(1_000L).beginSearchWithOverride(TimeUnit.SECONDS.toMillis(1)),
                singleLazy.lazySmpThreads(),
                singleSim.createSimulation()
        );
        SplittableRandom none = (SplittableRandom) factory.invoke(single, singleTask, 1);
        assertNull(none, "Single-threaded configuration should avoid randomization");

        single.shutdown();
        ai.shutdown();
    }

    @Test
    @DisplayName("Auto-play only executes moves for the configured side")
    void autoplayRespectsSideToMove() throws Exception {
        Engine engine = new Engine();
        FakeAutoAI ai = new FakeAutoAI(engine, AiTuning.defaults());

        IntArrayList moves = engine.getAllLegalMoves();
        int candidate = moves.getInt(0);
        long hash = engine.getBoardStateHash();

        TestUtils.writeField(ai, "currentBestMove", candidate);
        TestUtils.writeField(ai, "bestMoveForHash", hash);
        TestUtils.writeField(ai, "searchResultReady", true);

        ai.startAutoPlay(true, false);
        ai.scheduler.tick();

        assertEquals(1, engine.getLine().size(), "Auto-play should execute a move for matching side");
        assertEquals(candidate, engine.getLine().peek());

        // Reset candidate but mismatch side
        engine.undoLastMove();
        TestUtils.writeField(ai, "currentBestMove", candidate);
        TestUtils.writeField(ai, "bestMoveForHash", engine.getBoardStateHash());
        TestUtils.writeField(ai, "searchResultReady", true);

        ai.scheduler.tick();
        BlockingQueue<?> requests = (BlockingQueue<?>) TestUtils.readField(ai, "calculationRequests");
        assertFalse(requests.isEmpty(), "Auto-play should enqueue a new calculation request after executing a move");
    }

    @Test
    @Timeout(15)
    @DisplayName("Parallel root search keeps best move monotonically improving")
    void parallelRootSearchKeepsBestMoveStable() throws Exception {
        int searchDepth = 3;

        AiTuning singleThreadTuning = AiTuning.builder()
                .searchThreads(1)
                .lazySmpThreads(1)
                .maxDepth(6)
                .timeLimitMillis(500)
                .build();

        Engine singleEngine = new Engine();
        AI singleThreadAi = new AI(singleEngine, singleThreadTuning);

        Engine singleSim = singleEngine.createSimulation();
        SearchTask singleTask = new SearchTask(
                1L,
                singleSim.getBoardStateHash(),
                singleSim.whitesTurn(),
                new TimeManager(5_000L).beginSearchWithOverride(TimeUnit.SECONDS.toMillis(5)),
                1,
                singleSim.createSimulation()
        );

        @SuppressWarnings("unchecked")
        ThreadLocal<SearchTask> singleThreadLocal = (ThreadLocal<SearchTask>) TestUtils.readField(singleThreadAi, "threadSearchTask");
        SearchTask previousSingle = singleThreadLocal.get();
        MoveAndScore baselineBest;
        try {
            singleThreadLocal.set(singleTask);
            RootSearchResult baseline = singleThreadAi.searchRootMoves(
                    singleSim,
                    singleTask,
                    searchDepth,
                    Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                    null
            );
            assertTrue(baseline.hasCandidate(), "Baseline search should provide a best move");
            baselineBest = baseline.bestMove();
        } finally {
            if (previousSingle != null) {
                singleThreadLocal.set(previousSingle);
            } else {
                singleThreadLocal.remove();
            }
        }

        AiTuning parallelTuning = AiTuning.builder()
                .searchThreads(4)
                .lazySmpThreads(1)
                .maxDepth(6)
                .timeLimitMillis(500)
                .build();

        Engine parallelEngine = new Engine();
        AI parallelAi = new AI(parallelEngine, parallelTuning);

        BarrierExecutor barrierExecutor = new BarrierExecutor(parallelAi.getSearchThreads());
        TestUtils.writeField(parallelAi, "searchPool", barrierExecutor);

        Field rootLimitField = AI.class.getDeclaredField("ROOT_PARALLEL_LIMIT");
        rootLimitField.setAccessible(true);
        int rootLimit = rootLimitField.getInt(null);

        @SuppressWarnings("unchecked")
        ThreadLocal<SearchTask> parallelThreadLocal = (ThreadLocal<SearchTask>) TestUtils.readField(parallelAi, "threadSearchTask");

        try {
            for (int iteration = 0; iteration < 8; iteration++) {
                Engine sim = parallelEngine.createSimulation();
                SearchTask task = new SearchTask(
                        10_000L + iteration,
                        sim.getBoardStateHash(),
                        sim.whitesTurn(),
                        new TimeManager(5_000L).beginSearchWithOverride(TimeUnit.SECONDS.toMillis(5)),
                        parallelAi.getSearchThreads(),
                        sim.createSimulation()
                );

                SearchTask previous = parallelThreadLocal.get();
                parallelThreadLocal.set(task);
                try {
                    IntArrayList legal = sim.getAllLegalMoves();
                    IntArrayList ordered = parallelAi.sortMovesByEfficiency(legal, searchDepth, sim.getBoardStateHash(), -1, sim);
                    int fanout = Math.min(rootLimit, ordered.size() - 1);
                    int helperCapacity = parallelAi.getSearchThreads();
                    if (helperCapacity <= 0 && fanout > 0) {
                        fanout = 0;
                    } else if (helperCapacity > 0) {
                        fanout = Math.min(fanout, helperCapacity);
                    }
                    assertTrue(fanout >= 2 || helperCapacity <= 0,
                            "Test position should trigger parallel fan-out when capacity allows");
                    barrierExecutor.setParties(fanout);

                    RootSearchResult result = parallelAi.searchRootMoves(
                            sim,
                            task,
                            searchDepth,
                            Double.NEGATIVE_INFINITY,
                            Double.POSITIVE_INFINITY,
                            null
                    );

                    assertTrue(result.hasCandidate(), "Parallel search should produce a best move");
                    MoveAndScore best = result.bestMove();
                    assertEquals(baselineBest.move, best.move, "Best move must not regress under contention");
                    assertEquals(baselineBest.score, best.score, 0.05,
                            "Best score should remain within the lazy-SMP diversification window");
                } finally {
                    if (previous != null) {
                        parallelThreadLocal.set(previous);
                    } else {
                        parallelThreadLocal.remove();
                    }
                }
            }
        } finally {
            barrierExecutor.shutdownNow();
        }
    }

    @Test
    @Timeout(15)
    @DisplayName("Parallel root search shares remaining work across helpers")
    void parallelRootSearchSharesRemainingWork() throws Exception {
        int searchDepth = 3;

        AiTuning tuning = AiTuning.builder()
                .searchThreads(4)
                .lazySmpThreads(1)
                .maxDepth(5)
                .timeLimitMillis(500)
                .build();

        Engine engine = new Engine();
        AI ai = new AI(engine, tuning);

        BarrierExecutor barrierExecutor = new BarrierExecutor(ai.getSearchThreads());
        TestUtils.writeField(ai, "searchPool", barrierExecutor);

        Field rootLimitField = AI.class.getDeclaredField("ROOT_PARALLEL_LIMIT");
        rootLimitField.setAccessible(true);
        int rootLimit = rootLimitField.getInt(null);

        @SuppressWarnings("unchecked")
        ThreadLocal<SearchTask> threadLocal = (ThreadLocal<SearchTask>) TestUtils.readField(ai, "threadSearchTask");

        Engine sim = engine.createSimulation();
        SearchTask task = new SearchTask(
                20_000L,
                sim.getBoardStateHash(),
                sim.whitesTurn(),
                new TimeManager(5_000L).beginSearchWithOverride(TimeUnit.SECONDS.toMillis(5)),
                ai.getSearchThreads(),
                sim.createSimulation()
        );

        SearchTask previous = threadLocal.get();
        threadLocal.set(task);
        try {
            IntArrayList legal = sim.getAllLegalMoves();
            IntArrayList ordered = ai.sortMovesByEfficiency(legal, searchDepth, sim.getBoardStateHash(), -1, sim);

            int fanout = Math.min(rootLimit, ordered.size() - 1);
            int helperCapacity = ai.getSearchThreads();
            if (helperCapacity <= 0 && fanout > 0) {
                fanout = 0;
            } else if (helperCapacity > 0) {
                fanout = Math.min(fanout, helperCapacity);
            }

            assertTrue(fanout > 0, "Test position should trigger parallel fan-out");
            assertTrue(ordered.size() - 1 > fanout,
                    "Position should offer more root moves than the initial helper count");

            barrierExecutor.setParties(fanout);

            RootSearchResult result = ai.searchRootMoves(
                    sim,
                    task,
                    searchDepth,
                    Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                    null
            );

            assertTrue(result.hasCandidate(), "Parallel search should produce a best move");
            assertTrue(barrierExecutor.getSubmittedTasks() > fanout,
                    "Parallel search should schedule work beyond the initial fan-out");
        } finally {
            if (previous != null) {
                threadLocal.set(previous);
            } else {
                threadLocal.remove();
            }
            barrierExecutor.shutdownNow();
        }
    }

    private static class FakeAutoAI extends AI {
        private final FakeScheduler scheduler = new FakeScheduler();

        FakeAutoAI(Engine engine, AiTuning tuning) {
            super(engine, tuning);
        }

        @Override
        public void startAutoPlay(boolean aiIsWhite, boolean aiIsBlack) {
            try {
                TestUtils.writeField(this, "scheduler", scheduler);
                Method start = AI.class.getDeclaredMethod("startCalculationThread");
                start.setAccessible(true);
                start.invoke(this);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }

            scheduler.scheduleAtFixedRate(() -> {
                try {
                    boolean keepCalculating = (boolean) TestUtils.readField(this, "keepCalculating");
                    if (getMainEngine().getGameState().isGameOver() || !keepCalculating) {
                        stopCalculation();
                        scheduler.shutdown();
                        return;
                    }
                    boolean whitesTurn = getMainEngine().whitesTurn();
                    boolean shouldMove = (aiIsWhite && whitesTurn) || (aiIsBlack && !whitesTurn);
                    if (!shouldMove) {
                        return;
                    }
                    boolean ready = (boolean) TestUtils.readField(this, "searchResultReady");
                    int current = (int) TestUtils.readField(this, "currentBestMove");
                    long bestHash = (long) TestUtils.readField(this, "bestMoveForHash");
                    if (ready && current != -1 && bestHash == getMainEngine().getBoardStateHash()) {
                        performMove();
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }, 0, 50, TimeUnit.MILLISECONDS);
        }
    }

    private static final class BarrierExecutor extends java.util.concurrent.AbstractExecutorService {
        private final ExecutorService delegate;
        private final AtomicReference<Phaser> phaserRef = new AtomicReference<>();
        private final AtomicInteger submitted = new AtomicInteger();
        private final AtomicInteger barrierPermits = new AtomicInteger();
        private volatile boolean shutdown;

        BarrierExecutor(int threads) {
            this.delegate = Executors.newFixedThreadPool(threads);
        }

        void setParties(int parties) {
            submitted.set(0);
            if (parties <= 1) {
                phaserRef.set(null);
                barrierPermits.set(0);
                return;
            }
            phaserRef.set(new Phaser(parties));
            barrierPermits.set(parties);
        }

        int getSubmittedTasks() {
            return submitted.get();
        }

        @Override
        public void shutdown() {
            shutdown = true;
            delegate.shutdown();
        }

        @NonNull
        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, @NonNull TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(@NonNull Runnable command) {
            delegate.execute(() -> {
                submitted.incrementAndGet();
                Phaser phaser = phaserRef.get();
                if (phaser != null) {
                    int remaining = barrierPermits.getAndUpdate(v -> v > 0 ? v - 1 : v);
                    if (remaining > 0) {
                        phaser.arriveAndAwaitAdvance();
                        if (remaining == 1) {
                            phaserRef.compareAndSet(phaser, null);
                        }
                    }
                }
                command.run();
            });
        }
    }
}

