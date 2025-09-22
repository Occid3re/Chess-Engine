package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BatteryModuleTest {

    @Test
    void queenAndBishopBatteryAddsFormationBonus() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/6B1/8/8/8/2Q3K1 w - - 0 1");

        BatteryModule module = new BatteryModule();
        EvaluationPipeline pipeline = new EvaluationPipeline(List.of(module));
        pipeline.initialize(EvaluationContext.from(board, null));

        assertEquals(13, pipeline.getMidgameScore());
        assertEquals(9, pipeline.getEndgameScore());
    }

    @Test
    void batteryThreateningRookAddsExtraPressure() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/7r/6B1/8/8/8/2Q3K1 w - - 0 1");

        BatteryModule module = new BatteryModule();
        EvaluationPipeline pipeline = new EvaluationPipeline(List.of(module));
        pipeline.initialize(EvaluationContext.from(board, null));

        assertEquals(23, pipeline.getMidgameScore());
        assertEquals(17, pipeline.getEndgameScore());
    }
}
