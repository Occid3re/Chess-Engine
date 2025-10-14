package julius.game.chessengine.ai;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.syzygy.SyzygyMove;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.tuning.AiTuning;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class AITablebaseRecommendationTest {

    @Test
    void prioritisesRecommendedSyzygyMoveWhenScoresAreTied() {
        Engine engine = new Engine();
        engine.importBoardFromFen("8/8/3k4/1P6/3P4/6K1/8/7B w - - 1 57");

        int fromIndex = MoveHelper.convertStringToIndex("b5");
        int toIndex = MoveHelper.convertStringToIndex("b6");
        TablebaseResult recommendation = new TablebaseResult(
                SyzygyWdl.WIN,
                OptionalInt.of(1),
                OptionalInt.empty(),
                Optional.of(new SyzygyMove(fromIndex, toIndex, 0))
        );
        engine.getGameState().setLastTablebaseResult(recommendation);

        AiTuning tuning = AiTuning.builder()
                .searchThreads(1)
                .lazySmpThreads(1)
                .hashSizeMb(16)
                .maxDepth(6)
                .timeLimitMillis(200)
                .build();

        AI ai = new AI(engine, tuning, null);
        Engine simulation = engine.createSimulation();
        int direct = invokeDetermineTablebaseBestMove(ai, simulation, recommendation);
        assertThat(MoveHelper.convertIndexToString(MoveHelper.deriveFromIndex(direct))).isEqualTo("b5");
        assertThat(MoveHelper.convertIndexToString(MoveHelper.deriveToIndex(direct))).isEqualTo("b6");

        MoveAndScore best = ai.searchBestMoveBlocking(100);
        ai.shutdown();

        assertThat(best).as("tablebase recommendation should be respected").isNotNull();
        assertThat(MoveHelper.convertIndexToString(MoveHelper.deriveFromIndex(best.getMove()))).isEqualTo("b5");
        assertThat(MoveHelper.convertIndexToString(MoveHelper.deriveToIndex(best.getMove()))).isEqualTo("b6");
    }

    private int invokeDetermineTablebaseBestMove(AI ai, Engine simulation, TablebaseResult result) {
        try {
            var method = AI.class.getDeclaredMethod("determineTablebaseBestMove", Engine.class, TablebaseResult.class);
            method.setAccessible(true);
            return (int) method.invoke(ai, simulation, result);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Failed to invoke determineTablebaseBestMove", ex);
        }
    }
}

