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
        assertEquals(0, engine.getLine().size(),
                "Auto-play must not execute when side to move does not match configuration");

        BlockingQueue<?> requests = (BlockingQueue<?>) TestUtils.readField(ai, "calculationRequests");
        assertFalse(requests.isEmpty(), "Auto-play should enqueue a new calculation request after executing a move");
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
}

