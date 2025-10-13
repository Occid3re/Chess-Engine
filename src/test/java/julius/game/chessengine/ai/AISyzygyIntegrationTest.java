package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.syzygy.TestSyzygyTablebaseService;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AISyzygyIntegrationTest {

    @Test
    void alphaBetaReturnsExactScoreFromTablebase() throws Exception {
        Engine engine = new Engine();
        String fen = "6k1/8/8/8/8/8/5Q2/6K1 w - - 0 1";
        engine.importBoardFromFen(fen);

        SyzygyProbeResult probe = new SyzygyProbeResult(SyzygyWdl.WIN, OptionalInt.of(3), OptionalInt.empty());
        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(Map.of(fen, probe));

        try (AutoCloseable restorer = overrideScoreTablebase(service)) {
            AI ai = new AI(engine, service);
            Engine simulation = engine.createSimulation();

            Method alphaBeta = AI.class.getDeclaredMethod("alphaBeta", Engine.class, int.class, double.class,
                    double.class, boolean.class, long.class, int.class, int.class, int.class);
            alphaBeta.setAccessible(true);

            double score = (double) alphaBeta.invoke(ai, simulation, 1,
                    Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                    simulation.whitesTurn(), Long.MAX_VALUE, -1, 0, 0);

            double expected = Score.tablebaseToEvaluation(TablebaseResult.from(probe));
            assertThat(score).isEqualTo(expected);

            ai.shutdown();
        }
    }

    @Test
    void determineTablebaseBestMovePicksWinningChild() throws Exception {
        Engine engine = new Engine();
        String fen = "6k1/8/8/8/8/8/5Q2/6K1 w - - 0 1";
        engine.importBoardFromFen(fen);

        IntArrayList legalMoves = engine.getAllLegalMoves();
        Map<String, SyzygyProbeResult> responses = new HashMap<>();
        responses.put(fen, new SyzygyProbeResult(SyzygyWdl.WIN, OptionalInt.of(5), OptionalInt.empty()));

        for (int i = 0; i < legalMoves.size(); i++) {
            int move = legalMoves.getInt(i);
            engine.performMove(move);
            String childFen = FEN.translateBoardToFEN(engine.getBitBoard(), engine.getGameState()).getRenderBoard();
            SyzygyWdl childResult = childFen.contains("5QK1") ? SyzygyWdl.LOSS : SyzygyWdl.DRAW;
            responses.put(childFen, new SyzygyProbeResult(childResult, OptionalInt.of(1), OptionalInt.empty()));
            engine.undoLastMove();
        }

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(responses);

        try (AutoCloseable restorer = overrideScoreTablebase(service)) {
            AI ai = new AI(engine, service);
            Engine simulation = engine.createSimulation();

            Method determine = AI.class.getDeclaredMethod("determineTablebaseBestMove", Engine.class, boolean.class);
            determine.setAccessible(true);

            int bestMove = (int) determine.invoke(ai, simulation, simulation.whitesTurn());

            Set<String> winningChildren = responses.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(fen))
                    .filter(entry -> entry.getValue().wdl() == SyzygyWdl.LOSS)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());

            assertThat(winningChildren)
                    .describedAs("expected at least one tablebase child to be marked as winning")
                    .isNotEmpty();

            Engine verifier = engine.createSimulation();
            verifier.performMove(bestMove);
            String bestChildFen = FEN.translateBoardToFEN(verifier.getBitBoard(), verifier.getGameState()).getRenderBoard();

            assertThat(winningChildren)
                    .describedAs("tablebase best move should correspond to a child scored as a forced win")
                    .contains(bestChildFen);

            ai.shutdown();
        }
    }

    private AutoCloseable overrideScoreTablebase(TestSyzygyTablebaseService service) {
        SyzygyTablebaseService previous = Score.getTablebaseService();
        Score.setTablebaseService(service);
        return () -> {
            if (previous == null) {
                Score.clearTablebaseService();
            } else {
                Score.setTablebaseService(previous);
            }
        };
    }
}
