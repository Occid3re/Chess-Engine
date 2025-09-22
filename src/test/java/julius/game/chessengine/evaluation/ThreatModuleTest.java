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

    @Test
    void queenBishopBatteryCreatesAdditionalPressure() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/4n3/6r1/8/8/3B4/2Q5/4K3 w - - 0 1");

        ThreatModule threatModule = new ThreatModule();
        EvaluationPipeline pipeline = new EvaluationPipeline(List.of(threatModule));
        pipeline.initialize(EvaluationContext.from(board, null));

        int formationBonus = ThreatModuleTestConstants.BATTERY_FORMATION_BASE
                + ThreatModuleTestConstants.BATTERY_QUEEN_BONUS;
        int rookPenalty = Math.max(ThreatModuleTestConstants.BATTERY_MINIMUM_PENALTY,
                MaterialModule.ROOK_VALUE / ThreatModuleTestConstants.BATTERY_PENALTY_DIVISOR);
        int expectedScore = formationBonus + rookPenalty;

        assertEquals(expectedScore, pipeline.getMidgameScore());
        assertEquals(expectedScore, pipeline.getEndgameScore());
        assertEquals(expectedScore, pipeline.getBlendedScore());
    }

    private static final class ThreatModuleTestConstants {
        private static final int KNIGHT_PAWN_THREAT = -10;
        private static final int BATTERY_FORMATION_BASE = 12;
        private static final int BATTERY_QUEEN_BONUS = 4;
        private static final int BATTERY_PENALTY_DIVISOR = 16;
        private static final int BATTERY_MINIMUM_PENALTY = 6;

        private ThreatModuleTestConstants() {
        }
    }
}

