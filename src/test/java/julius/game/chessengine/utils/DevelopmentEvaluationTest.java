package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.evaluation.EvaluationContext;
import julius.game.chessengine.evaluation.PieceSquareModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class DevelopmentEvaluationTest {

    @Test
    void undevelopedBlackMinorsArePenalizedAfterOpening() {
        BitBoard undeveloped = FEN.translateFENtoBitBoard("rnb1kbnr/pppppppp/8/8/2B2B2/2N2N2/PPPPPPPP/R3K2R w KQkq - 0 1");
        BitBoard developed = FEN.translateFENtoBitBoard("r3k2r/pppbbppp/2n2n2/8/2B2B2/2N2N2/PPPPPPPP/R3K2R w KQkq - 0 1");

        int undevelopedPenalty = developmentContribution(undeveloped);
        int developedPenalty = developmentContribution(developed);
        assertTrue(undevelopedPenalty < developedPenalty,
                "Black should be penalized for leaving minors on their home squares after the opening");

        Score undevelopedScore = Score.initializeScore(undeveloped);
        Score developedScore = Score.initializeScore(developed);
        assertTrue(undevelopedScore.getScoreDifference() > developedScore.getScoreDifference(),
                "White should prefer the position where black remains undeveloped");
    }

    @Test
    void earlyQueenDevelopmentIsDiscouraged() {
        BitBoard queenHome = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        BitBoard queenAdventure = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/7Q/8/8/PPPPPPPP/RNB1KBNR w KQkq - 0 3");

        int homeContribution = developmentContribution(queenHome);
        int adventureContribution = developmentContribution(queenAdventure);
        assertTrue(adventureContribution < homeContribution,
                "Advancing the queen before developing minors should be penalized");

        Score queenHomeScore = Score.initializeScore(queenHome);
        Score queenAdventureScore = Score.initializeScore(queenAdventure);
        assertTrue(queenAdventureScore.getScoreDifference() < queenHomeScore.getScoreDifference(),
                "White's evaluation should drop when the queen comes out too early");
    }

    private static int developmentContribution(BitBoard board) {
        PieceSquareModule module = new PieceSquareModule();
        EvaluationContext context = EvaluationContext.from(board, null);
        module.initialize(context);
        return module.getDevelopmentContribution();
    }
}
