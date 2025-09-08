package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class HistoryHeuristicTest {

    private double runSearch(AI ai, Engine engine, int depth) throws Exception {
        Method m = AI.class.getDeclaredMethod("alphaBeta", Engine.class, int.class, double.class, double.class,
                boolean.class, long.class, long.class);
        m.setAccessible(true);
        ai.resetCounters();
        return (double) m.invoke(ai, engine, depth, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                engine.whitesTurn(), System.currentTimeMillis(), Long.MAX_VALUE);
    }

    @SuppressWarnings("unchecked")
    private void clearTranspositionTables() throws Exception {
        Field mainField = AI.class.getDeclaredField("transpositionTable");
        mainField.setAccessible(true);
        ((Map<Long, ?>) mainField.get(null)).clear();

        Field captureField = AI.class.getDeclaredField("captureTranspositionTable");
        captureField.setAccessible(true);
        ((Map<Long, ?>) captureField.get(null)).clear();
    }

    @Test
    void secondSearchVisitsFewerNodes() throws Exception {
        Engine engine = new Engine();
        engine.startNewGame();
        AI ai = new AI(engine);

        runSearch(ai, engine, 4);
        long firstNodes = ai.getNodesVisited();

        clearTranspositionTables();
        runSearch(ai, engine, 4);
        long secondNodes = ai.getNodesVisited();

        assertTrue(secondNodes <= firstNodes);
    }
}
