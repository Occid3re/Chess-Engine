package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.AiTuning;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import testsupport.DeterministicAiHelper;
import testsupport.TestLoggingExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Log4j2
@ExtendWith(TestLoggingExtension.class)
class AITest_IterativeDeepeningAndWindows {

    @Test
    @DisplayName("Aspiration windows narrow at depth >=3 and fall back to full window after retries")
    void aspirationWindowFallsBackToFullWindow() throws Exception {
        TrackingAI ai = new TrackingAI(new Engine(), AiTuning.defaults());
        ai.setMaxDepth(4);
        try (AutoCloseable threads = DeterministicAiHelper.withSingleThread(ai);
             AutoCloseable time = DeterministicAiHelper.withShortTimeLimit(ai, 150)) {
            MoveAndScore result = ai.searchBestMoveBlocking(200);
            assertNotNull(result, "Search must yield a move even under forced retries");
        }

        List<TrackingAI.WindowInvocation> depthOne = ai.invocationsByDepth.getOrDefault(1, List.of());
        List<TrackingAI.WindowInvocation> depthTwo = ai.invocationsByDepth.getOrDefault(2, List.of());
        List<TrackingAI.WindowInvocation> depthThree = ai.invocationsByDepth.getOrDefault(3, List.of());

        log.info("Depth1 invocations: {}", depthOne);
        log.info("Depth2 invocations: {}", depthTwo);
        log.info("Depth3 invocations: {}", depthThree);

        assertFalse(depthOne.isEmpty(), "Depth 1 should execute at least once");
        assertFalse(depthTwo.isEmpty(), "Depth 2 should execute at least once");
        assertFalse(depthThree.isEmpty(), "Depth 3 should execute at least once");

        assertTrue(depthOne.stream().allMatch(TrackingAI.WindowInvocation::isFullWindow),
                "First depth must use the full alpha/beta window");
        assertTrue(depthTwo.stream().allMatch(TrackingAI.WindowInvocation::isFullWindow),
                "Second depth must use the full alpha/beta window");

        assertTrue(depthThree.stream().anyMatch(TrackingAI.WindowInvocation::isAspirationWindow),
                "Depth >=3 must start with an aspiration window");
        assertTrue(depthThree.stream().anyMatch(TrackingAI.WindowInvocation::isFullWindow),
                "Depth >=3 should fall back to the full window after retries");

        assertTrue(ai.fullWindowFallbackObserved,
                "Tracking AI should record at least one full-window retry after aspiration failures");
    }

    private static final class TrackingAI extends AI {
        private final Map<Integer, List<WindowInvocation>> invocationsByDepth = new HashMap<>();
        private int aspirationFailures;
        private boolean fullWindowFallbackObserved;

        private TrackingAI(Engine engine, AiTuning tuning) {
            super(engine, tuning);
        }

        @Override
        protected RootSearchResult searchRootMoves(Engine sim, SearchTask task, int depth, double alpha, double beta, SplittableRandom rng) {
            WindowInvocation invocation = new WindowInvocation(depth, alpha, beta);
            invocationsByDepth.computeIfAbsent(depth, d -> new ArrayList<>()).add(invocation);
            log.info("Depth {} invocation -> alpha={}, beta={}", depth, alpha, beta);

            if (depth >= 3 && invocation.isFullWindow()) {
                fullWindowFallbackObserved = true;
            }

            if (depth >= 3 && invocation.isAspirationWindow() && aspirationFailures < 4) {
                aspirationFailures++;
                // Return a score below alpha to simulate fail-low and trigger another iteration.
                return RootSearchResult.completed(new MoveAndScore(0, alpha - 25.0), NodeType.UPPERBOUND);
            }

            double score;
            if (Double.isInfinite(alpha) && Double.isInfinite(beta)) {
                score = 0.0;
            } else {
                score = Math.min(beta - 1.0, alpha + 1.0);
            }
            return RootSearchResult.completed(new MoveAndScore(0, score));
        }

        private record WindowInvocation(int depth, double alpha, double beta) {
            boolean isFullWindow() {
                return Double.isInfinite(alpha) && Double.isInfinite(beta);
            }

            boolean isAspirationWindow() {
                return !isFullWindow();
            }

            @Override
            public String toString() {
                return "WindowInvocation{" +
                        "depth=" + depth +
                        ", alpha=" + alpha +
                        ", beta=" + beta +
                        '}';
            }
        }
    }
}

