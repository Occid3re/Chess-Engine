package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class NotCastledPenaltyTest {

    @Test
    void penaltySkippedWhenShielded() {
        BitBoard baseline = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        Score scoreBaseline = Score.initializeScore(baseline);

        BitBoard noRights = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w - - 0 1");
        Score scoreNoRights = Score.initializeScore(noRights);

        assertEquals(scoreBaseline.getWhiteKingsPosition(), scoreNoRights.getWhiteKingsPosition());
    }

    @Test
    void penaltyAppliedWhenOpenFile() {
        BitBoard shield = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w - - 0 1");
        Score scoreShield = Score.initializeScore(shield);

        BitBoard openFile = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPP1PPP/RNBQKBNR w - - 0 1");
        Score scoreOpen = Score.initializeScore(openFile);

        assertTrue(scoreOpen.getWhiteKingsPosition() < scoreShield.getWhiteKingsPosition());
    }

    @Test
    void penaltyReducedWhenBehindMaterial() {
        BitBoard openFile = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPP1PPP/RNBQKBNR w - - 0 1");
        Score evenScore = Score.initializeScore(openFile);

        BitBoard openFileWhiteDown = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPP1PPP/RNBQKB1R w - - 0 1");
        Score downScore = Score.initializeScore(openFileWhiteDown);

        assertTrue(downScore.getWhiteKingsPosition() > evenScore.getWhiteKingsPosition());
    }
}
