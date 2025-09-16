package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ScoreEvaluationTest {

    @Test
    void stalemateHasLowestMobility() {
        BitBoard stalemate = FEN.translateFENtoBitBoard("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
        Score scoreStalemate = Score.initializeScore(stalemate);

        BitBoard active = FEN.translateFENtoBitBoard("7k/4Q3/6K1/8/8/8/8/8 b - - 0 1");
        Score scoreActive = Score.initializeScore(active);

        assertTrue(scoreStalemate.getBlackMobilityScore() <= scoreActive.getBlackMobilityScore());
        assertTrue(scoreActive.getBlackMobilityScore() > 0);
        assertTrue(scoreStalemate.getScoreDifference() > scoreActive.getScoreDifference());
    }

    @Test
    void exposedKingIsPenalized() {
        BitBoard safe = FEN.translateFENtoBitBoard("6k1/6pp/8/8/8/8/6PP/6K1 w - - 0 1");
        Score scoreSafe = Score.initializeScore(safe);

        BitBoard exposed = FEN.translateFENtoBitBoard("6k1/8/8/6pp/8/8/6PP/6K1 w - - 0 1");
        Score scoreExposed = Score.initializeScore(exposed);

        assertTrue(scoreExposed.getBlackKingSafetyScore() < scoreSafe.getBlackKingSafetyScore());
        assertTrue(scoreExposed.getScoreDifference() > scoreSafe.getScoreDifference());
    }

    @Test
    void centerPawnsGrantBonusToWhite() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/8/4P3/8/8/4K3 w - - 0 1");
        Score score = Score.initializeScore(board);

        assertEquals(Score.CENTER_PAWN_BONUS, score.getWhiteCenterPawnBonus());
        assertEquals(0, score.getBlackCenterPawnBonus());
    }

    @Test
    void centerPawnsGrantBonusToBlack() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/4p3/8/8/8/4K3 w - - 0 1");
        Score score = Score.initializeScore(board);

        assertEquals(0, score.getWhiteCenterPawnBonus());
        assertEquals(Score.CENTER_PAWN_BONUS, score.getBlackCenterPawnBonus());
    }
}
