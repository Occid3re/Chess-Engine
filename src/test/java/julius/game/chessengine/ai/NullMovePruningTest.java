package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class NullMovePruningTest {

    private double runSearch(AI ai, Engine engine, int depth) throws Exception {
        Method m = AI.class.getDeclaredMethod("alphaBeta", Engine.class, int.class, double.class, double.class, boolean.class, long.class, long.class);
        m.setAccessible(true);
        ai.resetCounters();
        return (double) m.invoke(ai, engine, depth, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, engine.whitesTurn(), System.currentTimeMillis(), Long.MAX_VALUE);
    }

    @Test
    void nullMoveImprovesPerformance() throws Exception {
        Engine engine = new Engine();
        engine.startNewGame();
        AI ai = new AI(engine);

        ai.setUseNullMovePruning(true);
        runSearch(ai, engine, 4);
        long withNull = ai.getNodesVisited();
        assertTrue(ai.getNullMoveCount() > 0);

        ai.setUseNullMovePruning(false);
        runSearch(ai, engine, 4);
        long withoutNull = ai.getNodesVisited();

        assertTrue(withNull < withoutNull);
    }

    @Test
    void nullMoveSkippedInPawnEndgame() throws Exception {
        Engine engine = new Engine();
        engine.importBoardFromFen("8/8/8/8/8/8/PPPPPPPP/4kK2 w - - 0 1");
        AI ai = new AI(engine);

        runSearch(ai, engine, 4);
        assertEquals(0, ai.getNullMoveCount());
    }
}
