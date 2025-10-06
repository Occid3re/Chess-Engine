package julius.game.chessengine.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AIConcurrencyBudgetTest {

    @Test
    void leaderGetsFullCapacityWhenNoLazyFollowers() {
        assertEquals(8, AI.perWorkerHelperBudget(8, 1, null));
        assertEquals(8, AI.perWorkerHelperBudget(8, 1, 0));
    }

    @Test
    void helperCapacityIsSplitAcrossLazyWorkers() {
        assertEquals(2, AI.perWorkerHelperBudget(5, 3, 0));
        assertEquals(2, AI.perWorkerHelperBudget(5, 3, 1));
        assertEquals(1, AI.perWorkerHelperBudget(5, 3, 2));
    }

    @Test
    void workersBeyondCapacityReceiveZeroBudget() {
        assertEquals(1, AI.perWorkerHelperBudget(2, 4, 0));
        assertEquals(1, AI.perWorkerHelperBudget(2, 4, 1));
        assertEquals(0, AI.perWorkerHelperBudget(2, 4, 2));
        assertEquals(0, AI.perWorkerHelperBudget(2, 4, 3));
    }

    @Test
    void zeroOrNegativeCapacityDisablesRootHelpers() {
        assertEquals(0, AI.perWorkerHelperBudget(0, 3, 0));
        assertEquals(0, AI.perWorkerHelperBudget(-2, 3, 1));
    }

    @Test
    void indexesOutsideLazyRangeClampToLastWorker() {
        assertEquals(2, AI.perWorkerHelperBudget(4, 2, 5));
    }
}
