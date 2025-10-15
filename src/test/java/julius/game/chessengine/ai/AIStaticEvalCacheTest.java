package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class AIStaticEvalCacheTest {

    @Test
    void staticEvalCacheKeysIncludeHalfmoveClockForTablebaseResults() throws Exception {
        Engine engine = new Engine();
        engine.importBoardFromFen("8/8/8/8/8/8/8/K6k w - - 0 1");

        AI ai = new AI(engine);
        Method resolve = AI.class.getDeclaredMethod("resolveScoreDifference", GameState.class, long.class, boolean.class);
        resolve.setAccessible(true);

        GameState state = engine.getGameState();
        long boardHash = engine.getBoardStateHash();
        boolean whiteToMove = engine.whitesTurn();

        TablebaseResult win = new TablebaseResult(SyzygyWdl.WIN, OptionalInt.of(1), OptionalInt.of(1), Optional.empty());
        state.setHalfmoveClock(0);
        state.setLastTablebaseResult(win);
        double expectedWin = Score.tablebaseToEvaluation(win, whiteToMove, 0);
        double cachedWin = (double) resolve.invoke(ai, state, boardHash, whiteToMove);
        assertThat(cachedWin).isEqualTo(expectedWin);

        TablebaseResult loss = new TablebaseResult(SyzygyWdl.LOSS, OptionalInt.of(1), OptionalInt.of(1), Optional.empty());
        state.setHalfmoveClock(99);
        state.setLastTablebaseResult(loss);
        double expectedLoss = Score.tablebaseToEvaluation(loss, whiteToMove, 99);
        double cachedLoss = (double) resolve.invoke(ai, state, boardHash, whiteToMove);
        assertThat(cachedLoss).isEqualTo(expectedLoss);

        state.setLastTablebaseResult(null);

        state.setHalfmoveClock(0);
        double replayedWin = (double) resolve.invoke(ai, state, boardHash, whiteToMove);
        assertThat(replayedWin).isEqualTo(expectedWin);

        state.setHalfmoveClock(99);
        double replayedLoss = (double) resolve.invoke(ai, state, boardHash, whiteToMove);
        assertThat(replayedLoss).isEqualTo(expectedLoss);

        ai.shutdown();
    }
}

