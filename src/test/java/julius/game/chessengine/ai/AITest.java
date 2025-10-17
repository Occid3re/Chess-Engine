package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.Move;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.tuning.AiTuning;
import julius.game.chessengine.utils.MoveStack;
import julius.game.chessengine.utils.Score;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import testsupport.TestLoggingExtension;

import julius.game.chessengine.ai.NodeType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static testsupport.TestUtils.extractTableSize;
import static testsupport.TestUtils.putDummyEntry;
import static testsupport.TestUtils.readField;
import static testsupport.TestUtils.readKillersLength;
import static testsupport.TestUtils.writeField;

@DisplayName("Comprehensive AI behavioural tests with verbose diagnostics")
@ExtendWith(TestLoggingExtension.class)
class AITest {

    private static final Logger log = LogManager.getLogger(AITest.class);

    @Test
    @DisplayName("setHashSizeMb should clamp values, rebuild tables, and honour capacity distribution")
    void testSetHashSizeMbClampsAndRebuilds() throws Exception {
        Engine engine = new Engine();
        AI ai = new AI(engine, AiTuning.defaults());

        log.info("Initial hash size from tuning: {} MB", ai.getHashSizeMb());
        Object originalMainTable = readField(ai, "transpositionTable");
        Object originalCaptureTable = readField(ai, "captureTranspositionTable");

        // Prime the existing tables so that we can verify clear() is invoked during rebuild.
        putDummyEntry(ai, "transpositionTable", 0xABCDL,
                new TranspositionTableEntry(1.0, 4, NodeType.EXACT, 1234));
        putDummyEntry(ai, "captureTranspositionTable", 0x1234L,
                new CaptureTranspositionTableEntry(2.5, true));
        log.info("Prime table sizes -> main: {}, capture: {}", extractTableSize(ai, "transpositionTable"),
                extractTableSize(ai, "captureTranspositionTable"));

        int requestedHashSize = AI.MIN_HASH_SIZE_MB - 5; // intentionally below the minimum
        ai.setHashSizeMb(requestedHashSize);

        log.info("After clamp, reported hash size: {} MB", ai.getHashSizeMb());
        assertEquals(AI.MIN_HASH_SIZE_MB, ai.getHashSizeMb(),
                "Hash size must be clamped to the documented minimum");

        Object rebuiltMainTable = readField(ai, "transpositionTable");
        Object rebuiltCaptureTable = readField(ai, "captureTranspositionTable");
        assertNotSame(originalMainTable, rebuiltMainTable, "Main TT instance must be rebuilt");
        assertNotSame(originalCaptureTable, rebuiltCaptureTable, "Capture TT instance must be rebuilt");
        assertEquals(0, extractTableSize(ai, "transpositionTable"), "Rebuilt main table must be empty");
        assertEquals(0, extractTableSize(ai, "captureTranspositionTable"), "Rebuilt capture table must be empty");

        int expectedMainCapacity = computeExpectedCapacity(ai.getHashSizeMb(), true);
        int expectedCaptureCapacity = computeExpectedCapacity(ai.getHashSizeMb(), false);
        log.info("Capacity expectation -> main: {}, capture: {}", expectedMainCapacity, expectedCaptureCapacity);
        log.info("Actual capacities -> main: {}, capture: {}", ai.getTranspositionTableCapacity(),
                ai.getCaptureTranspositionTableCapacity());
        assertEquals(expectedMainCapacity, ai.getTranspositionTableCapacity(),
                "Main TT capacity should follow the weighted budget formula");
        assertEquals(expectedCaptureCapacity, ai.getCaptureTranspositionTableCapacity(),
                "Capture TT capacity should follow the weighted budget formula");
    }

    @Test
    @DisplayName("setMaxDepth must expand heuristic tables and update the public depth")
    void testSetMaxDepthExpandsHeuristics() throws Exception {
        Engine engine = new Engine();
        AI ai = new AI(engine, AiTuning.defaults());

        Object globalHeuristics = readField(ai, "globalHeuristics");
        int initialDepth = readKillersLength(globalHeuristics);
        log.info("Initial killer table depth: {}", initialDepth);

        int newDepth = 96;
        ai.setMaxDepth(newDepth);

        log.info("Updated max depth via getter: {}", ai.getMaxDepth());
        assertEquals(newDepth, ai.getMaxDepth(), "The public maxDepth must mirror the requested depth");

        int expandedGlobalDepth = readKillersLength(globalHeuristics);
        log.info("Expanded global killer depth: {}", expandedGlobalDepth);
        assertTrue(expandedGlobalDepth >= newDepth,
                "Global heuristic killer table must grow to at least the requested depth");

        Object threadLocal = readField(ai, "threadHeuristics");
        Object threadHeuristics = ((ThreadLocal<?>) threadLocal).get();
        int expandedThreadDepth = readKillersLength(threadHeuristics);
        log.info("Expanded thread-local killer depth: {}", expandedThreadDepth);
        assertTrue(expandedThreadDepth >= newDepth,
                "Thread-local heuristic killer table must grow to at least the requested depth");
    }

    @Test
    @DisplayName("getCurrentBestMoveInt should honour readiness and board-hash matching")
    void testGetCurrentBestMoveIntHonoursHash() throws Exception {
        Engine engine = new Engine();
        AI ai = new AI(engine, AiTuning.defaults());

        long liveHash = engine.getBoardStateHash();
        IntArrayList legalMoves = engine.getAllLegalMoves();
        assertFalse(legalMoves.isEmpty(), "Starting position must have legal moves");
        int sampleMove = legalMoves.getInt(0);
        log.info("Testing with move {} and board hash {}", Move.convertIntToMove(sampleMove), liveHash);

        writeField(ai, "currentBestMove", sampleMove);
        writeField(ai, "bestMoveForHash", liveHash);
        writeField(ai, "searchResultReady", true);

        assertEquals(sampleMove, ai.getCurrentBestMoveInt(),
                "When the hash matches and a move is ready we expect to surface it");

        writeField(ai, "bestMoveForHash", liveHash ^ 0xFFFF);
        log.info("Injected stale hash {} to force rejection", liveHash ^ 0xFFFF);
        assertEquals(-1, ai.getCurrentBestMoveInt(),
                "Stale hash must invalidate the cached move");

        writeField(ai, "searchResultReady", false);
        assertEquals(-1, ai.getCurrentBestMoveInt(),
                "Without a ready result we must not expose the cached move");
    }

    @Test
    @DisplayName("updateBoardStateHash enqueues work only while calculation is active")
    void testUpdateBoardStateHashEnqueueBehaviour() throws Exception {
        Engine engine = new Engine();
        AI ai = new AI(engine, AiTuning.defaults());

        Object queue = readField(ai, "calculationRequests");
        ((java.util.concurrent.BlockingQueue<?>) queue).clear();

        ai.updateBoardStateHash();
        int activeEnqueued = ((java.util.concurrent.BlockingQueue<?>) queue).size();
        log.info("Queue size after active update: {}", activeEnqueued);
        assertEquals(1, activeEnqueued, "Active engine must enqueue a fresh calculation request");

        ((java.util.concurrent.BlockingQueue<?>) queue).clear();
        writeField(ai, "keepCalculating", false);
        ai.updateBoardStateHash();
        int inactiveEnqueued = ((java.util.concurrent.BlockingQueue<?>) queue).size();
        log.info("Queue size after disabled update: {}", inactiveEnqueued);
        assertEquals(0, inactiveEnqueued, "Disabled engine must skip queueing work");
    }

    @Test
    @DisplayName("performMove executes the cached move, resets state, and queues follow-up analysis")
    void testPerformMoveExecutesAndResetsState() throws Exception {
        Engine engine = new Engine();
        AI ai = new AI(engine, AiTuning.defaults());

        IntArrayList moves = engine.getAllLegalMoves();
        assertFalse(moves.isEmpty(), "Initial position must have legal moves");
        int move = moves.getInt(0);
        long hashBefore = engine.getBoardStateHash();
        log.info("Priming performMove with move {} on hash {}", Move.convertIntToMove(move), hashBefore);

        writeField(ai, "currentBestMove", move);
        writeField(ai, "bestMoveForHash", hashBefore);
        writeField(ai, "searchResultReady", true);

        Object queue = readField(ai, "calculationRequests");
        ((java.util.concurrent.BlockingQueue<?>) queue).clear();

        ai.performMove();

        MoveStack line = engine.getLine();
        assertEquals(1, line.size(), "Engine history must contain exactly one move after performMove");
        assertEquals(move, line.peek(), "The executed move must match the cached best move");

        assertEquals(-1, readField(ai, "currentBestMove"), "currentBestMove must be cleared after execution");
        assertEquals(-1L, readField(ai, "bestMoveForHash"), "bestMoveForHash must be cleared after execution");
        assertEquals(Boolean.FALSE, readField(ai, "searchResultReady"),
                "searchResultReady must be reset after executing the move");

        int queued = ((java.util.concurrent.BlockingQueue<?>) queue).size();
        log.info("Queue size after performMove triggered: {}", queued);
        assertEquals(1, queued, "Executing a move must trigger a fresh analysis request");
    }

    @Test
    @DisplayName("reset clears volatile state and restarts the main engine")
    void testResetClearsStateAndRestartsEngine() throws Exception {
        Engine engine = new Engine();
        AI ai = new AI(engine, AiTuning.defaults());

        long initialHash = engine.getBoardStateHash();
        engine.getAllLegalMoves();
        engine.performMove(engine.getAllLegalMoves().getInt(0));
        long mutatedHash = engine.getBoardStateHash();
        assertNotEquals(initialHash, mutatedHash, "Engine hash must change after a move to validate reset");

        writeField(ai, "currentBestMove", 12345);
        writeField(ai, "bestMoveForHash", mutatedHash);
        writeField(ai, "previousBestMove", 54321);
        writeField(ai, "previousBestMoveHash", mutatedHash);
        writeField(ai, "searchResultReady", true);
        writeField(ai, "calculatedLine", new java.util.ArrayList<>(java.util.List.of(new MoveAndScore(12345, 42.0))));

        ai.reset();

        assertEquals(-1, readField(ai, "currentBestMove"), "currentBestMove must reset to sentinel");
        assertEquals(-1L, readField(ai, "bestMoveForHash"), "bestMoveForHash must reset to sentinel");
        assertEquals(-1, readField(ai, "previousBestMove"), "previousBestMove must reset to sentinel");
        assertEquals(-1L, readField(ai, "previousBestMoveHash"), "previousBestMoveHash must reset to sentinel");
        assertEquals(Boolean.FALSE, readField(ai, "searchResultReady"), "Search readiness must be cleared");
        assertTrue(ai.getCalculatedLine().isEmpty(), "Calculated PV line must be cleared");

        long hashAfterReset = engine.getBoardStateHash();
        log.info("Hash snapshot -> initial: {}, post-move: {}, after reset: {}", initialHash, mutatedHash, hashAfterReset);
        assertEquals(initialHash, hashAfterReset,
                "Engine must restart to the initial position when reset is invoked");
    }

    private static int computeExpectedCapacity(int hashSizeMb, boolean mainTable)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
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

    private static final class InstrumentedTranspositionTable<V> implements TranspositionTable<V> {
        private static final Logger tableLog = LogManager.getLogger(InstrumentedTranspositionTable.class);
        private final ConcurrentHashMap<Long, V> store = new ConcurrentHashMap<>();
        private final AtomicInteger getCount = new AtomicInteger();
        private final AtomicInteger putCount = new AtomicInteger();
        private final AtomicInteger clearCount = new AtomicInteger();
        private final AtomicInteger advanceAgeCount = new AtomicInteger();

        @Override
        public V get(long key) {
            int n = getCount.incrementAndGet();
            tableLog.info("[TT] get #{} -> key {}", n, key);
            return store.get(key);
        }

        @Override
        public void put(long key, V value, int depth) {
            int n = putCount.incrementAndGet();
            tableLog.info("[TT] put #{} -> key {}, depth {}, value {}", n, key, depth, value);
            store.put(key, value);
        }

        @Override
        public void clear() {
            int n = clearCount.incrementAndGet();
            tableLog.info("[TT] clear #{} (size before = {})", n, store.size());
            store.clear();
        }

        @Override
        public int size() {
            return store.size();
        }

        @Override
        public void advanceAge() {
            int n = advanceAgeCount.incrementAndGet();
            tableLog.info("[TT] advanceAge #{}", n);
        }

        int getGetCount() {
            return getCount.get();
        }

        int getPutCount() {
            return putCount.get();
        }
    }
}
