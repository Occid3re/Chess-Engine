package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DrawScoreOrientationTest {

    private static final double EPSILON = 1e-9;

    @Test
    void evaluateStaticPositionReturnsMoverOrientedValue() throws Exception {
        Engine engine = new Engine();
        AI ai = new AI(engine);

        Method evaluateStatic = AI.class.getDeclaredMethod(
                "evaluateStaticPosition", GameState.class, boolean.class, int.class);
        evaluateStatic.setAccessible(true);

        GameState whiteDrawState = new GameState(engine.getGameState());
        stubDrawState(whiteDrawState, 320.0);
        double whiteToMoveScore = (double) evaluateStatic.invoke(ai, whiteDrawState, true, 0);

        GameState blackDrawState = new GameState(engine.getGameState());
        stubDrawState(blackDrawState, -320.0);
        double blackToMoveScore = (double) evaluateStatic.invoke(ai, blackDrawState, false, 0);

        assertTrue(whiteToMoveScore < Score.DRAW,
                "White should dislike accepting a draw while ahead.");
        assertTrue(blackToMoveScore > Score.DRAW,
                "Black should welcome a draw when behind.");
        assertEquals(-whiteToMoveScore, blackToMoveScore, EPSILON,
                "Scores should remain symmetric around zero.");
    }

    @Test
    void alphaBetaReturnsWhitePerspectiveDrawScoreForEitherSide() throws Exception {
        Engine whiteToMoveEngine = new Engine();
        whiteToMoveEngine.importBoardFromFen("8/8/8/8/8/8/8/K6k w - - 0 1");
        stubDrawState(whiteToMoveEngine.getGameState(), 250.0);
        AI whiteToMoveAI = new AI(whiteToMoveEngine);
        alignSearchState(whiteToMoveAI, whiteToMoveEngine);

        Method alphaBeta = AI.class.getDeclaredMethod("alphaBeta", Engine.class, int.class, double.class,
                double.class, boolean.class, long.class, int.class, int.class, int.class);
        alphaBeta.setAccessible(true);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        double whiteScore = (double) alphaBeta.invoke(whiteToMoveAI, whiteToMoveEngine, 1,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, true, deadline, -1, 0, 0);

        Engine blackToMoveEngine = new Engine();
        blackToMoveEngine.importBoardFromFen("8/8/8/8/8/8/8/K6k b - - 0 1");
        stubDrawState(blackToMoveEngine.getGameState(), 250.0);
        AI blackToMoveAI = new AI(blackToMoveEngine);
        alignSearchState(blackToMoveAI, blackToMoveEngine);

        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        double blackScore = (double) alphaBeta.invoke(blackToMoveAI, blackToMoveEngine, 1,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, deadline, -1, 0, 0);

        assertEquals(whiteScore, blackScore, EPSILON,
                "Draw adjudication should match regardless of the mover.");
        assertTrue(whiteScore < 0,
                "With a material advantage, a draw should be scored negatively from White's perspective.");
    }

    private static void stubDrawState(GameState state, double scoreDifference) {
        state.setState(GameStateEnum.DRAW);
        Score stubScore = new Score() {
            @Override
            public double getScoreDifference() {
                return scoreDifference;
            }
        };
        state.setScore(stubScore);
    }

    private static void alignSearchState(AI ai, Engine engine) throws Exception {
        long hash = engine.getBoardStateHash();
        Field currentField = AI.class.getDeclaredField("currentBoardState");
        currentField.setAccessible(true);
        currentField.setLong(ai, hash);

        Field beforeField = AI.class.getDeclaredField("beforeCalculationBoardState");
        beforeField.setAccessible(true);
        beforeField.setLong(ai, hash);
    }
}
