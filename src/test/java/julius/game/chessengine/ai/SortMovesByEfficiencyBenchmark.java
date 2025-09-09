package julius.game.chessengine.ai;

import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.engine.Engine;
import org.junit.jupiter.api.Test;

/**
 * Simple micro benchmark for {@link AI#sortMovesByEfficiency} to ensure the
 * array based implementation performs as expected. The benchmark does not
 * assert timing but prints the total execution time so it can be compared
 * manually when needed.
 */
public class SortMovesByEfficiencyBenchmark {

    @Test
    public void benchmarkSortMoves() {
        Engine engine = new Engine();
        engine.startNewGame();
        AI ai = new AI(engine);
        MoveList moves = engine.getAllLegalMoves();

        for (int i = 0; i < 1000; i++) {
            ai.sortMovesByEfficiency(moves, engine.whitesTurn(), 0);
        }

        long start = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            ai.sortMovesByEfficiency(moves, engine.whitesTurn(), 0);
        }
        long elapsed = System.nanoTime() - start;
        System.out.println("sortMovesByEfficiency benchmark: " + (elapsed / 1_000_000) + " ms for 10000 iterations");
    }
}
