package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.AiTuning;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import testsupport.DeterministicAiHelper;
import testsupport.InstrumentedTranspositionTable;
import testsupport.TestLoggingExtension;
import testsupport.TestUtils;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Log4j2
@ExtendWith(TestLoggingExtension.class)
class AITest_TranspositionTableBehavior {

    private static final double WINDOW_SECONDS = 0.25;

    private AI newAi() {
        Engine engine = new Engine();
        AI ai = new AI(engine, AiTuning.defaults());
        ai.setMaxDepth(4);
        return ai;
    }

    @Test
    @DisplayName("Iterative deepening interacts with the main and capture TT")
    void transpositionTableCountersIncrementDuringSearch() throws Exception {
        AI ai = newAi();
        try (AutoCloseable ignore = DeterministicAiHelper.withSingleThread(ai);
             AutoCloseable time = DeterministicAiHelper.withShortTimeLimit(ai, 150)) {

            InstrumentedTranspositionTable<TranspositionTableEntry> main = new InstrumentedTranspositionTable<>();
            InstrumentedTranspositionTable<CaptureTranspositionTableEntry> capture = new InstrumentedTranspositionTable<>();
            TestUtils.writeField(ai, "transpositionTable", main);
            TestUtils.writeField(ai, "captureTranspositionTable", capture);

            log.info("Starting short deterministic search to observe TT interaction");
            MoveAndScore result = ai.searchBestMoveBlocking(150);
            log.info("Search result: move={}, score={}", result != null ? result.move : -1,
                    result != null ? result.score : Double.NaN);

            InstrumentedTranspositionTable.Snapshot mainSnapshot = main.snapshot();
            InstrumentedTranspositionTable.Snapshot captureSnapshot = capture.snapshot();

            log.info("Main TT snapshot: {}", mainSnapshot);
            log.info("Capture TT snapshot: {}", captureSnapshot);

            assertTrue(mainSnapshot.getCount() > 0, "Main table should be probed at least once");
            assertTrue(mainSnapshot.putCount() > 0, "Main table should store at least one entry");
            assertTrue(mainSnapshot.advanceAgeCount() >= 1, "advanceAge should run per iteration");

            assertTrue(captureSnapshot.advanceAgeCount() >= 1, "Capture table ages with iterations");
            assertTrue(captureSnapshot.putCount() >= 1, "Capture table should cache quiescence scores");
        }
    }

    @ParameterizedTest(name = "Hash size {0} MB produces expected TT capacities")
    @ValueSource(ints = {1, 8, 64, 4096})
    void capacityMatchesProductionComputation(int hashSize) throws Exception {
        AI ai = newAi();
        ai.setHashSizeMb(hashSize);

        int expectedMain = computeExpectedCapacity(hashSize, true);
        int expectedCapture = computeExpectedCapacity(hashSize, false);

        log.info("Hash {} MB -> expected main {} capture {}", hashSize, expectedMain, expectedCapture);
        log.info("Observed capacities -> main {} capture {}", ai.getTranspositionTableCapacity(),
                ai.getCaptureTranspositionTableCapacity());

        assertEquals(expectedMain, ai.getTranspositionTableCapacity(),
                "Main TT capacity mismatch for hash size " + hashSize);
        assertEquals(expectedCapture, ai.getCaptureTranspositionTableCapacity(),
                "Capture TT capacity mismatch for hash size " + hashSize);
    }

    @Test
    @DisplayName("evaluateBoard reuses capture TT entries across calls")
    void evaluateBoardUsesCaptureCache() throws Exception {
        AI ai = newAi();

        InstrumentedTranspositionTable<CaptureTranspositionTableEntry> capture = new InstrumentedTranspositionTable<>();
        TestUtils.writeField(ai, "captureTranspositionTable", capture);

        long deadline = System.nanoTime() + (long) (WINDOW_SECONDS * TimeUnit.SECONDS.toNanos(1));
        boolean isWhite = ai.getMainEngine().whitesTurn();

        log.info("First evaluation on empty cache");
        double first = ai.evaluateBoard(ai.getMainEngine().createSimulation(), isWhite, deadline);
        InstrumentedTranspositionTable.Snapshot afterFirst = capture.snapshot();
        log.info("First score={} snapshot={}", first, afterFirst);

        log.info("Second evaluation should hit the cache");
        double second = ai.evaluateBoard(ai.getMainEngine().createSimulation(), isWhite, deadline);
        InstrumentedTranspositionTable.Snapshot afterSecond = capture.snapshot();
        log.info("Second score={} snapshot={}", second, afterSecond);

        assertEquals(afterFirst.putCount(), afterSecond.putCount(),
                "Second evaluation must not insert a new entry");
        assertTrue(afterSecond.getCount() > afterFirst.getCount(),
                "Second evaluation should perform at least one cache lookup");
    }

    @SneakyThrows
    private static int computeExpectedCapacity(int hashSizeMb, boolean mainTable) {
        long totalBytes = Math.max(1L, (long) hashSizeMb * 1024L * 1024L);
        double mainWeight = julius.game.chessengine.tuning.Tuning.searchTtMainWeight();
        double captureWeight = julius.game.chessengine.tuning.Tuning.searchTtCaptureWeight();
        double totalWeight = mainWeight + captureWeight;
        long mainBudget = Math.max(1L, (long) (totalBytes * (mainWeight / totalWeight)));
        long captureBudget = Math.max(1L, totalBytes - mainBudget);
        if (captureBudget <= 0) {
            captureBudget = 1L;
            mainBudget = Math.max(1L, totalBytes - captureBudget);
        }
        long budget = mainTable ? mainBudget : captureBudget;
        int entrySize = mainTable ? 48 : 32;
        int minEntries = mainTable ? (1 << 12) : (1 << 11);
        int maxEntries = mainTable ? (1 << 26) : (1 << 25);

        Method method = AI.class.getDeclaredMethod("computeTableCapacity", long.class, int.class, int.class, int.class);
        method.setAccessible(true);
        return (int) method.invoke(null, budget, entrySize, minEntries, maxEntries);
    }
}

