package julius.game.chessengine.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineFenImportTest {

    @Test
    void importingFenWithFiftyMoveDrawMarksGameOver() {
        Engine engine = new Engine();

        engine.importBoardFromFen("4k3/8/8/8/8/8/8/4K3 w - - 100 1");

        GameState gameState = engine.getGameState();
        assertTrue(gameState.isGameOver());
        assertEquals(GameStateEnum.DRAW, gameState.getState());
        assertEquals(0, engine.getAllLegalMoves().size());
    }
}
