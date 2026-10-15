package julius.game.chessengine.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeManagementTest {

    private static long opt(long time, long inc, int fullmove) {
        return SearchManager.allocate(time, inc, 0, fullmove, 30)[0];
    }

    private static long max(long time, long inc, int fullmove) {
        return SearchManager.allocate(time, inc, 0, fullmove, 30)[1];
    }

    @Test
    void blitzStartsAroundSixSecondsAndNeverPlansBeyondFortyPercent() {
        // 3+2 at move one: about a fortieth of the clock plus most of the increment.
        assertTrue(opt(180_000, 2000, 1) >= 5500 && opt(180_000, 2000, 1) <= 6500, "opt " + opt(180_000, 2000, 1));
        assertTrue(max(180_000, 2000, 1) <= 72_000);
        assertEquals(3 * opt(180_000, 2000, 1), max(180_000, 2000, 1), 3);
    }

    @Test
    void laterMovesKeepAFairShare() {
        // The same 30 seconds on the clock buy more time at move 50 than at move 1.
        assertTrue(opt(30_000, 0, 50) > opt(30_000, 0, 1) * 2);
        // ... but moves-to-go never drops below 16.
        assertEquals(opt(30_000, 0, 50), opt(30_000, 0, 90));
    }

    @Test
    void bulletNeverRunsTheFlagDown() {
        // 60+0 at the start: 1.5 seconds, at most 4.5.
        assertEquals(1499, opt(60_000, 0, 1));
        assertEquals(4497, max(60_000, 0, 1));
        // Eight seconds left: a twentieth, at most an eighth.
        assertTrue(opt(8000, 0, 40) <= 400);
        assertTrue(max(8000, 0, 40) <= 1000);
        // With an increment the scramble still keeps most of it.
        assertTrue(opt(8000, 1000, 40) <= 1000);
        assertTrue(max(8000, 1000, 40) <= 1600);
        // Half a second and a two-second increment: spend a fraction, collect the increment.
        assertTrue(max(500, 2000, 60) <= 200);
    }

    @Test
    void budgetsAreOrderedAndInsideTheClock() {
        long[] times = {100, 500, 2000, 8000, 30_000, 60_000, 180_000, 600_000};
        long[] incs = {0, 1000, 2000, 10_000};
        int[] moves = {1, 10, 30, 60, 120};
        for (long t : times) {
            for (long i : incs) {
                for (int m : moves) {
                    long[] b = SearchManager.allocate(t, i, 0, m, 30);
                    assertTrue(b[0] >= 1 && b[0] <= b[1], t + "+" + i + " @" + m);
                    assertTrue(b[1] < t, "max must stay inside the clock: " + t + "+" + i + " @" + m + " -> " + b[1]);
                }
            }
        }
    }

    @Test
    void movesToGoSharesTheClockEvenly() {
        long[] b = SearchManager.allocate(60_000, 0, 20, 10, 30);
        assertEquals(2998, b[0]);
        assertEquals(8995, b[1]);
        // The last move before the control may use most of what is left.
        long[] last = SearchManager.allocate(5000, 0, 1, 40, 30);
        assertTrue(last[0] <= 4000 && last[1] <= 4000);
    }
}
