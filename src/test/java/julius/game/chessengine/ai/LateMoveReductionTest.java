package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import julius.game.chessengine.ai.TranspositionTable;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LateMoveReductionTest {

    private double runSearch(AI ai, Engine engine, int depth) throws Exception {
        Method m = AI.class.getDeclaredMethod("alphaBeta", Engine.class, int.class, double.class, double.class,
                boolean.class, long.class, long.class);
        m.setAccessible(true);
        ai.resetCounters();
        return (double) m.invoke(ai, engine, depth, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                engine.whitesTurn(), System.currentTimeMillis(), Long.MAX_VALUE);
    }

    private void clearTranspositionTables() throws Exception {
        Field mainField = AI.class.getDeclaredField("transpositionTable");
        mainField.setAccessible(true);
        ((TranspositionTable<?>) mainField.get(null)).clear();

        Field captureField = AI.class.getDeclaredField("captureTranspositionTable");
        captureField.setAccessible(true);
        ((TranspositionTable<?>) captureField.get(null)).clear();
    }

    @Test
    void lmrProducesSameScoreAsBaseline() throws Exception {
        Engine baseEngine = new Engine();
        baseEngine.startNewGame();
        AI baselineAI = new AI(baseEngine);
        baselineAI.setUseLateMoveReductions(false);
        double baseline = runSearch(baselineAI, baseEngine, 3);

        clearTranspositionTables();

        Engine lmrEngine = new Engine();
        lmrEngine.startNewGame();
        AI lmrAI = new AI(lmrEngine);
        lmrAI.setUseLateMoveReductions(true);
        double withLmr = runSearch(lmrAI, lmrEngine, 3);

        assertEquals(baseline, withLmr, 0.0001);
    }
}
