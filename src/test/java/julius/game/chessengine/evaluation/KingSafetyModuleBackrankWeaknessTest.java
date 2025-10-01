package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.tuning.NumericTuningParameters;
import julius.game.chessengine.tuning.Tuning;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KingSafetyModuleBackrankWeaknessTest {

    private static final Map<String, Double> BACKRANK_ONLY_TUNING = Map.ofEntries(
            entry("kingSafety.missingPawnShieldPenalty", 0.0),
            entry("kingSafety.halfOpenFilePenalty", 0.0),
            entry("kingSafety.openFilePenalty", 0.0),
            entry("kingSafety.defenderBonus", 0.0),
            entry("kingSafety.queenAttackedPenalty", 0.0),
            entry("kingSafety.attackWeightPawn", 0.0),
            entry("kingSafety.attackWeightKnight", 0.0),
            entry("kingSafety.attackWeightBishop", 0.0),
            entry("kingSafety.attackWeightRook", 0.0),
            entry("kingSafety.attackWeightQueen", 0.0),
            entry("kingSafety.backrankWeaknessMidgamePenalty", -100.0),
            entry("kingSafety.backrankWeaknessEndgamePenalty", -50.0)
    );

    @Test
    void appliesBackrankWeaknessPenaltyWhenAllThreatConditionsMet() throws Exception {
        BitBoard board = FEN.translateFENtoBitBoard("4k1r1/8/8/8/8/8/5P1P/6K1 w - - 0 1");

        try (AutoCloseable scope = NumericTuningParameters.use(BACKRANK_ONLY_TUNING)) {
            Tuning.refresh();
            KingSafetyModule module = new KingSafetyModule();
            KingSafetyModule.KingSafetyView view = module.evaluate(board);
            assertEquals(Tuning.backrankWeaknessMidgamePenalty(),
                    view.whiteBackrankWeakness().midgame(),
                    "Backrank threat should contribute the configured midgame penalty");
            assertEquals(Tuning.backrankWeaknessEndgamePenalty(),
                    view.whiteBackrankWeakness().endgame(),
                    "Backrank threat should contribute the configured endgame penalty");
        } finally {
            Tuning.refresh();
        }
    }

    @Test
    void noPenaltyWhenSafeLuftExists() throws Exception {
        BitBoard board = FEN.translateFENtoBitBoard("4k1r1/8/8/8/8/8/7P/6K1 w - - 0 1");

        try (AutoCloseable scope = NumericTuningParameters.use(BACKRANK_ONLY_TUNING)) {
            Tuning.refresh();
            KingSafetyModule module = new KingSafetyModule();
            KingSafetyModule.KingSafetyView view = module.evaluate(board);
            assertEquals(0, view.whiteBackrankWeakness().midgame(),
                    "Safe king luft should suppress backrank penalties");
            assertEquals(0, view.whiteBackrankWeakness().endgame(),
                    "Safe king luft should suppress backrank penalties");
        } finally {
            Tuning.refresh();
        }
    }

    @Test
    void noPenaltyWhenSliderLineIsBlocked() throws Exception {
        BitBoard board = FEN.translateFENtoBitBoard("4k1r1/8/8/8/8/8/5PPP/6K1 w - - 0 1");

        try (AutoCloseable scope = NumericTuningParameters.use(BACKRANK_ONLY_TUNING)) {
            Tuning.refresh();
            KingSafetyModule module = new KingSafetyModule();
            KingSafetyModule.KingSafetyView view = module.evaluate(board);
            assertEquals(0, view.whiteBackrankWeakness().midgame(),
                    "Blocked files should prevent backrank weakness penalties");
            assertEquals(0, view.whiteBackrankWeakness().endgame(),
                    "Blocked files should prevent backrank weakness penalties");
        } finally {
            Tuning.refresh();
        }
    }
}

