package julius.game.chessengine.ai;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.AiTuning;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static julius.game.chessengine.board.BoardStateAssertions.assertBoardConsistent;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

class AISimulationDesyncTest {

    private record Scenario(String label, String fen) {
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("Initial position", null),
            new Scenario("Logged queen-capture position",
                    "8/8/4q1k1/4p1p1/5p2/7P/4K3/8 b - - 2 43"),
            new Scenario("Logged king-capture position",
                    "8/8/3q4/4pk2/5pP1/8/4K3/8 b - - 0 44")
    );

    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(2);
    private static final int SEARCH_CYCLES = 3;

    @Test
    void lazySmpSearchesRemainSynchronizedWithBitBoards() throws InterruptedException {
        for (Scenario scenario : SCENARIOS) {
            Engine engine = new Engine();
            if (scenario.fen() != null) {
                engine.importBoardFromFen(scenario.fen());
            }
            assertBoardConsistent(engine.getBitBoard(), scenario.label() + " initial board");

            AiTuning tuning = AiTuning.builder()
                    .searchThreads(2)
                    .lazySmpThreads(3)
                    .hashSizeMb(4)
                    .maxDepth(8)
                    .timeLimitMillis(30)
                    .build();

            AI ai = new AI(engine, tuning);
            CollectingAppender bitBoardAppender = CollectingAppender.attach(BitBoard.class);
            CollectingAppender aiAppender = CollectingAppender.attach(AI.class);
            try {
                runSearchCycles(ai, engine, scenario.label());

                assertFalse(bitBoardAppender.containsMessageFragment("Capture inconsistency detected"),
                        scenario.label() + " produced capture inconsistency logs");
                assertFalse(bitBoardAppender.containsMessageFragment("No captured piece present"),
                        scenario.label() + " raised missing capture errors");
                assertFalse(aiAppender.containsMessageFragment("Illegal board state during search"),
                        scenario.label() + " encountered illegal board state during search");
                assertFalse(aiAppender.containsMessageFragment("Failed to sync simulation"),
                        scenario.label() + " failed to synchronise worker simulations");
                assertFalse(aiAppender.containsMessageFragment("Search worker"),
                        scenario.label() + " logged search worker errors");
                assertFalse(aiAppender.containsMessageFragment("Failed to create initial simulation"),
                        scenario.label() + " could not create worker simulations");
            } finally {
                ai.shutdown();
                bitBoardAppender.close();
                aiAppender.close();
            }
        }
    }

    private void runSearchCycles(AI ai, Engine engine, String label) throws InterruptedException {
        for (int cycle = 1; cycle <= SEARCH_CYCLES; cycle++) {
            ai.startAutoPlay(false, false);
            waitForBestMove(ai, label + " cycle " + cycle);

            assertBoardConsistent(engine.getBitBoard(), label + " cycle " + cycle + " main board");
            Engine clone = engine.createSimulation();
            assertBoardConsistent(clone.getBitBoard(), label + " cycle " + cycle + " clone board");

            assertFalse(ai.getCalculatedLine().isEmpty(),
                    label + " cycle " + cycle + " produced no principal variation");

            ai.stopCalculation();
            Thread.sleep(25L);
        }
    }

    private void waitForBestMove(AI ai, String context) throws InterruptedException {
        long deadline = System.nanoTime() + SEARCH_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Integer best = ai.getCurrentBestMoveInt();
            if (best != null && best >= 0) {
                return;
            }
            Thread.sleep(10L);
        }
        fail(context + " timed out waiting for best move");
    }

    private static final class CollectingAppender extends AbstractAppender {
        private final CopyOnWriteArrayList<LogEvent> events = new CopyOnWriteArrayList<>();
        private final Logger logger;

        private CollectingAppender(Logger logger, String name) {
            super(name, null, null, false, Property.EMPTY_ARRAY);
            this.logger = logger;
        }

        static CollectingAppender attach(Class<?> target) {
            LoggerContext context = LoggerContext.getContext(false);
            Logger logger = context.getLogger(target.getName());
            CollectingAppender appender = new CollectingAppender(logger, "test-" + target.getSimpleName());
            appender.start();
            logger.addAppender(appender);
            return appender;
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        boolean containsMessageFragment(String fragment) {
            for (LogEvent event : events) {
                String message = event.getMessage() != null
                        ? event.getMessage().getFormattedMessage()
                        : "";
                if (message.contains(fragment)) {
                    return true;
                }
                Throwable thrown = event.getThrown();
                if (thrown != null && thrown.getMessage() != null && thrown.getMessage().contains(fragment)) {
                    return true;
                }
            }
            return false;
        }

        void close() {
            logger.removeAppender(this);
            stop();
            events.clear();
        }
    }
}
