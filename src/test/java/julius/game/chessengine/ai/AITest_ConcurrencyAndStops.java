package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.AiTuning;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import testsupport.FakeScheduler;
import testsupport.TestLoggingExtension;
import testsupport.TestUtils;

import java.lang.reflect.Method;
import java.util.SplittableRandom;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

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
    @Timeout(10)
    @DisplayName("Parallel root search marks depth incomplete when the deadline expires early")
    void parallelRootSearchMarksDepthIncompleteWhenDeadlineExpiresEarly() throws Exception {
        SlowEngine engine = new SlowEngine(3);
        AI ai = new AI(engine, AiTuning.builder().searchThreads(2).build());

        Engine simulator = engine.createSimulation();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(5);
        SearchTask task = new SearchTask(
                1L,
                simulator.getBoardStateHash(),
                simulator.whitesTurn(),
                deadline,
                ai.getSearchThreads(),
                simulator.createSimulation()
        );

        @SuppressWarnings("unchecked")
        ThreadLocal<SearchTask> threadLocal = (ThreadLocal<SearchTask>) TestUtils.readField(ai, "threadSearchTask");
        RootSearchResult result;
        threadLocal.set(task);
        try {
            Method method = AI.class.getDeclaredMethod(
                    "getBestMoveParallel",
                    Engine.class,
                    SearchTask.class,
                    int.class,
                    long.class,
                    double.class,
                    double.class,
                    SplittableRandom.class
            );
            method.setAccessible(true);
            result = (RootSearchResult) method.invoke(
                    ai,
                    simulator,
                    task,
                    3,
                    deadline,
                    Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                    new SplittableRandom()
            );
        } finally {
            threadLocal.remove();
        }

        assertNotNull(result, "Root search should return a result even when aborted");
        assertTrue(result.hasCandidate(), "Aborted root search should keep the best-so-far move");
        assertFalse(result.isCompleted(), "Depth must be flagged as incomplete when not all root moves were evaluated");
    }

    @Test
    @Timeout(10)
    @DisplayName("Parallel root search completes after every root move is evaluated")
    void parallelRootSearchCompletesAfterEveryRootMoveIsEvaluated() throws Exception {
        String fen = "k7/2Q5/3Q4/4Q3/5Q2/6Q1/7Q/7K w - - 0 1";
        SlowEngine engine = new SlowEngine(0);
        engine.importBoardFromFen(fen);

        AI ai = new AI(engine, AiTuning.builder().searchThreads(2).build());

        Engine simulator = engine.createSimulation();
        int legalMoves = simulator.getAllLegalMoves().size();
        int rootLimit = Integer.getInteger("chessengine.rootParallelLimit", 24);
        assertTrue(legalMoves > rootLimit, "Test FEN should have more legal moves than the parallel fan-out limit");

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
        SearchTask task = new SearchTask(
                2L,
                simulator.getBoardStateHash(),
                simulator.whitesTurn(),
                deadline,
                ai.getSearchThreads(),
                simulator.createSimulation()
        );

        @SuppressWarnings("unchecked")
        ThreadLocal<SearchTask> threadLocal = (ThreadLocal<SearchTask>) TestUtils.readField(ai, "threadSearchTask");
        RootSearchResult result;
        threadLocal.set(task);
        try {
            Method method = AI.class.getDeclaredMethod(
                    "getBestMoveParallel",
                    Engine.class,
                    SearchTask.class,
                    int.class,
                    long.class,
                    double.class,
                    double.class,
                    SplittableRandom.class
            );
            method.setAccessible(true);
            result = (RootSearchResult) method.invoke(
                    ai,
                    simulator,
                    task,
                    2,
                    deadline,
                    Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                    new SplittableRandom()
            );
        } finally {
            threadLocal.remove();
        }

        assertNotNull(result, "Root search should produce a result under ample time");
        assertTrue(result.hasCandidate(), "Root search should return a best move when completed");
        assertTrue(result.isCompleted(), "Depth should be marked complete after every root move is evaluated");
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

    private static final class SlowEngine extends Engine {
        private final long delayMillis;

        private SlowEngine(long delayMillis) {
            super();
            this.delayMillis = delayMillis;
        }

        private SlowEngine(SlowEngine other) {
            super(other);
            this.delayMillis = other.delayMillis;
        }

        @Override
        public Engine createSimulation() {
            return new SlowEngine(this);
        }

        @Override
        public void performMove(int move) {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            super.performMove(move);
        }
    }
}

