package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class NotCastledPenaltyTest {

    @Test
    void penaltySkippedWhenShielded() {
        BitBoard baseline = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        Score scoreBaseline = new Score();
        scoreBaseline.initializeScore(baseline);

        BitBoard noRights = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w - - 0 1");
        Score scoreNoRights = new Score();
        scoreNoRights.initializeScore(noRights);

        assertEquals(scoreBaseline.getWhiteKingsPosition(), scoreNoRights.getWhiteKingsPosition());
    }

    @Test
    void penaltyAppliedWhenOpenFile() {
        BitBoard shield = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w - - 0 1");
        Score scoreShield = new Score();
        scoreShield.initializeScore(shield);

        BitBoard openFile = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPP1PPP/RNBQKBNR w - - 0 1");
        Score scoreOpen = new Score();
        scoreOpen.initializeScore(openFile);

        assertTrue(scoreOpen.getWhiteKingsPosition() < scoreShield.getWhiteKingsPosition());
    }

    @Test
    void penaltyReducedWhenBehindMaterial() {
        BitBoard openFile = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPP1PPP/RNBQKBNR w - - 0 1");
        Score evenScore = new Score();
        evenScore.initializeScore(openFile);

        BitBoard openFileWhiteDown = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPP1PPP/RNBQKB1R w - - 0 1");
        Score downScore = new Score();
        downScore.initializeScore(openFileWhiteDown);

        assertTrue(downScore.getWhiteKingsPosition() > evenScore.getWhiteKingsPosition());
    }
}
