package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ScoreEvaluationTest {

    @Test
    void stalemateHasLowestMobility() {
        BitBoard stalemate = FEN.translateFENtoBitBoard("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
        Score scoreStalemate = new Score();
        scoreStalemate.initializeScore(stalemate);
        assertEquals(0, scoreStalemate.getBlackMobilityScore());

        BitBoard active = FEN.translateFENtoBitBoard("7k/4Q3/6K1/8/8/8/8/8 b - - 0 1");
        Score scoreActive = new Score();
        scoreActive.initializeScore(active);
        assertTrue(scoreActive.getBlackMobilityScore() > 0);
        assertTrue(scoreStalemate.getScoreDifference() > scoreActive.getScoreDifference());
    }

    @Test
    void exposedKingIsPenalized() {
        BitBoard safe = FEN.translateFENtoBitBoard("6k1/6pp/8/8/8/8/6PP/6K1 w - - 0 1");
        Score scoreSafe = new Score();
        scoreSafe.initializeScore(safe);

        BitBoard exposed = FEN.translateFENtoBitBoard("6k1/8/8/6pp/8/8/6PP/6K1 w - - 0 1");
        Score scoreExposed = new Score();
        scoreExposed.initializeScore(exposed);

        assertTrue(scoreExposed.getBlackKingSafetyScore() < scoreSafe.getBlackKingSafetyScore());
        assertTrue(scoreExposed.getScoreDifference() > scoreSafe.getScoreDifference());
    }
}
