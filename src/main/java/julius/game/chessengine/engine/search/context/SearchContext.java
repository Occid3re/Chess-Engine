package julius.game.chessengine.engine.search.context;

import julius.game.chessengine.ai.MoveAndScore;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.board.MoveHelper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Arrays;

/**
 * Container for per-search mutable state shared between all workers participating in a search.
 *
 * <p>The context exposes global heuristic tables that are prepared once per search/iteration and
 * merged with worker-local deltas, counters used for diagnostics and stop conditions, and the
 * principal-variation buffers published by the searcher.</p>
 */
public final class SearchContext {

    static final int SEE_CACHE_SIZE = 1 << 10; // 1024 entries
    static final int SEE_CACHE_MASK = SEE_CACHE_SIZE - 1;

    static final int NUM_KILLER_MOVES = 2;
    static final int MAX_MOVE_LIST_SIZE = 218; // legal move upper bound

    private final Object heuristicsLock = new Object();
    private final Heuristics globalHeuristics;

    private final ThreadLocal<WorkerContext> workerContexts;
    private final AtomicInteger workerIdGenerator = new AtomicInteger();

    private volatile long activeSearchId = Long.MIN_VALUE;
    private volatile int activeDepth = 1;

    private final AtomicLong nodesVisited = new AtomicLong();
    private final AtomicLong nullMoveCount = new AtomicLong();

    private final MoveList rootMoves = new MoveList();
    private final Deque<Double> iterationScores = new ArrayDeque<>();

    private volatile List<MoveAndScore> currentPrincipalVariation =
            Collections.synchronizedList(new ArrayList<>());
    private volatile List<MoveAndScore> lastCompletedPrincipalVariation = currentPrincipalVariation;

    public SearchContext(int initialDepth) {
        int depth = Math.max(1, initialDepth);
        this.globalHeuristics = new Heuristics(depth);
        this.workerContexts = ThreadLocal.withInitial(() ->
                new WorkerContext(workerIdGenerator.getAndIncrement(), depth));
        this.activeDepth = depth;
    }

    public SearchContext() {
        this(64);
    }

    public void beginSearch(long searchId, int depthCapacity) {
        this.activeSearchId = searchId;
        this.activeDepth = Math.max(1, depthCapacity);
        nodesVisited.set(0L);
        nullMoveCount.set(0L);
        iterationScores.clear();
        resetCurrentPrincipalVariation();
        synchronized (rootMoves) {
            rootMoves.clear();
        }
    }

    public WorkerContext worker() {
        WorkerContext ctx = workerContexts.get();
        ctx.attachToSearch(activeSearchId, activeDepth);
        return ctx;
    }

    public void prepareIteration(long taskId, WorkerContext worker, int depth, int requiredDepth,
                                 boolean decayHistory) {
        Objects.requireNonNull(worker, "worker");
        Heuristics heuristics = worker.heuristics();
        synchronized (heuristicsLock) {
            globalHeuristics.ensureCapacity(requiredDepth);
            if (decayHistory) {
                globalHeuristics.decayHistory();
            }
            heuristics.beginIteration(globalHeuristics, requiredDepth);
            heuristics.markPrepared(taskId, depth);
        }
    }

    public Heuristics prepareHeuristics(long taskId, WorkerContext worker, int depth, int maxDepth) {
        Objects.requireNonNull(worker, "worker");
        Heuristics heuristics = worker.heuristics();
        if (!heuristics.isPreparedFor(taskId, depth)) {
            synchronized (heuristicsLock) {
                globalHeuristics.ensureCapacity(maxDepth);
                heuristics.beginIteration(globalHeuristics, maxDepth);
                heuristics.markPrepared(taskId, depth);
            }
        }
        return heuristics;
    }

    public void mergeWorkerHeuristics(WorkerContext worker) {
        Objects.requireNonNull(worker, "worker");
        Heuristics heuristics = worker.heuristics();
        synchronized (heuristicsLock) {
            heuristics.mergeInto(globalHeuristics);
        }
        heuristics.resetUpdates();
    }

    public void ensureCapacity(int depth) {
        synchronized (heuristicsLock) {
            globalHeuristics.ensureCapacity(depth);
        }
        worker().heuristics().ensureCapacity(depth);
    }

    public void clearHistoryTables() {
        synchronized (heuristicsLock) {
            globalHeuristics.clearHistory();
            globalHeuristics.clearCounter();
        }
    }

    public int[][] snapshotKillers() {
        synchronized (heuristicsLock) {
            return globalHeuristics.snapshotKillers();
        }
    }

    public MoveList rootMoves() {
        return rootMoves;
    }

    public void recordIterationScore(double score) {
        iterationScores.addLast(score);
    }

    public Deque<Double> getIterationScores() {
        return iterationScores;
    }

    public void updatePrincipalVariation(List<MoveAndScore> pv) {
        if (pv == null) {
            clearPrincipalVariation();
            return;
        }
        List<MoveAndScore> snapshot = Collections.synchronizedList(new ArrayList<>(pv));
        currentPrincipalVariation = snapshot;
        lastCompletedPrincipalVariation = snapshot;
    }

    public void clearPrincipalVariation() {
        List<MoveAndScore> empty = Collections.synchronizedList(new ArrayList<>());
        currentPrincipalVariation = empty;
        lastCompletedPrincipalVariation = empty;
    }

    public void resetCurrentPrincipalVariation() {
        currentPrincipalVariation = Collections.synchronizedList(new ArrayList<>());
    }

    public List<MoveAndScore> getLastCompletedPrincipalVariation() {
        return lastCompletedPrincipalVariation;
    }

    public List<MoveAndScore> getCurrentPrincipalVariation() {
        return currentPrincipalVariation;
    }

    public long incrementNodesVisited() {
        return nodesVisited.incrementAndGet();
    }

    public long incrementNullMoveCount() {
        return nullMoveCount.incrementAndGet();
    }

    public long getNodesVisited() {
        return nodesVisited.get();
    }

    public long getNullMoveCount() {
        return nullMoveCount.get();
    }

    public void resetCounters() {
        nodesVisited.set(0L);
        nullMoveCount.set(0L);
    }

    /**
     * Mutable heuristic tables replicated per worker so updates can be accumulated locally and
     * merged into the shared baseline between iterations.
     */
    public static final class Heuristics {

        private static final int BOARD_SQUARES = 64;
        private static final int HISTORY_SIZE = BOARD_SQUARES * BOARD_SQUARES;

        private int[][] killers;
        private final int[][] history;
        private final int[][] continuation;
        private final int[][] counter;

        private boolean[] killerDirty;
        private int[] killerDirtyList;
        private int killerDirtyCount;

        private final int[] historyDelta;
        private final boolean[] historyDirty;
        private final int[] historyDirtyList;
        private int historyDirtyCount;

        private final int[] continuationDelta;
        private final boolean[] continuationDirty;
        private final int[] continuationDirtyList;
        private int continuationDirtyCount;

        private final int[] counterUpdates;
        private final boolean[] counterDirty;
        private final int[] counterDirtyList;
        private int counterDirtyCount;

        private long preparedTaskId = Long.MIN_VALUE;
        private int preparedDepth = -1;

        public Heuristics(int depth) {
            int d = Math.max(1, depth);
            this.killers = allocateKillers(d);
            this.history = new int[BOARD_SQUARES][BOARD_SQUARES];
            this.continuation = new int[BOARD_SQUARES][BOARD_SQUARES];
            this.counter = new int[BOARD_SQUARES][BOARD_SQUARES];
            for (int f = 0; f < BOARD_SQUARES; f++) {
                Arrays.fill(counter[f], -1);
            }
            this.killerDirty = new boolean[d];
            this.killerDirtyList = new int[d];
            this.historyDelta = new int[HISTORY_SIZE];
            this.historyDirty = new boolean[HISTORY_SIZE];
            this.historyDirtyList = new int[HISTORY_SIZE];
            this.continuationDelta = new int[HISTORY_SIZE];
            this.continuationDirty = new boolean[HISTORY_SIZE];
            this.continuationDirtyList = new int[HISTORY_SIZE];
            this.counterUpdates = new int[HISTORY_SIZE];
            Arrays.fill(counterUpdates, -1);
            this.counterDirty = new boolean[HISTORY_SIZE];
            this.counterDirtyList = new int[HISTORY_SIZE];
        }

        private static int[][] allocateKillers(int depth) {
            int[][] table = new int[depth][NUM_KILLER_MOVES];
            for (int i = 0; i < depth; i++) {
                Arrays.fill(table[i], -1);
            }
            return table;
        }

        public void ensureCapacity(int depth) {
            if (depth <= killers.length) {
                return;
            }
            int[][] expanded = allocateKillers(depth);
            for (int i = 0; i < killers.length; i++) {
                System.arraycopy(killers[i], 0, expanded[i], 0, killers[i].length);
            }
            killers = expanded;
            killerDirty = Arrays.copyOf(killerDirty, depth);
            killerDirtyList = Arrays.copyOf(killerDirtyList, depth);
        }

        public void beginIteration(Heuristics base, int requiredDepth) {
            resetUpdates();
            ensureCapacity(requiredDepth);
            base.ensureCapacity(requiredDepth);
            int limit = Math.min(requiredDepth, base.killers.length);
            for (int d = 0; d < limit; d++) {
                System.arraycopy(base.killers[d], 0, killers[d], 0, NUM_KILLER_MOVES);
            }
            for (int f = 0; f < BOARD_SQUARES; f++) {
                System.arraycopy(base.history[f], 0, history[f], 0, BOARD_SQUARES);
                System.arraycopy(base.continuation[f], 0, continuation[f], 0, BOARD_SQUARES);
                System.arraycopy(base.counter[f], 0, counter[f], 0, BOARD_SQUARES);
            }
        }

        public boolean isPreparedFor(long taskId, int depth) {
            return preparedTaskId == taskId && preparedDepth == depth;
        }

        public void markPrepared(long taskId, int depth) {
            this.preparedTaskId = taskId;
            this.preparedDepth = depth;
        }

        public void resetUpdates() {
            for (int i = 0; i < killerDirtyCount; i++) {
                killerDirty[killerDirtyList[i]] = false;
            }
            killerDirtyCount = 0;

            for (int i = 0; i < historyDirtyCount; i++) {
                int idx = historyDirtyList[i];
                historyDirty[idx] = false;
                historyDelta[idx] = 0;
            }
            historyDirtyCount = 0;

            for (int i = 0; i < continuationDirtyCount; i++) {
                int idx = continuationDirtyList[i];
                continuationDirty[idx] = false;
                continuationDelta[idx] = 0;
            }
            continuationDirtyCount = 0;

            for (int i = 0; i < counterDirtyCount; i++) {
                int idx = counterDirtyList[i];
                counterDirty[idx] = false;
                counterUpdates[idx] = -1;
            }
            counterDirtyCount = 0;
            preparedTaskId = Long.MIN_VALUE;
            preparedDepth = -1;
        }

        public boolean hasUpdates() {
            return killerDirtyCount > 0 || historyDirtyCount > 0
                    || continuationDirtyCount > 0 || counterDirtyCount > 0;
        }

        public void recordKiller(int depth, int move) {
            if (move == -1) {
                return;
            }
            int depthIndex = Math.max(0, Math.min(depth, killers.length - 1));
            int[] row = killers[depthIndex];
            for (int j : row) {
                if (j == move) {
                    return;
                }
            }
            for (int i = row.length - 1; i > 0; i--) {
                row[i] = row[i - 1];
            }
            row[0] = move;
            if (!killerDirty[depthIndex]) {
                killerDirty[depthIndex] = true;
                killerDirtyList[killerDirtyCount++] = depthIndex;
            }
        }

        public void addHistory(int move, int depth) {
            if (move == -1 || MoveHelper.isCapture(move)) {
                return;
            }
            int from = move & 0x3F;
            int to = (move >>> 6) & 0x3F;
            int delta = depth * depth;
            history[from][to] += delta;
            int idx = (from << 6) | to;
            if (!historyDirty[idx]) {
                historyDirty[idx] = true;
                historyDirtyList[historyDirtyCount++] = idx;
            }
            historyDelta[idx] += delta;
        }

        public void addContinuation(int prevMove, int move, int depth) {
            if (move == -1 || prevMove < 0) {
                return;
            }
            if (MoveHelper.isCapture(move) || MoveHelper.isPawnPromotionMove(move)) {
                return;
            }
            int prevTo = (prevMove >>> 6) & 0x3F;
            int to = (move >>> 6) & 0x3F;
            int delta = depth * depth;
            continuation[prevTo][to] += delta;
            int idx = (prevTo << 6) | to;
            if (!continuationDirty[idx]) {
                continuationDirty[idx] = true;
                continuationDirtyList[continuationDirtyCount++] = idx;
            }
            continuationDelta[idx] += delta;
        }

        public void recordCounterMove(int prevMove, int move) {
            if (prevMove < 0) {
                return;
            }
            int pf = prevMove & 0x3F;
            int pt = (prevMove >>> 6) & 0x3F;
            counter[pf][pt] = move;
            int idx = (pf << 6) | pt;
            if (!counterDirty[idx]) {
                counterDirty[idx] = true;
                counterDirtyList[counterDirtyCount++] = idx;
            }
            counterUpdates[idx] = move;
        }

        public void mergeInto(Heuristics target) {
            for (int i = 0; i < killerDirtyCount; i++) {
                int depth = killerDirtyList[i];
                target.ensureCapacity(depth + 1);
                int[] row = killers[depth];
                for (int move : row) {
                    if (move != -1) {
                        target.insertKiller(depth, move);
                    }
                }
            }
            for (int i = 0; i < historyDirtyCount; i++) {
                int idx = historyDirtyList[i];
                int from = idx >>> 6;
                int to = idx & 0x3F;
                target.history[from][to] += historyDelta[idx];
            }
            for (int i = 0; i < continuationDirtyCount; i++) {
                int idx = continuationDirtyList[i];
                int from = idx >>> 6;
                int to = idx & 0x3F;
                target.continuation[from][to] += continuationDelta[idx];
            }
            for (int i = 0; i < counterDirtyCount; i++) {
                int idx = counterDirtyList[i];
                int from = idx >>> 6;
                int to = idx & 0x3F;
                target.counter[from][to] = counterUpdates[idx];
            }
        }

        public void insertKiller(int depth, int move) {
            if (move == -1) {
                return;
            }
            int depthIndex = Math.max(0, Math.min(depth, killers.length - 1));
            int[] row = killers[depthIndex];
            for (int existing : row) {
                if (existing == move) {
                    return;
                }
            }
            for (int i = row.length - 1; i > 0; i--) {
                row[i] = row[i - 1];
            }
            row[0] = move;
        }

        public void decayHistory() {
            for (int f = 0; f < BOARD_SQUARES; f++) {
                for (int t = 0; t < BOARD_SQUARES; t++) {
                    history[f][t] >>= 1;
                    continuation[f][t] >>= 1;
                }
            }
        }

        public void clearHistory() {
            for (int f = 0; f < BOARD_SQUARES; f++) {
                Arrays.fill(history[f], 0);
                Arrays.fill(continuation[f], 0);
            }
            resetUpdates();
        }

        public void clearCounter() {
            for (int f = 0; f < BOARD_SQUARES; f++) {
                Arrays.fill(counter[f], -1);
            }
            for (int i = 0; i < counterDirtyCount; i++) {
                int idx = counterDirtyList[i];
                counterDirty[idx] = false;
                counterUpdates[idx] = -1;
            }
            counterDirtyCount = 0;
        }

        public int[][] snapshotKillers() {
            int[][] snapshot = new int[killers.length][];
            for (int i = 0; i < killers.length; i++) {
                snapshot[i] = Arrays.copyOf(killers[i], killers[i].length);
            }
            return snapshot;
        }
    }
}
