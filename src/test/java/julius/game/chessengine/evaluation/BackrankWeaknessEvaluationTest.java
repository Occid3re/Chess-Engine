package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.tuning.Tuning;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

class BackrankWeaknessEvaluationTest {

    private static final String ROOK_PRESSURE_FEN = "6rk/8/8/8/8/8/5P1P/6K1 w - - 0 1";
    private static final String BLOCKED_FILE_FEN = "6rk/8/8/8/8/6R1/7P/6K1 w - - 0 1";
    private static final String QUEEN_ALIGNMENT_FEN = "6k1/8/8/2q5/8/4n3/5P1P/6K1 w - - 0 1";

    @Test
    void rookLineOfSightWithoutLuftTriggersPenalty() {
        BitBoard board = FEN.translateFENtoBitBoard(ROOK_PRESSURE_FEN);
        KingSafetyModule module = new KingSafetyModule();
        KingSafetyModule.KingSafetyView view = module.getView(board);

        assertEquals(Tuning.backrankWeaknessMidgamePenalty(), view.whiteBackrankWeakness().midgame());
        assertEquals(Tuning.backrankWeaknessEndgamePenalty(), view.whiteBackrankWeakness().endgame());
    }

    @Test
    void defendedEscapeSquareSuppressesPenalty() {
        BitBoard board = FEN.translateFENtoBitBoard(BLOCKED_FILE_FEN);
        KingSafetyModule module = new KingSafetyModule();
        KingSafetyModule.KingSafetyView view = module.getView(board);

        assertEquals(0, view.whiteBackrankWeakness().midgame());
        assertEquals(0, view.whiteBackrankWeakness().endgame());
    }

    @Test
    void queenCanAlignOnOpenFileCountsAsThreat() {
        BitBoard board = FEN.translateFENtoBitBoard(QUEEN_ALIGNMENT_FEN);
        KingSafetyModule module = new KingSafetyModule();
        KingSafetyModule.KingSafetyView view = module.getView(board);

        assertEquals(Tuning.backrankWeaknessMidgamePenalty(), view.whiteBackrankWeakness().midgame());
        assertEquals(Tuning.backrankWeaknessEndgamePenalty(), view.whiteBackrankWeakness().endgame());
    }

    @Test
    void backrankEvaluationRemainsMicroBenchmarkFriendly() {
        BitBoard board = FEN.translateFENtoBitBoard(ROOK_PRESSURE_FEN);
        KingSafetyModule module = new KingSafetyModule();

        assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
            for (int i = 0; i < 150_000; i++) {
                KingSafetyModule.KingSafetyView view = module.getView(board);
                assertTrue(view.whiteBackrankWeakness().midgame() <= 0);
            }
        });
    }
}

