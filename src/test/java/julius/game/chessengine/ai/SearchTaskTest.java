package julius.game.chessengine.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static julius.game.chessengine.utils.Score.CHECKMATE;

class SearchTaskTest {

    @Test
    void losingMateForWhiteDoesNotStopSearch() {
        SearchTask task = new SearchTask(1L, 42L, true,
                System.nanoTime() + 1_000_000_000L, 1);

        MoveAndScore losingMate = new MoveAndScore(123, -CHECKMATE + 10);
        assertTrue(task.publishBest(losingMate, 1, null));
        assertFalse(task.isStopRequested(),
                "Fail-hard mate against the mover must not stop the search");

        MoveAndScore savingMove = new MoveAndScore(456, 0);
        assertTrue(task.publishBest(savingMove, 2, null));
        BestMoveDepth best = task.getBest();
        assertEquals(456, best.move);
        assertEquals(0.0, best.score, 0.0001);
    }

    @Test
    void losingMateForBlackDoesNotStopSearch() {
        SearchTask task = new SearchTask(2L, 99L, false,
                System.nanoTime() + 1_000_000_000L, 1);

        MoveAndScore losingMate = new MoveAndScore(321, CHECKMATE - 10);
        assertTrue(task.publishBest(losingMate, 1, null));
        assertFalse(task.isStopRequested(),
                "Fail-hard mate against the mover must not stop the search");

        MoveAndScore savingMove = new MoveAndScore(654, 0);
        assertTrue(task.publishBest(savingMove, 2, null));
        BestMoveDepth best = task.getBest();
        assertEquals(654, best.move);
        assertEquals(0.0, best.score, 0.0001);
    }

    @Test
    void winningMateForMoverStopsSearch() {
        SearchTask whiteTask = new SearchTask(3L, 111L, true,
                System.nanoTime() + 1_000_000_000L, 1);
        assertTrue(whiteTask.publishBest(new MoveAndScore(11, CHECKMATE - 5), 1, null));
        assertTrue(whiteTask.isStopRequested(),
                "Winning mate for side to move should stop the search");

        SearchTask blackTask = new SearchTask(4L, 222L, false,
                System.nanoTime() + 1_000_000_000L, 1);
        assertTrue(blackTask.publishBest(new MoveAndScore(22, -CHECKMATE + 5), 1, null));
        assertTrue(blackTask.isStopRequested(),
                "Winning mate for side to move should stop the search");
    }
}
