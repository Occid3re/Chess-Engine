package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.evaluation.KingSafetyModule;
import julius.game.chessengine.evaluation.KingSafetyModule.KingSafetyView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NotCastledPenaltyTest {

    @Test
    void penaltySkippedWhenShielded() {
        BitBoard baseline = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        BitBoard noRights = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w - - 0 1");

        assertEquals(whiteKingSafety(baseline), whiteKingSafety(noRights));
    }

    @Test
    void penaltyAppliedWhenOpenFile() {
        BitBoard shield = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w - - 0 1");
        BitBoard openFile = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPP1PPP/RNBQKBNR w - - 0 1");

        assertTrue(whiteKingSafety(openFile) < whiteKingSafety(shield));

        Score scoreShield = Score.initializeScore(shield);
        Score scoreOpen = Score.initializeScore(openFile);
        assertTrue(scoreOpen.getScoreDifference() < scoreShield.getScoreDifference());
    }

    @Test
    void penaltyReducedWhenBehindMaterial() {
        BitBoard openFile = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPP1PPP/RNBQKBNR w - - 0 1");
        BitBoard openFileWhiteDown = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPP1PPP/RNBQKB1R w - - 0 1");

        assertTrue(whiteKingSafety(openFileWhiteDown) > whiteKingSafety(openFile));

        Score evenScore = Score.initializeScore(openFile);
        Score downScore = Score.initializeScore(openFileWhiteDown);
        assertTrue(downScore.getScoreDifference() > evenScore.getScoreDifference());
    }

    private static int whiteKingSafety(BitBoard board) {
        KingSafetyModule module = new KingSafetyModule();
        KingSafetyView view = module.getView(board);
        return view.whiteKing().blend(board.getPhase());
    }
}
