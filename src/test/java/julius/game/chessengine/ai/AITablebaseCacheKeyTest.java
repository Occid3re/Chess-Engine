package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TestSyzygyTablebaseService;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.tuning.AiTuning;
import julius.game.chessengine.tuning.Tuning;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class AITablebaseCacheKeyTest {

    private static final String BASE_FEN = "8/8/8/8/8/4K3/8/7k w - - 0 1";
    private static final String LATE_FEN = "8/8/8/8/8/4K3/8/7k w - - 98 1";

    @BeforeEach
    void refreshTuning() {
        Tuning.refresh();
    }

    @Test
    void tablebaseScoresAreCachedPerHalfmoveClock() throws Exception {
        Map<String, SyzygyProbeResult> responses = Map.of(
                BASE_FEN, probeResult(6),
                LATE_FEN, probeResult(2)
        );

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(responses);

        try (AutoCloseable restorer = overrideScoreTablebase(service)) {
            Engine mainEngine = new Engine();
            mainEngine.importBoardFromFen(BASE_FEN);

            AI ai = new AI(mainEngine, AiTuning.defaults(), service);
            try {
                Engine early = new Engine();
                early.importBoardFromFen(BASE_FEN);
                Engine late = new Engine();
                late.importBoardFromFen(LATE_FEN);

                early.getGameState().refreshScore(early.getBitBoard());
                early.getGameState().captureTablebaseState();
                late.getGameState().refreshScore(late.getBitBoard());
                late.getGameState().captureTablebaseState();

                assertThat(early.getGameState().getLastTablebaseResult()).isPresent();
                assertThat(late.getGameState().getLastTablebaseResult()).isPresent();

                long boardHash = early.getBoardStateHash();
                long lateHash = late.getBoardStateHash();
                assertThat(boardHash).isEqualTo(lateHash);

                TablebaseResult earlyResult = early.getGameState().getLastTablebaseResult().orElseThrow();
                TablebaseResult lateResult = late.getGameState().getLastTablebaseResult().orElseThrow();

                double expectedEarly = Score.tablebaseToEvaluation(earlyResult, early.whitesTurn(),
                        early.getGameState().getHalfmoveClock());
                double expectedLate = Score.tablebaseToEvaluation(lateResult, late.whitesTurn(),
                        late.getGameState().getHalfmoveClock());
                assertThat(expectedEarly).isNotEqualTo(expectedLate);

                double earlyEval = resolve(ai, early.getGameState(), boardHash, early.whitesTurn());
                double lateEval = resolve(ai, late.getGameState(), lateHash, late.whitesTurn());

                assertThat(earlyEval).isEqualTo(expectedEarly);
                assertThat(lateEval).isEqualTo(expectedLate);

                Long2DoubleOpenHashMap cache = extractCache(ai);
                long earlyKey = mixCacheKey(boardHash, early.getGameState().getHalfmoveClock());
                long lateKey = mixCacheKey(lateHash, late.getGameState().getHalfmoveClock());

                assertThat(earlyKey).isNotEqualTo(lateKey);
                assertThat(cache.get(earlyKey)).isEqualTo(earlyEval);
                assertThat(cache.get(lateKey)).isEqualTo(lateEval);
                assertThat(cache.size()).isGreaterThanOrEqualTo(2);
            } finally {
                ai.shutdown();
            }
        }
    }

    private static SyzygyProbeResult probeResult(int dtz) {
        return new SyzygyProbeResult(SyzygyWdl.CURSED_WIN, OptionalInt.of(dtz), OptionalInt.empty(), Optional.empty());
    }

    private static double resolve(AI ai, GameState state, long boardHash, boolean whiteToMove) throws Exception {
        Method method = AI.class.getDeclaredMethod("resolveScoreDifference", GameState.class, long.class, boolean.class);
        method.setAccessible(true);
        return (double) method.invoke(ai, state, boardHash, whiteToMove);
    }

    @SuppressWarnings("unchecked")
    private static Long2DoubleOpenHashMap extractCache(AI ai) throws Exception {
        Field field = AI.class.getDeclaredField("staticEvalCache");
        field.setAccessible(true);
        ThreadLocal<Long2DoubleOpenHashMap> threadLocal = (ThreadLocal<Long2DoubleOpenHashMap>) field.get(ai);
        return threadLocal.get();
    }

    private static long mixCacheKey(long boardHash, int halfmoveClock) throws Exception {
        Method method = AI.class.getDeclaredMethod("mixBoardHashWithHalfmove", long.class, int.class);
        method.setAccessible(true);
        return (long) method.invoke(null, boardHash, halfmoveClock);
    }

    private static AutoCloseable overrideScoreTablebase(SyzygyTablebaseService service) {
        SyzygyTablebaseService previous = Score.getTablebaseService();
        Score.setTablebaseService(service);
        return () -> {
            if (previous == null) {
                Score.clearTablebaseService();
            } else {
                Score.setTablebaseService(previous);
            }
        };
    }
}
