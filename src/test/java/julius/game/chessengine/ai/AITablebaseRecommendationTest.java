package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.syzygy.SyzygyMove;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.syzygy.TestSyzygyTablebaseService;
import julius.game.chessengine.tuning.AiTuning;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        simulation.getGameState().setLastTablebaseResult(recommendation);
        int direct = invokeDetermineTablebaseBestMove(ai, simulation, recommendation, true);
        assertThat(MoveHelper.convertIndexToString(MoveHelper.deriveFromIndex(direct))).isEqualTo("b5");
        assertThat(MoveHelper.convertIndexToString(MoveHelper.deriveToIndex(direct))).isEqualTo("b6");

        MoveAndScore best = ai.searchBestMoveBlocking(100);
        ai.shutdown();

        assertThat(best).as("tablebase recommendation should be respected").isNotNull();
        assertThat(MoveHelper.convertIndexToString(MoveHelper.deriveFromIndex(best.getMove()))).isEqualTo("b5");
        assertThat(MoveHelper.convertIndexToString(MoveHelper.deriveToIndex(best.getMove()))).isEqualTo("b6");
    }

    @Test
    void prefersContinuationWithDerivedFasterMate() {
        Engine engine = new Engine();
        engine.importBoardFromFen("k7/3Q4/2K5/8/8/8/8/8 w - - 0 1");

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(
                createRecommendedLineResponses());

        TablebaseResult parent = new TablebaseResult(
                SyzygyWdl.WIN,
                OptionalInt.of(1),
                OptionalInt.empty(),
                Optional.empty()
        );
        engine.getGameState().setLastTablebaseResult(parent);

        AiTuning tuning = AiTuning.builder()
                .searchThreads(1)
                .lazySmpThreads(1)
                .hashSizeMb(16)
                .maxDepth(6)
                .timeLimitMillis(200)
                .build();

        AI ai = new AI(engine, tuning, service);
        Engine simulation = engine.createSimulation();
        simulation.getGameState().setLastTablebaseResult(parent);

        IntArrayList legalMoves = engine.getAllLegalMoves();
        int fastMove = findMove(legalMoves, "d7", "c8");
        int slowMove = findMove(legalMoves, "d7", "d5");

        TablebaseResult fastContinuation = evaluateContinuation(ai, simulation, fastMove);
        TablebaseResult slowContinuation = evaluateContinuation(ai, simulation, slowMove);

        assertThat(fastContinuation.dtm()).hasValue(2);
        assertThat(slowContinuation.dtm()).hasValue(6);

        simulation.getGameState().setLastTablebaseResult(parent);
        int selected = invokeDetermineTablebaseBestMove(ai, simulation, parent, true);
        ai.shutdown();

        assertThat(selected).isEqualTo(fastMove);
    }

    private int invokeDetermineTablebaseBestMove(AI ai, Engine simulation, TablebaseResult result, boolean parentIsWhite) {
        try {
            var method = AI.class.getDeclaredMethod("determineTablebaseBestMove", Engine.class, TablebaseResult.class, boolean.class);
            method.setAccessible(true);
            return (int) method.invoke(ai, simulation, result, parentIsWhite);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Failed to invoke determineTablebaseBestMove", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private TablebaseResult evaluateContinuation(AI ai, Engine simulation, int move) {
        try {
            Method method = AI.class.getDeclaredMethod("evaluateTablebaseContinuation", Engine.class, int.class);
            method.setAccessible(true);
            Optional<TablebaseResult> result = ((Optional<?>) method.invoke(ai, simulation, move))
                    .map(continuation -> {
                        try {
                            Method resultAccessor = continuation.getClass().getDeclaredMethod("result");
                            resultAccessor.setAccessible(true);
                            return (TablebaseResult) resultAccessor.invoke(continuation);
                        } catch (ReflectiveOperationException ex) {
                            throw new AssertionError("Failed to extract tablebase result", ex);
                        }
                    });
            return result.orElseThrow(() -> new AssertionError("Expected tablebase continuation"));
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Failed to invoke evaluateTablebaseContinuation", ex);
        }
    }

    private Map<String, SyzygyProbeResult> createRecommendedLineResponses() {
        Map<String, SyzygyProbeResult> responses = new HashMap<>();
        String rootFen = "k7/3Q4/2K5/8/8/8/8/8 w - - 0 1";
        registerRecommendedLine(responses, rootFen, List.of(
                new MoveSpec("d7", "c8"),
                new MoveSpec("a8", "a7"),
                new MoveSpec("c8", "b7")
        ));
        registerRecommendedLine(responses, rootFen, List.of(
                new MoveSpec("d7", "d5"),
                new MoveSpec("a8", "a7"),
                new MoveSpec("d5", "e6"),
                new MoveSpec("a7", "a8"),
                new MoveSpec("e6", "d7"),
                new MoveSpec("a8", "b8"),
                new MoveSpec("d7", "b7")
        ));
        return responses;
    }

    private void registerRecommendedLine(Map<String, SyzygyProbeResult> responses,
                                          String startFen,
                                          List<MoveSpec> moves) {
        Engine engine = new Engine();
        engine.importBoardFromFen(startFen);

        for (int i = 0; i < moves.size(); i++) {
            MoveSpec move = moves.get(i);
            applyMove(engine, move);

            String fen = engine.translateBoardToFen().getRenderBoard();
            Optional<SyzygyMove> recommendation = (i + 1 < moves.size())
                    ? Optional.of(toSyzygyMove(moves.get(i + 1)))
                    : Optional.empty();
            SyzygyWdl wdl = engine.whitesTurn() ? SyzygyWdl.WIN : SyzygyWdl.LOSS;
            OptionalInt dtz = recommendation.isPresent() ? OptionalInt.of(1) : OptionalInt.of(0);

            responses.put(fen, new SyzygyProbeResult(wdl, dtz, OptionalInt.empty(), recommendation));
        }
    }

    private void applyMove(Engine engine, MoveSpec move) {
        IntArrayList legal = engine.getAllLegalMoves();
        int encoded = findMove(legal, move.from(), move.to());
        if (encoded < 0) {
            throw new AssertionError("Move " + move + " was not legal for FEN "
                    + engine.translateBoardToFen().getRenderBoard());
        }
        engine.performMove(encoded);
    }

    private static SyzygyMove toSyzygyMove(MoveSpec move) {
        int from = MoveHelper.convertStringToIndex(move.from());
        int to = MoveHelper.convertStringToIndex(move.to());
        return new SyzygyMove(from, to, move.promotion());
    }

    private static int findMove(IntArrayList legalMoves, String from, String to) {
        int fromIndex = MoveHelper.convertStringToIndex(from);
        int toIndex = MoveHelper.convertStringToIndex(to);
        for (int i = 0; i < legalMoves.size(); i++) {
            int candidate = legalMoves.getInt(i);
            if (MoveHelper.deriveFromIndex(candidate) == fromIndex
                    && MoveHelper.deriveToIndex(candidate) == toIndex) {
                return candidate;
            }
        }
        return -1;
    }

    private record MoveSpec(String from, String to, int promotion) {
        MoveSpec(String from, String to) {
            this(from, to, 0);
        }
    }
}

