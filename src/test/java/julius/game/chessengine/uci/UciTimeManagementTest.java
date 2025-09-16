package julius.game.chessengine.uci;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UciTimeManagementTest {

    @Test
    void estimateMovesToGoPrefersLongerHorizonForBullet() {
        assertEquals(60L, UciHandler.estimateMovesToGo(60_000L, 0L));
        assertEquals(40L, UciHandler.estimateMovesToGo(180_000L, 0L));
        assertEquals(30L, UciHandler.estimateMovesToGo(600_000L, 0L));
    }

    @Test
    void computeTimeLimitUsesEstimateWhenMovestogoMissing() {
        long limit = UciHandler.computeTimeLimit(60_000L, 0L, 0L, 0, 0);
        assertEquals(1_000L, limit);
    }

    @Test
    void computeTimeLimitRespectsMoveOverheadForMovetime() {
        long limit = UciHandler.computeTimeLimit(0L, 0L, 500L, 0, 75);
        assertEquals(425L, limit);
    }

    @Test
    void computeTimeLimitCapsMovetimeToRemainingTime() {
        long limit = UciHandler.computeTimeLimit(200L, 0L, 500L, 0, 0);
        assertEquals(200L, limit);
    }

    @Test
    void computeTimeLimitDoesNotExceedRemainingTime() {
        long limit = UciHandler.computeTimeLimit(300L, 5_000L, 0L, 0, 0);
        assertEquals(300L, limit);
    }
}
