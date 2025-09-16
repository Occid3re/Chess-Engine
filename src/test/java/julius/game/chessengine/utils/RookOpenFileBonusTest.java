package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class RookOpenFileBonusTest {

    @Test
    void rooksOnOpenFilesScoreHigherThanHalfOpen() {
        BitBoard openFile = FEN.translateFENtoBitBoard("4k3/p7/8/8/8/8/8/1R2K3 w - - 0 1");
        Score openScore = Score.initializeScore(openFile);

        BitBoard halfOpenFile = FEN.translateFENtoBitBoard("4k3/p7/8/8/8/8/8/R3K3 w - - 0 1");
        Score halfScore = Score.initializeScore(halfOpenFile);

        assertTrue(openScore.getWhiteRooksOpenFileBonus() > halfScore.getWhiteRooksHalfOpenFileBonus());
        assertTrue(openScore.getScoreDifference() > halfScore.getScoreDifference());
    }
}
