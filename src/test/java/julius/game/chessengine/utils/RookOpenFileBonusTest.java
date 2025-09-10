package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class RookOpenFileBonusTest {

    @Test
    void moreRooksGiveHigherScore() {
        BitBoard twoRooks = FEN.translateFENtoBitBoard("4k3/8/8/8/8/8/8/RR2K3 w - - 0 1");
        Score scoreTwo = new Score();
        scoreTwo.initializeScore(twoRooks);

        BitBoard oneRook = FEN.translateFENtoBitBoard("4k3/8/8/8/8/8/8/R3K3 w - - 0 1");
        Score scoreOne = new Score();
        scoreOne.initializeScore(oneRook);

        assertTrue(scoreTwo.getScoreDifference() > scoreOne.getScoreDifference());
    }
}
