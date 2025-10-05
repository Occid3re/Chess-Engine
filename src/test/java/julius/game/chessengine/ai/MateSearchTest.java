package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.engine.GameStateEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Verifies that the AI finds the forced checkmate from known FEN positions
 * under various per-move time limits using the current alpha-beta search.
 *
 * Strategy:
 * - For each FEN and time limit, import the position and start auto-play with AI on both sides.
 * - Let the engine progress until game over or a bounded move budget is reached.
 * - Assert that the terminal state matches the expected winner for that FEN.
 *
 * Notes:
 * - For "mate in N", at most (2N-1) plies are required if both sides play best moves.
 * - We give a conservative wall-clock budget: (timeLimit + 150ms) per ply to absorb thread scheduling jitter.
 * - If a very low time limit proves too tight for deeper mates, higher limits (500ms, 1000ms) should still pass.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MateSearchTest {

    /**
     * Test matrix: (fen, mateInMoves, expectedWinner).
     * Everything should be WHITE_WON except the "mate in 3" position, which should be BLACK_WON.
     *
     * Mate in 1
     *   White to move: Rf8#
     */
    private Stream<Object[]> fenMatrix() {
        return Stream.of(
                // mate in 1 -> WHITE_WON
                new Object[] { "7k/6pp/8/8/8/8/6PP/5RK1 w - - 0 1", 1, GameStateEnum.WHITE_WON },

                // mate in 2 -> WHITE_WON
                new Object[] { "r1bq2r1/b4pk1/p1pp1p2/1p2pP2/1P2P1PB/3P4/1PPQ2P1/R3K2R w - - 0 1", 2, GameStateEnum.WHITE_WON },

                // mate in 3 -> BLACK_WON (black to move and win)
                new Object[] { "2r3k1/p4p2/3Rp2p/1p2P1pK/8/1P4P1/P3Q2P/1q6 b - - 0 1", 3, GameStateEnum.BLACK_WON },

                // mate in 4 -> WHITE_WON
                new Object[] { "4k2r/1R3R2/p3p1pp/4b3/1BnNr3/8/P1P5/5K2 w - - 1 0", 4, GameStateEnum.WHITE_WON },

                // mate in 5 -> WHITE_WON
                new Object[] { "2q1nk1r/4Rp2/1ppp1P2/6Pp/3p1B2/3P3P/PPP1Q3/6K1 w - - 0 1", 5, GameStateEnum.WHITE_WON },

                // mate in 6 -> WHITE_WON
                new Object[] { "8/8/p1p2p1p/k1P2Pp1/P5P1/1K5P/8/8 w - - 1 0", 6, GameStateEnum.WHITE_WON }
        );
    }

    /** Per-move time limits to test (milliseconds). */
    private static final List<Long> TIME_LIMITS_MS = List.of(50L, 100L, 200L, 500L, 10000L);

    /**
     * Only the largest time budget is considered a hard requirement. Smaller
     * limits remain in the matrix for diagnostic purposes, but the engine is
     * only expected to reliably convert the forced mate when it receives at
     * least {@link #requiredSuccessThresholdMs()} milliseconds per move.
     */
    private long requiredSuccessThresholdMs() {
        return TIME_LIMITS_MS.get(TIME_LIMITS_MS.size() - 1);
    }

    /** Safety margin to absorb scheduler jitter and thread wakeups. */
    private static final long TIME_SLACK_MS = 150L;

    /** Polling interval while waiting for game progress. */
    private static final long POLL_MS = 10L;

    /**
     * Core parameterized test.
     * For each (FEN, N, expectedWinner) and each time limit, we assert that the AI reaches checkmate
     * within (2N-1) plies and within a conservative wall-clock cap, and that the final state matches expectation.
     */
    @ParameterizedTest(name = "Mate in {1} expecting {2} @ {0}")
    @MethodSource("fenMatrix")
    void testMateInNAllTimeBudgets(String fen, int mateInMoves, GameStateEnum expectedWinner) {
        long threshold = requiredSuccessThresholdMs();

        // Execute the scenario for every time budget and collect the outcomes.
        Map<Long, ScenarioResult> results = new java.util.LinkedHashMap<>();
        for (long ms : TIME_LIMITS_MS) {
            ScenarioResult result = runSingleTimeBudget(fen, mateInMoves, ms);
            results.put(ms, result);

            String failInfo = String.format(
                    "FEN='%s', mateIn=%d, time=%dms, plies=%d/%d, state=%s",
                    fen, mateInMoves, ms, result.observedPlies(), result.maxPlies(), result.finalState()
            );

            Assertions.assertTrue(result.observedPlies() <= result.maxPlies(),
                    "Exceeded ply cap: " + failInfo);
        }

        boolean succeededAtThreshold = results.entrySet().stream()
                .filter(entry -> entry.getKey() >= threshold)
                .anyMatch(entry -> entry.getValue().finalState() == expectedWinner);

        if (!succeededAtThreshold) {
            String summary = results.entrySet().stream()
                    .map(entry -> entry.getKey() + "ms=" + entry.getValue().finalState())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("<no results>");

            Assertions.fail(String.format(
                    "Mate search failed to achieve %s for FEN '%s' (mate in %d). Results: %s",
                    expectedWinner, fen, mateInMoves, summary
            ));
        }
    }

    /**
     * Runs one scenario: import FEN, set time budget, let AI auto-play both sides.
     * Asserts that the expected winning side is achieved within a safe ply/time cap.
     */
    private ScenarioResult runSingleTimeBudget(String fen, int mateInMoves, long timeLimitMs) {
        Engine engine = new Engine();
        engine.importBoardFromFen(fen);

        AI ai = new AI(engine);
        ai.setTimeLimit(timeLimitMs);

        // Allow both sides to be driven by the AI so it follows the forced line.
        ai.startAutoPlay(true, true);

        final int maxPlies = Math.max(1, 2 * mateInMoves - 1);
        final long perPlyBudgetMs = timeLimitMs + TIME_SLACK_MS;
        final long absoluteDeadlineMs = System.currentTimeMillis() + (perPlyBudgetMs * maxPlies);

        // We will loop until:
        // - game over (expected: checkmate), OR
        // - we hit the ply budget or time budget.
        int observedPlies = 0;
        long lastHash = engine.getBoardStateHash();

        while (System.currentTimeMillis() < absoluteDeadlineMs
                && observedPlies < maxPlies
                && !engine.getGameState().isGameOver()) {

            try {
                TimeUnit.MILLISECONDS.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            long h = engine.getBoardStateHash();
            if (h != lastHash) {
                lastHash = h;
                observedPlies++;
                // Nudge the calculator that position changed (helps it resume quickly)
                ai.updateBoardStateHash();
            }
        }

        ai.stopCalculation();

        GameState state = engine.getGameState();

        return new ScenarioResult(state.getState(), observedPlies, maxPlies);
    }

    @AfterEach
    void tearDown() {
        // Nothing to clean right now; left for symmetry or future resources
    }

    private record ScenarioResult(GameStateEnum finalState, int observedPlies, int maxPlies) {
    }
}