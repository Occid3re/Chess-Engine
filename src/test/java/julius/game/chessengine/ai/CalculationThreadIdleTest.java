package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CalculationThreadIdleTest {

    @Test
    void calculationThreadWaitsWhenNoPositionChange() throws Exception {
        Engine engine = new Engine();
        AI ai = new AI(engine);
        ai.setTimeLimit(1);

        long hash = engine.getBoardStateHash();
        Field currentField = AI.class.getDeclaredField("currentBoardState");
        currentField.setAccessible(true);
        currentField.setLong(ai, hash);
        Field beforeField = AI.class.getDeclaredField("beforeCalculationBoardState");
        beforeField.setAccessible(true);
        beforeField.setLong(ai, hash);

        Method startCalc = AI.class.getDeclaredMethod("startCalculationThread");
        startCalc.setAccessible(true);
        startCalc.invoke(ai);

        Field threadField = AI.class.getDeclaredField("calculationThread");
        threadField.setAccessible(true);
        Thread calcThread = (Thread) threadField.get(ai);

        for (int i = 0; i < 100 && calcThread.getState() != Thread.State.WAITING; i++) {
            Thread.sleep(20);
        }

        assertEquals(Thread.State.WAITING, calcThread.getState());
        ai.stopCalculation();
    }
}
