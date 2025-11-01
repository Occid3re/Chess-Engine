package julius.game.chessengine.uci;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.ai.AI;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.syzygy.SyzygyMove;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TestSyzygyTablebaseService;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.utils.Score;
import julius.game.chessengine.tuning.AiTuning;
import org.junit.jupiter.api.Test;
import testsupport.DeterministicAiHelper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class UciSyzygyFlowTest {

    @Test
    void blackSyzygyWinIsBroadcast() throws Exception {
        String fen = "8/8/8/8/8/1kb1n3/8/2K5 b - - 31 16";

        ScenarioResult result = runSyzygyScenario(fen, OptionalInt.of(5));

        assertThat(result.tablebaseResult().wdl()).isEqualTo(SyzygyWdl.WIN);
        assertThat(result.tablebaseResult().dtz()).hasValue(5);
        assertThat(result.outputLines()).anyMatch(line -> line.startsWith("bestmove"));
        assertThat(result.probedFens()).contains(fen);
    }

    @Test
    void whiteSyzygyWinIsBroadcast() throws Exception {
        String fen = "3k4/p6p/8/8/8/4N3/5B2/3K4 w - - 0 1";

        ScenarioResult result = runSyzygyScenario(fen, OptionalInt.of(7));

        assertThat(result.tablebaseResult().wdl()).isEqualTo(SyzygyWdl.WIN);
        assertThat(result.tablebaseResult().dtz()).hasValue(7);
        assertThat(result.outputLines()).anyMatch(line -> line.startsWith("bestmove"));
        assertThat(result.probedFens()).contains(fen);
    }

    private ScenarioResult runSyzygyScenario(String fen, OptionalInt dtz) throws Exception {
        TestSyzygyTablebaseService service = createServiceWithContinuation(fen, dtz, OptionalInt.empty());

        try (ScoreTablebaseRestorer restorer = overrideScoreTablebase(service)) {
            Engine engine = new Engine();
            AiTuning tuning = AiTuning.builder()
                    .searchThreads(1)
                    .lazySmpThreads(1)
                    .rootParallelLimit(24)
                    .hashSizeMb(64)
                    .build();
            AI ai = new AI(engine, tuning, service);

            try (AutoCloseable single = DeterministicAiHelper.withSingleThread(ai);
                 AutoCloseable limit = DeterministicAiHelper.withShortTimeLimit(ai, 50)) {
                RecordingOutput output = new RecordingOutput();
                UciHandler handler = new UciHandler(service, engine, ai, output, () -> true);

                // Prime the engine so the board hash matches what the tablebase client expects.
                engine.importBoardFromFen(fen);

                assertThat(handler.handle("uci")).isTrue();
                assertThat(handler.handle("isready")).isTrue();
                assertThat(handler.handle("position fen " + fen)).isTrue();
                engine.getGameState().refreshScore(engine.getBitBoard());
                assertThat(handler.handle("go movetime 100"))
                        .withFailMessage(() -> String.join("\n", output.snapshot()))
                        .isTrue();

                waitForBestMove(output);
                handler.stop();

                engine.undoLastMove();
                service.probe(engine.getBitBoard());
                TablebaseResult result = engine.getLastTablebaseResult()
                        .orElseThrow(() -> new IllegalStateException("Expected tablebase result"));
                return new ScenarioResult(result, output.snapshot(), service.getProbedFens());
            }
        }
    }

    private TestSyzygyTablebaseService createServiceWithContinuation(String fen,
            OptionalInt dtz, OptionalInt dtm) {
        Engine planner = new Engine();
        planner.importBoardFromFen(fen);

        int recommendedMove = selectDeterministicMove(planner, fen);
        SyzygyMove recommended = toSyzygyMove(recommendedMove);

        planner.performMove(recommendedMove);
        String childFen = renderFen(planner);
        planner.undoLastMove();

        Map<String, SyzygyProbeResult> responses = new LinkedHashMap<>();
        responses.put(fen, new SyzygyProbeResult(SyzygyWdl.WIN, dtz, dtm, Optional.of(recommended)));
        responses.put(childFen, new SyzygyProbeResult(SyzygyWdl.LOSS, OptionalInt.empty(), OptionalInt.empty(), Optional.empty()));
        return TestSyzygyTablebaseService.fromResponses(responses);
    }

    private int selectDeterministicMove(Engine engine, String fen) {
        IntArrayList legal = engine.getAllLegalMoves();
        if (legal.isEmpty()) {
            throw new IllegalStateException("No legal moves available for FEN: " + fen);
        }
        return legal.getInt(0);
    }

    private SyzygyMove toSyzygyMove(int move) {
        int from = MoveHelper.deriveFromIndex(move);
        int to = MoveHelper.deriveToIndex(move);
        int promo = MoveHelper.derivePromotionPieceTypeBits(move);
        return new SyzygyMove(from, to, promo);
    }

    private String renderFen(Engine engine) {
        return FEN.translateBoardToFEN(engine.getBitBoard(), engine.getGameState()).getRenderBoard();
    }

    private void waitForBestMove(RecordingOutput output) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (output.snapshot().stream().anyMatch(line -> line.startsWith("bestmove"))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for bestmove.\nOutput:\n" + String.join("\n", output.snapshot()));
    }

    private static final class RecordingOutput implements Consumer<String> {

        private final CopyOnWriteArrayList<String> lines = new CopyOnWriteArrayList<>();

        @Override
        public void accept(String line) {
            lines.add(line);
        }

        List<String> snapshot() {
            return List.copyOf(lines);
        }
    }

    private record ScenarioResult(TablebaseResult tablebaseResult, List<String> outputLines, List<String> probedFens) {
    }

    private ScoreTablebaseRestorer overrideScoreTablebase(TestSyzygyTablebaseService service) {
        if (service == null) {
            throw new IllegalArgumentException("service");
        }
        SyzygyTablebaseService previous = Score.getTablebaseService();
        Score.setTablebaseService(service);
        return new ScoreTablebaseRestorer(previous);
    }

    private static final class ScoreTablebaseRestorer implements AutoCloseable {

        private final SyzygyTablebaseService previous;
        private boolean closed;

        private ScoreTablebaseRestorer(SyzygyTablebaseService previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (previous == null) {
                Score.clearTablebaseService();
            } else {
                Score.setTablebaseService(previous);
            }
            closed = true;
        }
    }
}
