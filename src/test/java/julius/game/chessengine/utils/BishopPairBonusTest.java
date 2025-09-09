package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BishopPairBonusTest {

    @Test
    void whiteBishopsPairGetsBonus() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/8/8/8/8/2B1KB2 w - - 0 1");
        Score score = new Score();
        score.initializeScore(board);
        assertEquals(Score.BISHOP_PAIR_BONUS, score.getWhiteBishopPairBonus());
        assertEquals(0, score.getBlackBishopPairBonus());
    }

    @Test
    void blackBishopsPairGetsBonus() {
        BitBoard board = FEN.translateFENtoBitBoard("2b1kb2/8/8/8/8/8/8/4K3 w - - 0 1");
        Score score = new Score();
        score.initializeScore(board);
        assertEquals(Score.BISHOP_PAIR_BONUS, score.getBlackBishopPairBonus());
        assertEquals(0, score.getWhiteBishopPairBonus());
    }
}

