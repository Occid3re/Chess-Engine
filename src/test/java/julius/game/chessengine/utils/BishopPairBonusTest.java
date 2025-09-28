package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.evaluation.EvaluationContext;
import julius.game.chessengine.evaluation.EvaluationPipeline;
import julius.game.chessengine.evaluation.MaterialModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BishopPairBonusTest {

    @Test
    void whiteBishopsPairGetsBonus() {
        BitBoard pair = FEN.translateFENtoBitBoard("4k3/8/8/8/8/8/8/2B1KB2 w - - 0 1");
        BitBoard single = FEN.translateFENtoBitBoard("4k3/8/8/8/8/8/8/4KB2 w - - 0 1");

        MaterialModule module = new MaterialModule();
        int diff = materialScore(pair) - materialScore(single);
        assertEquals(module.getBishopValue() + module.getBishopPairBonus(), diff);
    }

    @Test
    void blackBishopsPairGetsBonus() {
        BitBoard pair = FEN.translateFENtoBitBoard("2b1kb2/8/8/8/8/8/8/4K3 w - - 0 1");
        BitBoard single = FEN.translateFENtoBitBoard("4kb2/8/8/8/8/8/8/4K3 w - - 0 1");

        MaterialModule module = new MaterialModule();
        int diff = materialScore(single) - materialScore(pair);
        assertEquals(module.getBishopValue() + module.getBishopPairBonus(), diff);
    }

    private static int materialScore(BitBoard board) {
        MaterialModule module = new MaterialModule();
        EvaluationPipeline pipeline = new EvaluationPipeline(java.util.List.of(module));
        pipeline.initialize(EvaluationContext.from(board, null));
        return pipeline.getMidgameScore();
    }
}
