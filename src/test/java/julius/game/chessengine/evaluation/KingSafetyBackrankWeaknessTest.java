package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.evaluation.KingSafetyModule.KingSafetyView;
import julius.game.chessengine.tuning.Tuning;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KingSafetyBackrankWeaknessTest {

    private static final String UNSTOPPABLE_WHITE = "6k1/4q3/8/8/8/8/3P1P2/4K3 w - - 0 1";
    private static final String SAFE_ESCAPE = "6k1/4q3/8/8/8/8/3P4/4K3 w - - 0 1";
    private static final String BLOCKED_LINE = "6k1/4q3/8/8/8/8/3PRP2/4K3 w - - 0 1";

    @Test
    void penaltyAppliedWhenBackrankMateThreatExists() {
        BitBoard board = FEN.translateFENtoBitBoard(UNSTOPPABLE_WHITE);
        KingSafetyModule module = new KingSafetyModule();

        KingSafetyView view = module.getView(board);
        int midgamePenalty = view.whiteBackrankWeakness().blend(0);
        int endgamePenalty = view.whiteBackrankWeakness().blend(256);

        assertEquals(Tuning.backrankWeaknessMidgamePenalty(), midgamePenalty);
        assertEquals(Tuning.backrankWeaknessEndgamePenalty(), endgamePenalty);
        assertTrue(view.whiteBackrankWeakness().blend(board.getPhase()) < 0);
    }

    @Test
    void penaltyClearedWhenLuftIsAvailable() {
        BitBoard board = FEN.translateFENtoBitBoard(SAFE_ESCAPE);
        KingSafetyModule module = new KingSafetyModule();

        KingSafetyView view = module.getView(board);
        assertEquals(0, view.whiteBackrankWeakness().blend(0));
        assertEquals(0, view.whiteBackrankWeakness().blend(256));
    }

    @Test
    void penaltyClearedWhenHeavyPieceIsBlocked() {
        BitBoard board = FEN.translateFENtoBitBoard(BLOCKED_LINE);
        KingSafetyModule module = new KingSafetyModule();

        KingSafetyView view = module.getView(board);
        assertEquals(0, view.whiteBackrankWeakness().blend(0));
        assertEquals(0, view.whiteBackrankWeakness().blend(256));
    }

    @Test
    void evaluateBackrankWeaknessMicroBenchmark() {
        BitBoard board = FEN.translateFENtoBitBoard(UNSTOPPABLE_WHITE);
        KingSafetyModule module = new KingSafetyModule();

        assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
            for (int i = 0; i < 2048; i++) {
                module.evaluate(board);
            }
        });
    }
}
