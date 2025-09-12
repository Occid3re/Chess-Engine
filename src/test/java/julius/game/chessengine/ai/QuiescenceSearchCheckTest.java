package julius.game.chessengine.ai;

import julius.game.chessengine.board.Move;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class QuiescenceSearchCheckTest {

    @Test
    void engineAvoidsAllowingImmediateMate() {
        Engine engine = new Engine();
        AI ai = new AI(engine);
        engine.importBoardFromFen("7k/7p/4N3/5Q1/8/4q3/4R3/6K1 b - - 0 1");

        MoveList legal = engine.getAllLegalMoves();
        int losingMove = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        int bestMove = -1;
        for (int i = 0; i < legal.size(); i++) {
            int m = legal.getMove(i);
            Move decoded = Move.convertIntToMove(m);

            engine.performMove(m);
            double score = ai.evaluateBoard(engine, engine.whitesTurn(), System.nanoTime() + 1_000_000_000L);
            engine.undoLastMove();
            double blackScore = -score;

            if ("Qxe2".equals(decoded.toString())) {
                losingMove = m;
                assertTrue(score >= Score.CHECKMATE - 1);
            }
            if (blackScore > bestScore) {
                bestScore = blackScore;
                bestMove = m;
            }
        }
        assertNotEquals(losingMove, bestMove);
    }
}
