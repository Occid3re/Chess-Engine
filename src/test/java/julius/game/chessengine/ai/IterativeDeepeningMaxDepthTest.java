package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IterativeDeepeningMaxDepthTest {

    @Test
    void setMaxDepthResizesKillerMovesAndAllowsThirtyPlies() throws Exception {
        Engine engine = new Engine();
        TestAI ai = new TestAI(engine);

        int initialCapacity = extractKillerMoves(ai).length;

        ai.setMaxDepth(30);

        assertEquals(30, ai.getMaxDepth(), "maxDepth should be set to the requested value");

        int[][] killerMoves = extractKillerMoves(ai);
        assertTrue(killerMoves.length >= 30, "killer move table must accommodate the requested depth");

        if (killerMoves.length > initialCapacity) {
            for (int depth = initialCapacity; depth < killerMoves.length; depth++) {
                for (int slot = 0; slot < killerMoves[depth].length; slot++) {
                    assertEquals(-1, killerMoves[depth][slot], "new killer move slots should be initialised to -1");
                }
            }
        }

        long boardHash = engine.getBoardStateHash();
        setLongField(ai, "currentBoardState", boardHash);
        setLongField(ai, "beforeCalculationBoardState", boardHash);

        SearchTask task = new SearchTask(1L, boardHash, engine.whitesTurn(),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(1), 1);

        Method iterativeDeepening = AI.class.getDeclaredMethod(
                "iterativeDeepening", SearchTask.class, Engine.class, SplittableRandom.class);
        iterativeDeepening.setAccessible(true);
        iterativeDeepening.invoke(ai, task, engine, null);

        assertEquals(30, ai.getDeepestRequestedDepth(),
                "iterative deepening should reach the configured depth");
    }

    private static int[][] extractKillerMoves(AI ai) {
        return ai.snapshotKillerMoves();
    }

    private static void setLongField(AI ai, String fieldName, long value) throws Exception {
        Field field = AI.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setLong(ai, value);
    }

    private static final class TestAI extends AI {
        private int deepestRequestedDepth = 0;

        private TestAI(Engine engine) {
            super(engine);
        }

        @Override
        protected MoveAndScore searchRootMoves(Engine sim, SearchTask task, int depth,
                                               double alpha, double beta, SplittableRandom rng) {
            deepestRequestedDepth = Math.max(deepestRequestedDepth, depth);
            return new MoveAndScore(0, 0.0);
        }

        int getDeepestRequestedDepth() {
            return deepestRequestedDepth;
        }
    }
}
