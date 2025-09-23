package julius.game.chessengine.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EngineSimulationIsolationTest {

    @Test
    public void simulationDoesNotTriggerMainListener() {
        Engine engine = new Engine();
        AtomicInteger counter = new AtomicInteger();
        engine.setOnPositionChanged(hash -> counter.incrementAndGet());

        Engine simulation = engine.createSimulation();
        simulation.performMove(simulation.getAllLegalMoves().getMove(0));

        assertEquals(0, counter.get(), "Simulation move should not notify the main engine listener");
    }
}
