package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.AiTuning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import testsupport.DiagnosticSearchProbe;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Produces a verbose diagnostic trace for mate-threat positions where the engine must
 * find a single defensive resource (e.g. {@code f4} in the supplied position).
 *
 * <p>The diagnostics record move ordering, alpha/beta bounds, elapsed time and node
 * counts for every root move considered at each iterative-deepening depth. The report
 * also inspects the transposition table afterwards so we can confirm whether critical
 * moves (like {@code f4}) were searched, pruned or skipped due to time pressure.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AITest_MateThreatDiagnostics {

    private static Stream<Object[]> mateThreatScenarios() {
        return Stream.of(
                new Object[]{
                        "rnb1k2r/2bp3p/3qpp1n/P5p1/Q1P3P1/PN6/4BPNP/R4RK1 w - - 1 24",
                        List.of("f4")
                },
                new Object[]{
                        "rn2kb1r/pp2pppp/5q2/1p6/2b3Q1/4B2P/PP3PP1/RN2K1NR b KQkq - 1 12",
                        List.of("Nc6", "e6")
                },
                new Object[]{
                        "r1bqk2r/ppp2ppp/2n1p3/3pP3/3P4/2bB1N2/P1P2PPP/R1BQ1RK1 w kq - 0 9",
                        List.of("Rb1")
                }
        );
    }

    @BeforeEach
    void ensureLocale() {
        Locale.setDefault(Locale.ROOT);
    }

    @DisplayName("Detailed diagnostic trace for mate-in-one defence scenarios")
    @ParameterizedTest(name = "Scenario {index}: {0}")
    @MethodSource("mateThreatScenarios")
    void analyseMateThreat(String fen, List<String> lifesavingMoves) throws Exception {
        Engine engine = new Engine();
        engine.importBoardFromFen(fen);

        AiTuning tuning = AiTuning.builder()
                .searchThreads(1)
                .lazySmpThreads(1)
                .hashSizeMb(64)
                .maxDepth(14)
                .timeLimitMillis(1000)
                .nullMovePruning(true)
                .build();

        DiagnosticSearchProbe ai = new DiagnosticSearchProbe(engine, tuning);

        long start = System.nanoTime();
        MoveAndScore result = ai.searchBestMoveBlocking(1500);
        Duration wallClock = Duration.ofNanos(System.nanoTime() - start);

        assertFalse(ai.getDepthTraces().isEmpty(),
                "The diagnostic AI must record at least one search iteration");

        String report = ai.buildMateThreatReport(fen, lifesavingMoves, result, wallClock);
        assertNotNull(report, "The diagnostic report should never be null");

        // Emit to STDOUT so the engineer can inspect the detailed breakdown when the test runs.
        System.out.println(report);
    }
}
