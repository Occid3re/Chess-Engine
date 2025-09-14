package julius.game.chessengine.engine;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.board.MoveList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GameStateCheckmateTest {

    @Test
    void blackCheckmatedSetsWhiteWon() {
        BitBoard board = FEN.translateFENtoBitBoard("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1");
        MoveList legalMoves = board.generateAllPossibleMoves(board.isWhitesTurn());
        GameState gameState = new GameState(board);
        gameState.updateState(board, legalMoves, false);
        assertEquals(0, legalMoves.size());
        assertEquals(GameStateEnum.WHITE_WON, gameState.getState());
        assertNotEquals(GameStateEnum.DRAW, gameState.getState());
    }

    @Test
    void whiteCheckmatedSetsBlackWon() {
        BitBoard board = FEN.translateFENtoBitBoard("8/8/8/8/8/6k1/6q1/7K w - - 0 1");
        MoveList legalMoves = board.generateAllPossibleMoves(board.isWhitesTurn());
        GameState gameState = new GameState(board);
        gameState.updateState(board, legalMoves, false);
        assertEquals(0, legalMoves.size());
        assertEquals(GameStateEnum.BLACK_WON, gameState.getState());
        assertNotEquals(GameStateEnum.DRAW, gameState.getState());
    }
}
