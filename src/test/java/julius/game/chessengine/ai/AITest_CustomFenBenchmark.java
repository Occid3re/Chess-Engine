package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.AiTuning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import testsupport.DeterministicAiHelper;
import testsupport.DiagnosticSearchProbe;
import testsupport.TestLoggingExtension;

import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(TestLoggingExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AITest_CustomFenBenchmark {

    private static Stream<Arguments> benchmarkPositions() {
        return Stream.of(
                Arguments.of(
                        "Classical opening baseline",
                        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                        4
                ),
                Arguments.of(
                        "Tactical middlegame pressure",
                        "r2q1rk1/pp2bppp/2n1pn2/3p4/3P4/2NBPN2/PPQ2PPP/R3K2R w KQ - 0 1",
                        5
                )
        );
    }

    @BeforeEach
    void ensureLocale() {
        Locale.setDefault(Locale.ROOT);
    }

    @DisplayName("Custom FEN search benchmark with diagnostic logging")
    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("benchmarkPositions")
    void benchmarkPosition(String description, String fen, int depth) throws Exception {
        Engine engine = new Engine();
        engine.importBoardFromFen(fen);

        AiTuning tuning = AiTuning.builder()
                .searchThreads(1)
                .lazySmpThreads(1)
                .hashSizeMb(64)
                .maxDepth(depth)
                .timeLimitMillis(2500)
                .nullMovePruning(true)
                .build();

        DiagnosticSearchProbe ai = new DiagnosticSearchProbe(engine, tuning);

        try (AutoCloseable ignore = DeterministicAiHelper.withSingleThread(ai)) {
            System.out.printf(Locale.ROOT, "Running benchmark: %s%n", description);
            long start = System.nanoTime();
            MoveAndScore result = ai.searchBestMoveBlocking(2500);
            Duration wallClock = Duration.ofNanos(System.nanoTime() - start);

            assertNotNull(result, "Search should produce a best move");
            assertFalse(ai.getDepthTraces().isEmpty(),
                    "The diagnostic probe must record at least one search iteration");

            int deepest = ai.getDepthTraces().stream()
                    .map(DiagnosticSearchProbe.DepthTrace::depth)
                    .max(Comparator.naturalOrder())
                    .orElse(0);
            assertTrue(deepest >= depth,
                    () -> String.format(Locale.ROOT, "Expected to reach depth %d but only saw %d", depth, deepest));

            String report = ai.buildBenchmarkReport(fen, depth, result, wallClock);
            assertNotNull(report, "Benchmark report should never be null");

            System.out.println(report);
        }
    }
}
