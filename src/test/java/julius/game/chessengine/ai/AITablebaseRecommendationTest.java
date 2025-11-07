package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.syzygy.SyzygyMove;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TestSyzygyTablebaseService;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.tuning.AiTuning;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AITablebaseRecommendationTest {

    @Test
    void prioritisesRecommendedSyzygyMoveWhenScoresAreTied() {
        Engine engine = new Engine();
        engine.importBoardFromFen("8/8/3k4/1P6/3P4/6K1/8/7B w - - 1 57");

        String parentFen = engine.translateBoardToFen().getRenderBoard();

        int fromIndex = MoveHelper.convertStringToIndex("b5");
        int toIndex = MoveHelper.convertStringToIndex("b6");
        int recommendedMove = findMatchingMove(engine, fromIndex, toIndex);

        SyzygyProbeResult parentProbe = new SyzygyProbeResult(
                SyzygyWdl.WIN,
                OptionalInt.of(1),
                OptionalInt.empty(),
                Optional.of(new SyzygyMove(fromIndex, toIndex, 0))
        );

        engine.performMove(recommendedMove);
        String childFen = engine.translateBoardToFen().getRenderBoard();
        engine.undoLastMove();

        SyzygyProbeResult childProbe = new SyzygyProbeResult(
                SyzygyWdl.LOSS,
                OptionalInt.of(0),
                OptionalInt.empty(),
                Optional.empty()
        );

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(Map.of(parentFen, parentProbe, childFen, childProbe));
        engine.getGameState().setLastTablebaseResult(TablebaseResult.from(parentProbe));

        AiTuning tuning = AiTuning.builder()
                .searchThreads(1)
                .lazySmpThreads(1)
                .hashSizeMb(16)
                .maxDepth(6)
                .timeLimitMillis(200)
                .build();

        AI ai = new AI(engine, tuning, service);
        Engine simulation = engine.createSimulation();
        int direct = invokeDetermineTablebaseBestMove(ai, engine, TablebaseResult.from(parentProbe));
        assertThat(MoveHelper.convertIndexToString(MoveHelper.deriveFromIndex(direct))).isEqualTo("b5");
        assertThat(MoveHelper.convertIndexToString(MoveHelper.deriveToIndex(direct))).isEqualTo("b6");

        MoveAndScore best = ai.searchBestMoveBlocking(100);
        ai.shutdown();

        assertThat(best).as("tablebase recommendation should be respected").isNotNull();
        assertThat(MoveHelper.convertIndexToString(MoveHelper.deriveFromIndex(best.getMove()))).isEqualTo("b5");
        assertThat(MoveHelper.convertIndexToString(MoveHelper.deriveToIndex(best.getMove()))).isEqualTo("b6");
    }

    @Test
    void avoidsImmediateThreefoldRepetitionWhenWinning() {
        Engine engine = new Engine();
        engine.importBoardFromFen("6K1/8/4B3/6P1/4P3/3P4/8/k7 w - - 19 69");

        int kh8 = findMatchingMove(engine,
                MoveHelper.convertStringToIndex("g8"), MoveHelper.convertStringToIndex("h8"));
        int g6 = findMatchingMove(engine,
                MoveHelper.convertStringToIndex("g5"), MoveHelper.convertStringToIndex("g6"));

        String parentFen = engine.translateBoardToFen().getRenderBoard();

        Engine simulation = engine.createSimulation();
        simulation.performMove(kh8);
        String kh8Fen = simulation.translateBoardToFen().getRenderBoard();
        long kh8Hash = simulation.getBoardStateHash();
        simulation.undoLastMove();

        simulation.performMove(g6);
        String g6Fen = simulation.translateBoardToFen().getRenderBoard();
        simulation.undoLastMove();

        engine.getGameState().getRepetition().put(kh8Hash, 2);

        SyzygyProbeResult parentProbe = new SyzygyProbeResult(
                SyzygyWdl.WIN,
                OptionalInt.of(4),
                OptionalInt.empty(),
                Optional.empty()
        );
        SyzygyProbeResult kh8Probe = new SyzygyProbeResult(
                SyzygyWdl.LOSS,
                OptionalInt.of(3),
                OptionalInt.empty(),
                Optional.empty()
        );
        SyzygyProbeResult g6Probe = new SyzygyProbeResult(
                SyzygyWdl.LOSS,
                OptionalInt.of(3),
                OptionalInt.empty(),
                Optional.empty()
        );

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(Map.of(
                parentFen, parentProbe,
                kh8Fen, kh8Probe,
                g6Fen, g6Probe
        ));

        engine.getGameState().setLastTablebaseResult(TablebaseResult.from(parentProbe));

        AiTuning tuning = AiTuning.builder()
                .searchThreads(1)
                .lazySmpThreads(1)
                .hashSizeMb(16)
                .maxDepth(6)
                .timeLimitMillis(200)
                .build();

        AI ai = new AI(engine, tuning, service);
        int chosen = invokeDetermineTablebaseBestMove(ai, engine, TablebaseResult.from(parentProbe));
        ai.shutdown();

        assertThat(MoveHelper.convertIndexToString(MoveHelper.deriveFromIndex(chosen))).isEqualTo("g5");
        assertThat(MoveHelper.convertIndexToString(MoveHelper.deriveToIndex(chosen))).isEqualTo("g6");
    }

    private int invokeDetermineTablebaseBestMove(AI ai, Engine engine, TablebaseResult result) {
        try {
            var method = AI.class.getDeclaredMethod("determineTablebaseBestMove", Engine.class, TablebaseResult.class, boolean.class);
            method.setAccessible(true);
            return (int) method.invoke(ai, engine, result, engine.whitesTurn());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Failed to invoke determineTablebaseBestMove", ex);
        }
    }

    private int findMatchingMove(Engine engine, int fromIndex, int toIndex) {
        IntArrayList legal = engine.getAllLegalMoves();
        for (int i = 0; i < legal.size(); i++) {
            int move = legal.getInt(i);
            if (MoveHelper.deriveFromIndex(move) == fromIndex && MoveHelper.deriveToIndex(move) == toIndex) {
                return move;
            }
        }
        throw new AssertionError("Expected legal move from " + fromIndex + " to " + toIndex);
    }
}

