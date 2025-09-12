package julius.game.chessengine.ai;

import julius.game.chessengine.board.Move;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class QuiescenceSearchCheckTest {

    @Test
    void engineAvoidsAllowingImmediateMate() throws Exception {
        Engine engine = new Engine();
        AI ai = new AI(engine);
        engine.importBoardFromFen("7k/7p/4N3/5Q1/8/4q3/4R3/6K1 b - - 0 1");

        Method alphaBeta = AI.class.getDeclaredMethod("alphaBeta", Engine.class, int.class,
                double.class, double.class, boolean.class, long.class);
        alphaBeta.setAccessible(true);

        MoveList legal = engine.getAllLegalMoves();
        int losingMove = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        int bestMove = -1;
        for (int i = 0; i < legal.size(); i++) {
            int m = legal.getMove(i);
            Move decoded = Move.convertIntToMove(m);

            engine.performMove(m);
            long deadline = System.nanoTime() + 1_000_000_000L;
            double score = (double) alphaBeta.invoke(ai, engine, 0, Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY, engine.whitesTurn(), deadline);
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
