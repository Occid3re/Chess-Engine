package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.AiTuning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AITest_LazySmpSnapshot {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void lazyWorkersCopyFromDedicatedSnapshot() throws Exception {
        InstrumentedEngine engine = new InstrumentedEngine();
        AiTuning tuning = AiTuning.builder()
                .searchThreads(1)
                .lazySmpThreads(2)
                .maxDepth(4)
                .timeLimitMillis(200)
                .build();

        AI ai = new AI(engine, tuning);

        Method startCalc = AI.class.getDeclaredMethod("startCalculationThread");
        startCalc.setAccessible(true);

        // Leave the opening book to force a full search path.
        int firstMove = engine.getAllLegalMoves().getInt(0);
        engine.performMove(firstMove);

        try {
            startCalc.invoke(ai);

            // Wait until the initial search has started.
            waitForCondition(() -> engine.descendants().size() >= tuning.lazySmpThreads() + 2,
                    Duration.ofSeconds(1), Duration.ofMillis(10));

            // Wait for the dedicated root snapshot clone to appear.
            waitForCondition(() -> engine.descendants().stream()
                            .anyMatch(clone -> clone != engine
                                    && clone.parent() != null
                                    && clone.parent() != engine),
                    Duration.ofSeconds(2), Duration.ofMillis(10));
        } finally {
            ai.stopCalculation();
        }

        boolean hasNestedClone = engine.descendants().stream()
                .anyMatch(clone -> clone != engine
                        && clone.parent() != null
                        && clone.parent() != engine);

        assertTrue(hasNestedClone, "Lazy SMP workers should receive an isolated root snapshot clone");
    }

    private static final class InstrumentedEngine extends Engine {
        private final InstrumentedEngine parent;
        private final CopyOnWriteArrayList<InstrumentedEngine> descendants;

        InstrumentedEngine() {
            super();
            this.parent = null;
            this.descendants = new CopyOnWriteArrayList<>();
            this.descendants.add(this);
        }

        private InstrumentedEngine(InstrumentedEngine parent) {
            super(parent);
            this.parent = parent;
            this.descendants = parent.descendants;
            this.descendants.add(this);
        }

        @Override
        public InstrumentedEngine createSimulation() {
            return new InstrumentedEngine(this);
        }

        InstrumentedEngine parent() {
            return parent;
        }

        List<InstrumentedEngine> descendants() {
            return descendants;
        }
    }

    private static void waitForCondition(BooleanSupplier condition, Duration timeout, Duration step)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                break;
            }
            Thread.sleep(step.toMillis());
        }
        assertTrue(condition.getAsBoolean(), "Condition not satisfied before timeout");
    }
}
