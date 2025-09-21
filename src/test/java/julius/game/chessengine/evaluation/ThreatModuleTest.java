package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThreatModuleTest {

    @Test
    void defendedKnightUnderPawnAttackIsSoftlyPenalized() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/3p4/4N3/8/6B1/4K3 w - - 0 1");

        ThreatModule threatModule = new ThreatModule();
        EvaluationPipeline pipeline = new EvaluationPipeline(List.of(threatModule));
        pipeline.initialize(EvaluationContext.from(board, null));

        int expectedPenalty = (ThreatModuleTestConstants.KNIGHT_PAWN_THREAT * MaterialModule.BISHOP_VALUE)
                / MaterialModule.PAWN_VALUE;

        assertEquals(expectedPenalty, pipeline.getMidgameScore());
        assertEquals(expectedPenalty, pipeline.getEndgameScore());
        assertEquals(expectedPenalty, pipeline.getBlendedScore());
    }

    private static final class ThreatModuleTestConstants {
        private static final int KNIGHT_PAWN_THREAT = -10;

        private ThreatModuleTestConstants() {
        }
    }
}

