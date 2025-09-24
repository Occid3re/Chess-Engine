package julius.game.chessengine.engine.search.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchLimitsTest {

    @Test
    void builderClampsNonNegativeValues() {
        SearchLimits limits = SearchLimits.builder()
                .timeControl(-1, -2, -3, -4, -5)
                .nodesLimit(-10)
                .moveTimeMillis(-20)
                .softDeadlineNanos(-30)
                .hardDeadlineNanos(-40)
                .build();

        assertFalse(limits.hasTimeLimit());
        assertEquals(0, limits.getNodesLimit());
        assertEquals(0, limits.getMoveTimeMillis());
        assertEquals(0, limits.getSoftDeadlineNanos());
        assertEquals(0, limits.getHardDeadlineNanos());
        assertEquals(0, limits.getTimeControl().movesToGo());
        assertEquals(0, limits.getTimeControl().whiteTimeMillis());
    }

    @Test
    void hasTimeLimitConsidersMoveTimeAndTimeControl() {
        SearchLimits withMoveTime = SearchLimits.builder().moveTimeMillis(150).build();
        assertTrue(withMoveTime.hasTimeLimit());

        SearchLimits withTimeControl = SearchLimits.builder()
                .timeControl(1_000, 0, 0, 0, 0)
                .build();
        assertTrue(withTimeControl.hasTimeLimit());

        SearchLimits unlimited = SearchLimits.unlimited();
        assertFalse(unlimited.hasTimeLimit());
    }

    @Test
    void hardDeadlineUsesMoveTimeWhenPresent() {
        SearchLimits limits = SearchLimits.builder().moveTimeMillis(250).build();
        long start = 500L;
        long expected = start + TimeUnit.MILLISECONDS.toNanos(250);
        assertEquals(expected, limits.hardDeadlineNanos(start));
    }

    @Test
    void hardDeadlinePrefersExplicitDeadline() {
        SearchLimits limits = SearchLimits.builder()
                .moveTimeMillis(250)
                .hardDeadlineNanos(1_500)
                .build();
        assertEquals(2_000, limits.hardDeadlineNanos(500));
    }
}
