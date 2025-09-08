package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PassedPawnScoreTest {

    @Test
    void whitePassedPawnGetsBonus() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/4P3/8/8/8/4K3 w - - 0 1");
        Score score = new Score();
        score.initializeScore(board);
        assertEquals(Score.PASSED_PAWN_BONUS * 4, score.getWhitePassedPawnBonus());
        assertEquals(0, score.getBlackPassedPawnBonus());
    }

    @Test
    void blackPassedPawnGetsBonus() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/8/3p4/8/8/4K3 w - - 0 1");
        Score score = new Score();
        score.initializeScore(board);
        assertEquals(Score.PASSED_PAWN_BONUS * 4, score.getBlackPassedPawnBonus());
        assertEquals(0, score.getWhitePassedPawnBonus());
    }
}

