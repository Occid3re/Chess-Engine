package julius.game.chessengine.engine;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void importingInsufficientMaterialRemainsPlayableButFlagsDraw() {
        Engine engine = new Engine();

        engine.importBoardFromFen("8/5k2/8/8/8/8/5K2/8 w - - 0 1");

        GameState gameState = engine.getGameState();
        assertFalse(gameState.isGameOver(), "Insufficient material should not stop move handling");
        assertEquals(GameStateEnum.PLAY, gameState.getState(), "Engine should remain in PLAY state");
        assertTrue(gameState.isDrawByInsufficientMaterial(), "Flag should mark draw for UI/evaluation");

        // Use the UI/eval helper (includes insufficient material)
        assertTrue(gameState.isDrawForUIOrEval(), "Draw should be visible to UI/eval");

        // Terminal-only draw API must be false here
        assertFalse(gameState.isInStateDraw(), "Non-terminal draws must not be treated as terminal");

        IntArrayList moves = engine.getAllLegalMoves();
        assertFalse(moves.isEmpty(), "Moves must still be generated under insufficient material");

        int initialHistory = engine.getLine().size();
        int move = moves.getInt(0);
        engine.performMove(move);
        assertEquals(initialHistory + 1, engine.getLine().size(), "performMove must push to history");

        engine.undoLastMove();
        assertEquals(initialHistory, engine.getLine().size(), "undoLastMove must restore history");
    }

}
