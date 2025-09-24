package julius.game.chessengine.engine.search.time;

import julius.game.chessengine.engine.search.config.SearchLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

class TimeManagerTest {

    private static final long ONE_MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1);

    private TestClock clock;
    private TimeManager manager;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        manager = new TimeManager(clock);
    }

    @Test
    void beginSearchInitialisesDeadlinesFromMoveTime() {
        SearchLimits limits = SearchLimits.builder().moveTimeMillis(250).build();
        clock.set(1_000_000L);

        manager.beginSearch(limits);

        long expectedDeadline = clock.now() + TimeUnit.MILLISECONDS.toNanos(250);
        assertEquals(clock.now(), manager.getSearchStartTimeNanos());
        assertEquals(expectedDeadline, manager.getHardDeadlineNanos());
        assertEquals(expectedDeadline, manager.getSoftDeadlineNanos());

        assertFalse(manager.shouldStop(0L, 0));
        clock.advance(TimeUnit.MILLISECONDS.toNanos(300));
        assertTrue(manager.shouldStop(0L, 0));
    }

    @Test
    void aspirationAttemptsApproachHardDeadline() {
        SearchLimits limits = SearchLimits.builder()
                .softDeadlineNanos(TimeUnit.MILLISECONDS.toNanos(100))
                .hardDeadlineNanos(TimeUnit.MILLISECONDS.toNanos(200))
                .build();
        clock.set(5_000_000L);

        manager.beginSearch(limits);

        long soft = manager.getSoftDeadlineNanos();
        long hard = manager.getHardDeadlineNanos();
        assertEquals(clock.now() + TimeUnit.MILLISECONDS.toNanos(100), soft);
        assertEquals(clock.now() + TimeUnit.MILLISECONDS.toNanos(200), hard);

        assertEquals(soft, manager.deadlineForAspirationAttempt(0));
        long attemptOne = manager.deadlineForAspirationAttempt(1);
        assertTrue(attemptOne > soft && attemptOne < hard);
        long attemptTwo = manager.deadlineForAspirationAttempt(2);
        assertTrue(attemptTwo >= attemptOne && attemptTwo < hard);
        long laterAttempt = manager.deadlineForAspirationAttempt(10);
        assertEquals(hard, laterAttempt);
    }

    @Test
    void nodesAndDepthLimitsTriggerStop() {
        SearchLimits limits = SearchLimits.builder()
                .nodesLimit(1000)
                .fixedDepth(5)
                .build();
        clock.set(10_000_000L);
        manager.beginSearch(limits);

        assertFalse(manager.shouldStop(999, 4));
        assertTrue(manager.shouldStop(1000, 4));
        assertTrue(manager.shouldStop(999, 5));
    }

    @Test
    void requestStopOverridesLimits() {
        manager.beginSearch(SearchLimits.unlimited());
        assertFalse(manager.shouldStop(0L, 0));
        manager.requestStop();
        assertTrue(manager.shouldStop(0L, 0));
        assertTrue(manager.stopSignal().get());
    }

    @Test
    void elapsedMillisUsesProvidedClock() {
        manager.beginSearch(SearchLimits.unlimited());
        assertEquals(0L, manager.getSearchElapsedMillis());

        clock.advance(42 * ONE_MILLISECOND);
        assertEquals(42L, manager.getSearchElapsedMillis());
    }

    @Test
    void resetClearsState() {
        clock.set(100L);
        manager.beginSearch(SearchLimits.builder().moveTimeMillis(10).build());
        manager.requestStop();

        manager.reset();

        assertEquals(0L, manager.getSearchStartTimeNanos());
        assertEquals(Long.MAX_VALUE, manager.getSoftDeadlineNanos());
        assertEquals(Long.MAX_VALUE, manager.getHardDeadlineNanos());
        assertFalse(manager.stopSignal().get());
        assertFalse(manager.shouldStop(0L, 0));
    }

    private static final class TestClock implements LongSupplier {
        private long now;

        @Override
        public long getAsLong() {
            return now;
        }

        void set(long value) {
            now = value;
        }

        void advance(long delta) {
            now += delta;
        }

        long now() {
            return now;
        }
    }
}

