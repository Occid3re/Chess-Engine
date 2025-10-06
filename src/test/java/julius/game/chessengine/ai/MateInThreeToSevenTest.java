package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.engine.GameStateEnum;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import java.util.ArrayList;
import java.util.List;

import testsupport.TestReportWriter;

/**
 * Focused regression covering deeper forced mates (N = 3..7).
 *
 * <p>The existing {@link MateSearchTest} already verifies a broad mix of
 * puzzles and time budgets. This suite narrows in on the tricky range where
 * check extensions and mate-distance propagation are most critical. Each FEN
 * encodes a known mate in N and we simply assert that the engine reaches the
 * expected game termination within {@code 2N-1} plies while driving both
 * colours via {@link AI#startAutoPlay(boolean, boolean)}.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MateInThreeToSevenTest {

    private final List<MateObservation> observations = new ArrayList<>();

    private Stream<Object[]> matePuzzles() {
        return Stream.of(
                // mate in 3 -> BLACK_WON
                new Object[]{"2r3k1/p4p2/3Rp2p/1p2P1pK/8/1P4P1/P3Q2P/1q6 b - - 0 1", 3, GameStateEnum.BLACK_WON},

                // mate in 4 -> WHITE_WON
                new Object[]{"4k2r/1R3R2/p3p1pp/4b3/1BnNr3/8/P1P5/5K2 w - - 1 1", 4, GameStateEnum.WHITE_WON},

                // mate in 5 -> WHITE_WON
                new Object[]{"2q1nk1r/4Rp2/1ppp1P2/6Pp/3p1B2/3P3P/PPP1Q3/6K1 w - - 0 1", 5, GameStateEnum.WHITE_WON},

                // mate in 6 -> WHITE_WON
                new Object[]{"8/8/p1p2p1p/k1P2Pp1/P5P1/1K5P/8/8 w - - 1 0", 6, GameStateEnum.WHITE_WON},

                // mate in 7 -> WHITE_WON
                new Object[]{"1rb2r1k/pp2B1p1/4p2p/2ppN2Q/P3p3/2P2N2/1P3RPP/1R4K1 b - - 1 24", 7, GameStateEnum.WHITE_WON}
        );
    }

    @ParameterizedTest(name = "Mate in {1} for FEN {0}")
    @MethodSource("matePuzzles")
    void detectsMateInRange(String fen, int mateInMoves, GameStateEnum expectedWinner) {
        Engine engine = new Engine();
        engine.importBoardFromFen(fen);

        AI ai = new AI(engine);
        final long timeLimitMs = 1000L;
        ai.setTimeLimit(timeLimitMs);

        // Drive both colours with the engine so it follows the forced line
        ai.startAutoPlay(true, true);

        final int maxPlies = Math.max(1, 2 * mateInMoves - 1);
        final long slackMs = 250L;
        final long perPlyBudgetMs = timeLimitMs + slackMs;
        final long absoluteDeadlineMs = System.currentTimeMillis() + perPlyBudgetMs * maxPlies;

        int observedPlies = 0;
        long lastHash = engine.getBoardStateHash();

        while (System.currentTimeMillis() < absoluteDeadlineMs
                && observedPlies < maxPlies
                && !engine.getGameState().isGameOver()) {

            try {
                TimeUnit.MILLISECONDS.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            long currentHash = engine.getBoardStateHash();
            if (currentHash != lastHash) {
                lastHash = currentHash;
                observedPlies++;
                ai.updateBoardStateHash();
            }
        }

        ai.stopCalculation();

        GameState state = engine.getGameState();
        MateObservation observation = new MateObservation(
                fen,
                mateInMoves,
                expectedWinner,
                state.getState(),
                observedPlies,
                maxPlies,
                timeLimitMs,
                state.getState() == expectedWinner && observedPlies <= maxPlies
        );
        observations.add(observation);

        String failInfo = String.format(
                "FEN='%s', mateIn=%d, plies=%d/%d, expected=%s, actual=%s",
                fen, mateInMoves, observedPlies, maxPlies, expectedWinner, state.getState()
        );

        Assertions.assertEquals(expectedWinner, state.getState(), failInfo);
        Assertions.assertTrue(observedPlies <= maxPlies, "Exceeded ply cap: " + failInfo);
    }

    @AfterAll
    void writeDiagnostics() {
        if (observations.isEmpty()) {
            return;
        }
        List<String> jsonLines = observations.stream()
                .map(MateObservation::toJsonLine)
                .toList();
        List<String> textLines = observations.stream()
                .map(MateObservation::toHumanReadable)
                .toList();

        TestReportWriter.writeLines("mate-in-three-to-seven.jsonl", jsonLines);
        TestReportWriter.writeLines("mate-in-three-to-seven.txt", textLines);
    }

    private record MateObservation(
            String fen,
            int mateIn,
            GameStateEnum expected,
            GameStateEnum actual,
            int plies,
            int maxPlies,
            long timeLimitMs,
            boolean success
    ) {
        String toHumanReadable() {
            return String.format(
                    "FEN=%s | mateIn=%d | plies=%d/%d | expected=%s | actual=%s | success=%s",
                    fen, mateIn, plies, maxPlies, expected, actual, success
            );
        }

        String toJsonLine() {
            return "{"
                    + "\"fen\":\"" + escape(fen) + "\"," 
                    + "\"mateIn\":" + mateIn + ','
                    + "\"expected\":\"" + expected + "\"," 
                    + "\"actual\":\"" + actual + "\"," 
                    + "\"plies\":" + plies + ','
                    + "\"maxPlies\":" + maxPlies + ','
                    + "\"timeLimitMs\":" + timeLimitMs + ','
                    + "\"success\":" + success
                    + "}";
        }

        private static String escape(String input) {
            return input.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}

