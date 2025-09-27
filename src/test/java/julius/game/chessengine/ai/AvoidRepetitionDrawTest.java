package julius.game.chessengine.ai;

import julius.game.chessengine.board.Move;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.engine.GameStateEnum;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

class AvoidRepetitionDrawTest {

    private static final String WINNING_FEN = "7k/p6B/5K1Q/8/8/8/8/8 w - - 0 1";

    @Test
    void whiteDoesNotRepeatWhenWinning() throws InterruptedException {
        Engine engine = new Engine();
        engine.importBoardFromFen(WINNING_FEN);

        int drawMove = findMove(engine, "h6", "g6");

        Engine repetitionProbe = engine.createSimulation();
        repetitionProbe.performMove(drawMove);
        long drawHash = repetitionProbe.getBoardStateHash();

        GameState gameState = engine.getGameState();
        gameState.getRepetition().put(drawHash, 2);

        AI ai = new AI(engine);
        ai.setTimeLimit(200L);
        ai.startAutoPlay(true, false);

        int observedMove = waitForMove(engine);

        ai.stopCalculation();

        Assertions.assertNotEquals(drawMove, observedMove,
                () -> "Expected engine to avoid repetition draw, but it played "
                        + Move.convertIntToMove(observedMove));
        Assertions.assertNotEquals(GameStateEnum.DRAW, engine.getGameState().getState(),
                "Engine incorrectly accepted a repetition draw from a winning position.");
        Assertions.assertEquals(GameStateEnum.WHITE_WON, engine.getGameState().getState(),
                "Engine should convert the advantage instead of repeating for a draw.");
    }

    private int findMove(Engine engine, String from, String to) {
        int fromIndex = MoveHelper.convertStringToIndex(from);
        int toIndex = MoveHelper.convertStringToIndex(to);
        IntArrayList legalMoves = engine.getAllLegalMoves();
        for (int i = 0; i < legalMoves.size(); i++) {
            int move = legalMoves.getInt(i);
            if (MoveHelper.deriveFromIndex(move) == fromIndex
                    && MoveHelper.deriveToIndex(move) == toIndex) {
                return move;
            }
        }
        throw new IllegalStateException("Move " + from + " -> " + to + " not found");
    }

    private int waitForMove(Engine engine) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        int lastMove = -1;
        while (System.currentTimeMillis() < deadline) {
            lastMove = engine.getLastMove();
            if (lastMove != -1) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        Assertions.assertNotEquals(-1, lastMove, "Engine failed to make a move in time");
        return lastMove;
    }
}
