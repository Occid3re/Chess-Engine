package julius.game.chessengine.ai;

import julius.game.chessengine.ai.time.TimeManager;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.AiTuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import testsupport.TestLoggingExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the production {@link AI} parallel root search to gather worker diagnostics.
 * The existing {@link BestMoveSearchTest} relies on {@code DiagnosticAI}, which performs
 * sequential move analysis; this harness calls the real root-split implementation so we
 * can observe executor utilisation via the logging hooks.
 */
@org.junit.jupiter.api.extension.ExtendWith(TestLoggingExtension.class)
class BestMoveSearchParallelHarnessTest {

    private static final String WORKER_STATS_PROP = "chessengine.diagnostics.workerStats";
    private static final String ROOT_FANOUT_PROP = "chessengine.diagnostics.rootFanout";
    private static final int SEARCH_THREADS = 16;
    private static final int LAZY_SMP_THREADS = 8;
    private static final int ROOT_LIMIT = 72;
    private static final int HASH_MB = 256;
    private static final int SEARCH_DEPTH = 8;

    @AfterEach
    void clearDiagnosticsFlags() {
        System.clearProperty(WORKER_STATS_PROP);
        System.clearProperty(ROOT_FANOUT_PROP);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "3R4/8/P4pk1/rP4p1/8/8/1K6/8 w - - 3 65",
            "r1bqkbnr/pppppppp/2n5/8/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
    })
    void productionParallelSearchProducesExecutorWork(String fen) throws Exception {
        System.setProperty(WORKER_STATS_PROP, "true");
        System.setProperty(ROOT_FANOUT_PROP, "true");

        Engine engine = new Engine();
        engine.importBoardFromFen(fen);

        AiTuning tuning = AiTuning.builder()
                .searchThreads(SEARCH_THREADS)
                .lazySmpThreads(LAZY_SMP_THREADS)
                .rootParallelLimit(ROOT_LIMIT)
                .hashSizeMb(HASH_MB)
                .maxDepth(SEARCH_DEPTH)
                .timeLimitMillis(5_000)
                .build();

        AI ai = new AI(engine, tuning);
        try {
            RootSearchResult result = executeParallelRootSearch(ai, engine);
            assertNotNull(result, "Parallel root search should produce a result");

            ThreadPoolExecutor executor = extractSearchPool(ai);
            assertNotNull(executor, "Search pool must be created when searchThreads > 1");
        } finally {
            ai.shutdown();
        }
    }

    private RootSearchResult executeParallelRootSearch(AI ai, Engine rootEngine) throws Exception {
        Engine simulatorEngine = rootEngine.createSimulation();
        Engine rootSnapshot = simulatorEngine.createSimulation();

        TimeManager timeManager = extractTimeManager(ai);
        TimeManager.TimeBudget budget = timeManager.beginSearchWithOverride(5_000L);

        SearchTask task = new SearchTask(
                1L,
                simulatorEngine.getBoardStateHash(),
                simulatorEngine.whitesTurn(),
                budget,
                LAZY_SMP_THREADS,
                rootSnapshot
        );

        Method getBestMoveParallel = AI.class.getDeclaredMethod(
                "getBestMoveParallel",
                Engine.class,
                SearchTask.class,
                int.class,
                long.class,
                double.class,
                double.class,
                SplittableRandom.class
        );
        getBestMoveParallel.setAccessible(true);

        return (RootSearchResult) getBestMoveParallel.invoke(
                ai,
                simulatorEngine,
                task,
                SEARCH_DEPTH,
                budget.hardDeadlineNanos(),
                Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                new SplittableRandom(0L)
        );
    }

    private TimeManager extractTimeManager(AI ai) throws Exception {
        Field field = AI.class.getDeclaredField("timeManager");
        field.setAccessible(true);
        return (TimeManager) field.get(ai);
    }

    private ThreadPoolExecutor extractSearchPool(AI ai) throws Exception {
        Field field = AI.class.getDeclaredField("searchPool");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(ai);
    }
}
