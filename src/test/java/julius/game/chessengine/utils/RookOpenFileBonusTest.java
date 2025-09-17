package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.evaluation.PawnStructureModule;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Log4j2
public class RookOpenFileBonusTest {

    @Test
    void rooksOnOpenFilesScoreHigherThanHalfOpen() {
        BitBoard openFile = FEN.translateFENtoBitBoard("4k3/p7/8/8/8/8/8/1R2K3 w - - 0 1");
        BitBoard halfOpenFile = FEN.translateFENtoBitBoard("4k3/p7/8/8/8/8/8/R3K3 w - - 0 1");

        int openCount = new PawnStructureModule()
                .countOpenFilesWithRooks(openFile, openFile.getWhiteRooks());
        int halfCount = new PawnStructureModule()
                .countHalfOpenFilesWithRooks(halfOpenFile, halfOpenFile.getWhiteRooks(), true);

        log.info("openCount: " + openCount);
        log.info("halfCount: " + halfCount);

        assertTrue(openCount == halfCount);

        Score openScore = Score.initializeScore(openFile);
        Score halfScore = Score.initializeScore(halfOpenFile);

        log.info("OpenScore: " + openScore.getScoreDifference());
        log.info("HalfOpenScore: " + halfScore.getScoreDifference());

        assertTrue(openScore.getScoreDifference() > halfScore.getScoreDifference());
    }
}
