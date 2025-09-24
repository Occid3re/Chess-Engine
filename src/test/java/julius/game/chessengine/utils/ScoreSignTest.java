package julius.game.chessengine.utils;

import julius.game.chessengine.engine.Engine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the material evaluation sign matches the side that is ahead.
 */
class ScoreSignTest {

    private static final String WHITE_UP_QUEEN = "8/8/8/8/8/8/4k3/3QK3 %s - - 0 1";

    @Test
    void scoreDifferenceIsPositiveWhenWhiteHasExtraQueenWhiteToMove() {
        Engine engine = new Engine();
        engine.importBoardFromFen(WHITE_UP_QUEEN.formatted("w"));
        double score = engine.getGameState().getScore().getScoreDifference();
        assertTrue(score > 0.0, "Score should be positive when white leads materially");
    }

    @Test
    void scoreDifferenceIsPositiveWhenWhiteHasExtraQueenBlackToMove() {
        Engine engine = new Engine();
        engine.importBoardFromFen(WHITE_UP_QUEEN.formatted("b"));
        double score = engine.getGameState().getScore().getScoreDifference();
        assertTrue(score > 0.0, "Score should be positive for white regardless of side to move");
    }
}

