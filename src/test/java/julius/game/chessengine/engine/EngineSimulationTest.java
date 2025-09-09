package julius.game.chessengine.engine;

import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.cache.TimedLRUCache;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

public class EngineSimulationTest {

    @Test
    public void simulationDoesNotAffectMainEngineLegalMoves() throws Exception {
        Engine main = new Engine();
        MoveList mainMoves = main.getAllLegalMoves();
        assertTrue(mainMoves.size() > 0);
        int mainSize = mainMoves.size();

        Engine sim = main.createSimulation();
        MoveList simMoves = sim.getAllLegalMoves();
        assertNotSame(mainMoves, simMoves);

        sim.performMove(simMoves.getMove(0));

        assertEquals(mainSize, main.getAllLegalMoves().size());

        Field cacheField = Engine.class.getDeclaredField("legalMovesCache");
        cacheField.setAccessible(true);
        TimedLRUCache<MoveList> mainCache = (TimedLRUCache<MoveList>) cacheField.get(main);
        TimedLRUCache<MoveList> simCache = (TimedLRUCache<MoveList>) cacheField.get(sim);
        assertNotSame(mainCache, simCache);
    }
}
