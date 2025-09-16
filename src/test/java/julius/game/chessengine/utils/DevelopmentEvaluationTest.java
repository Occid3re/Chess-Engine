package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DevelopmentEvaluationTest {

    @Test
    void undevelopedBlackMinorsArePenalizedAfterOpening() {
        BitBoard undeveloped = FEN.translateFENtoBitBoard("rnb1kbnr/pppppppp/8/8/2B2B2/2N2N2/PPPPPPPP/R3K2R w KQkq - 0 1");
        Score undevelopedScore = Score.initializeScore(undeveloped);

        BitBoard developed = FEN.translateFENtoBitBoard("r3k2r/pppbbppp/2n2n2/8/2B2B2/2N2N2/PPPPPPPP/R3K2R w KQkq - 0 1");
        Score developedScore = Score.initializeScore(developed);

        assertTrue(undevelopedScore.getBlackMinorDevelopmentPenalty() < 0,
                "Black should be penalized for leaving minors on their home squares after the opening");
        assertEquals(0, developedScore.getBlackMinorDevelopmentPenalty(),
                "Fully developed minors should remove the penalty");
        assertTrue(undevelopedScore.getScoreDifference() > developedScore.getScoreDifference(),
                "White should prefer the position where black remains undeveloped");
    }

    @Test
    void earlyQueenDevelopmentIsDiscouraged() {
        BitBoard queenHome = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        Score queenHomeScore = Score.initializeScore(queenHome);

        BitBoard queenAdventure = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/7Q/8/8/PPPPPPPP/RNB1KBNR w KQkq - 0 3");
        Score queenAdventureScore = Score.initializeScore(queenAdventure);

        assertEquals(0, queenHomeScore.getWhiteQueenDevelopmentPenalty(),
                "Keeping the queen at home should avoid any penalty");
        assertTrue(queenAdventureScore.getWhiteQueenDevelopmentPenalty() < 0,
                "Advancing the queen before developing minors should be penalized");
        assertTrue(queenAdventureScore.getScoreDifference() < queenHomeScore.getScoreDifference(),
                "White's evaluation should drop when the queen comes out too early");
    }
}
