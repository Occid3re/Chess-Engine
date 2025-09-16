package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StartingSquarePenaltyTest {

    @Test
    void noPenaltyWhenOnlyKnightsOnStartingSquares() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/8/8/8/R1B2B1R/1N2K1N1 w - - 0 1");
        Score score = Score.initializeScore(board);
        assertEquals(0, score.getWhiteStartingSquarePenalty());
    }

    @Test
    void noPenaltyWhenOnlyBishopsOnStartingSquares() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/8/8/2N2N2/R6R/2B1KB2 w - - 0 1");
        Score score = Score.initializeScore(board);
        assertEquals(0, score.getWhiteStartingSquarePenalty());
    }

    @Test
    void noPenaltyWhenOnlyRooksOnStartingSquares() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/8/2B2B2/2N2N2/8/R3K2R w - - 0 1");
        Score score = Score.initializeScore(board);
        assertEquals(0, score.getWhiteStartingSquarePenalty());
    }
}
