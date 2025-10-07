package julius.game.chessengine.uci;

import julius.game.chessengine.ai.time.TimeManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UciTimeManagementTest {

    @Test
    void estimateMovesToGoPrefersLongerHorizonForBullet() {
        assertEquals(60L, TimeManager.estimateMovesToGo(60_000L, 0L));
        assertEquals(40L, TimeManager.estimateMovesToGo(180_000L, 0L));
        assertEquals(30L, TimeManager.estimateMovesToGo(600_000L, 0L));
    }

    @Test
    void allocationUsesEstimateWhenMovestogoMissing() {
        TimeManager manager = new TimeManager(5_000L);
        manager.submit(new TimeManager.Request(60_000L, 0L, 0L, 0, 0, false));
        TimeManager.TimeBudget budget = manager.beginSearch();
        assertEquals(1_000L, budget.allocationMillis());
    }

    @Test
    void allocationRespectsMoveOverheadForMovetime() {
        TimeManager manager = new TimeManager(5_000L);
        manager.submit(new TimeManager.Request(0L, 0L, 500L, 0, 75, false));
        TimeManager.TimeBudget budget = manager.beginSearch();
        assertEquals(425L, budget.allocationMillis());
    }

    @Test
    void allocationCapsMovetimeToRemainingTime() {
        TimeManager manager = new TimeManager(5_000L);
        manager.submit(new TimeManager.Request(200L, 0L, 500L, 0, 0, false));
        TimeManager.TimeBudget budget = manager.beginSearch();
        assertEquals(200L, budget.allocationMillis());
    }

    @Test
    void allocationDoesNotExceedRemainingTime() {
        TimeManager manager = new TimeManager(5_000L);
        manager.submit(new TimeManager.Request(300L, 5_000L, 0L, 0, 0, false));
        TimeManager.TimeBudget budget = manager.beginSearch();
        assertEquals(300L, budget.allocationMillis());
    }

    @Test
    void ponderHitPromotesUnlimitedBudgetToTimedSearch() {
        TimeManager manager = new TimeManager(5_000L);
        manager.submit(new TimeManager.Request(30_000L, 0L, 0L, 0, 0, true));
        TimeManager.TimeBudget ponder = manager.beginSearch();
        assertTrue(ponder.isPonder());
        assertEquals(Long.MAX_VALUE, ponder.hardDeadlineNanos());

        TimeManager.TimeBudget promoted = manager.promotePonderHit();
        assertFalse(promoted.isPonder());
        assertTrue(promoted.hardDeadlineNanos() < Long.MAX_VALUE);
        assertEquals(500L, promoted.allocationMillis());
    }
}
