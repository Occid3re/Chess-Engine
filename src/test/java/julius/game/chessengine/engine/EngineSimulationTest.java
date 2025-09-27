package julius.game.chessengine.engine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class EngineSimulationTest {

    @Test
    public void simulationDoesNotAffectMainEngineLegalMoves() throws Exception {
        Engine main = new Engine();
        IntArrayList mainMoves = main.getAllLegalMoves();
        assertTrue(mainMoves.size() > 0);
        int mainSize = mainMoves.size();

        Engine sim = main.createSimulation();
        IntArrayList simMoves = sim.getAllLegalMoves();
        assertNotSame(mainMoves, simMoves);

        sim.performMove(simMoves.getInt(0));

        assertEquals(mainSize, main.getAllLegalMoves().size());
    }
}
