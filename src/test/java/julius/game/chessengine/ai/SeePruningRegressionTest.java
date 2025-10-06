package julius.game.chessengine.ai;

import julius.game.chessengine.board.Move;
import julius.game.chessengine.engine.Engine;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SeePruningRegressionTest {

    @Test
    void shouldKeepSacrificialCaptureNearRoot() {
        String fen = "3r3k/1p1n1pp1/p2P1n1p/q1pN4/4B3/PP3P1P/2P2PKB/R7 w - - 6 35";

        Engine engine = new Engine();
        engine.importBoardFromFen(fen);

        AI ai = new AI(engine);
        ai.setMaxDepth(6);

        MoveAndScore result = ai.searchBestMoveBlocking(TimeUnit.SECONDS.toMillis(2));
        assertNotNull(result, "Engine did not return a move for the sacrificial test position");

        String bestMove = Move.convertIntToMove(result.getMove()).toString();
        assertEquals("Nxf6", bestMove,
                () -> "Expected the engine to keep the sacrificial capture Nxf6, but got " + bestMove);

        ai.shutdown();
    }
}
