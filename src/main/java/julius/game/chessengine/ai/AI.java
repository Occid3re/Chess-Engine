package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.Move;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.tuning.AiTuning;
import julius.game.chessengine.utils.Score;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.StampedLock;

import static julius.game.chessengine.helper.BitHelper.FileMasks;
import static julius.game.chessengine.helper.KingHelper.KING_ATTACKS;
import static julius.game.chessengine.utils.Score.*;

@Log4j2
@Component
public class AI {

    @Getter
    private final Engine mainEngine;

    @Getter
    private final AiTuning tuning;

    /**
     * Number of threads used for searching. Defaults to single-threaded search but
     * can be adjusted at runtime via the UCI "Threads" option.
     */
    @Getter
    @Setter
    private int searchThreads = Integer.getInteger("chessengine.searchThreads", 1);

    // number of Lazy SMP workers (≥1)
    @Getter
    private int lazySmpThreads = Math.max(1, Integer.getInteger("chessengine.lazySmpThreads", 1));

    private Thread calculationCoordinator;
    private Thread[] calculationThreads;

    private static final int MAX_CHECK_EXTENSIONS_IN_A_ROW = 2;
    private static final int ABS_PLY_LIMIT_MARGIN = 32;

    private final AtomicReference<SearchTask> activeSearch = new AtomicReference<>();
    private final ThreadLocal<SearchTask> threadSearchTask = new ThreadLocal<>();
    private final AtomicLong searchIdGenerator = new AtomicLong();
    /**
     * Requested size of the transposition table in megabytes. The current
     * implementation does not dynamically resize the table, but the value is
     * tracked so that future improvements can honour it.
     */
    @Getter
    private int hashSizeMb = 16;

    /**
     * Estimated footprint per stored entry in the main and capture transposition tables.
     * The estimate accounts for the entry object itself as well as the surrounding table
     * structure (key arrays/atomic wrappers). Values are rounded up generously so the
     * engine never allocates more memory than requested.
     */
    private static final int MAIN_TT_ENTRY_BYTES = 48;
    private static final int CAPTURE_TT_ENTRY_BYTES = 32;

    /**
     * Entry limits enforced for each table. They are powers of two because the
     * underlying implementation rounds the capacity up to the next power of two.
     * Keeping the min/max explicit makes the Hash option predictable for UCI
     * front-ends.
     */
    private static final int MIN_MAIN_TT_ENTRIES = 1 << 12;      // 4k entries ≈ 192 KB
    private static final int MAX_MAIN_TT_ENTRIES = 1 << 26;      // 67,108,864 entries
    private static final int MIN_CAPTURE_TT_ENTRIES = 1 << 11;   // 2k entries ≈ 64 KB
    private static final int MAX_CAPTURE_TT_ENTRIES = 1 << 25;   // 33,554,432 entries

    private static final double MAIN_TT_WEIGHT = 2.0;
    private static final double CAPTURE_TT_WEIGHT = 1.0;

    public static final int MIN_HASH_SIZE_MB = 1;
    public static final int MAX_HASH_SIZE_MB = 4096;

    /**
     * Thread pool for root-split parallel search (created only if searchThreads > 1).
     */
    private final ExecutorService searchPool;

    /**
     * Limit how many root moves we fan out in parallel to avoid oversubscription.
     */
    private static final int ROOT_PARALLEL_LIMIT =
            Integer.getInteger("chessengine.rootParallelLimit", 24);

    public static final double EXIT_FLAG = Double.MAX_VALUE;

    /**
     * Fixed-size transposition table. Uses a non-atomic implementation when running
     * with a single search thread to avoid the overhead of atomic operations.
     */
    private TranspositionTable<TranspositionTableEntry> transpositionTable;

    @Getter
    private int transpositionTableCapacity;

    /**
     * Separate table for capture searches using the same fixed-size structure.
     */
    private TranspositionTable<CaptureTranspositionTableEntry> captureTranspositionTable;

    @Getter
    private int captureTranspositionTableCapacity;

    private static final int NUM_KILLER_MOVES = 2;

    /**
     * Global heuristic tables shared across searches. Search threads work with
     * thread-local copies that are periodically merged back into this
     * structure between iterative-deepening iterations.
     */
    private final Heuristics globalHeuristics;

    /**
     * Per-thread heuristic state used during move ordering. The tables are
     * initialised from {@link #globalHeuristics} at the beginning of each
     * iterative-deepening iteration, updated locally during the search and
     * merged back afterwards.
     */
    private final ThreadLocal<Heuristics> threadHeuristics;

    private final StampedLock heuristicsLock = new StampedLock();
    private final LockMetrics heuristicsLockMetrics = new LockMetrics();

    // Buffers used for move ordering. Reused across calls to avoid repeated
    // allocations when ordering moves.
    private static final int MAX_MOVE_LIST_SIZE = 218; // maximum legal moves

    private final ThreadLocal<SortBuffers> sortBuffers =
            ThreadLocal.withInitial(() -> new SortBuffers(MAX_MOVE_LIST_SIZE));
    private final ThreadLocal<Map<Integer, Integer>> seeCacheThreadLocal =
            ThreadLocal.withInitial(() -> new HashMap<>(64));

    private static final int LMR_MAX_DEPTH = 64;
    private static final int LMR_MAX_MOVES = MAX_MOVE_LIST_SIZE;
    private static final int HISTORY_BUCKETS = 5;
    private static final double[] HISTORY_BUCKET_NORMALIZED = new double[HISTORY_BUCKETS];
    private static final int[][][] LMR_REDUCTION_TABLE =
            new int[LMR_MAX_DEPTH + 1][LMR_MAX_MOVES][HISTORY_BUCKETS];

    static {
        if (HISTORY_BUCKETS < 1) {
            throw new IllegalStateException("History bucket count must be positive");
        }
        for (int bucket = 0; bucket < HISTORY_BUCKETS; bucket++) {
            HISTORY_BUCKET_NORMALIZED[bucket] = HISTORY_BUCKETS == 1
                    ? 0.0
                    : bucket / (double) (HISTORY_BUCKETS - 1);
        }
        for (int depth = 0; depth <= LMR_MAX_DEPTH; depth++) {
            for (int moveIndex = 0; moveIndex < LMR_MAX_MOVES; moveIndex++) {
                for (int bucket = 0; bucket < HISTORY_BUCKETS; bucket++) {
                    double base = Math.log(1 + depth) * Math.log(2 + moveIndex);
                    double historyWeight = 1.0 - 0.5 * HISTORY_BUCKET_NORMALIZED[bucket];
                    double scaled = base * historyWeight / 1.5;
                    int reduction = (int) Math.floor(scaled);
                    int maxReduction = Math.max(0, depth - 1);
                    if (reduction < 0) {
                        reduction = 0;
                    }
                    if (reduction > maxReduction) {
                        reduction = maxReduction;
                    }
                    LMR_REDUCTION_TABLE[depth][moveIndex][bucket] = reduction;
                }
            }
        }
    }

    private ScheduledExecutorService scheduler;

    private final BlockingQueue<CalculationRequest> calculationRequests = new LinkedBlockingQueue<>();
    private final BlockingQueue<SearchJob> searchJobs = new LinkedBlockingQueue<>();

    private static final class CalculationRequest {
        final long boardHash;
        final boolean stop;

        CalculationRequest(long boardHash, boolean stop) {
            this.boardHash = boardHash;
            this.stop = stop;
        }
    }

    private static final class SearchJob {
        final SearchTask task;
        final boolean stop;

        private SearchJob(SearchTask task, boolean stop) {
            this.task = task;
            this.stop = stop;
        }

        static SearchJob work(SearchTask task) {
            return new SearchJob(task, false);
        }

        static SearchJob stopSignal() {
            return new SearchJob(null, true);
        }
    }

    private volatile boolean keepCalculating = true;

    private volatile long currentBoardState = -1;
    private volatile long beforeCalculationBoardState = -2;

    private volatile int currentBestMove = -1;
    private volatile int previousBestMove = -1;
    private volatile long previousBestMoveHash = -1;
    private volatile boolean searchResultReady = false;

    private volatile long bestMoveForHash = -1;

    @Getter
    private List<MoveAndScore> calculatedLine = Collections.synchronizedList(new ArrayList<>());

    // Game configuration parameters

    @Getter
    private int maxDepth = 32; // Adjust the level of depth according to your requirements

    @Getter
    @Setter
    private long timeLimit; // milliseconds

    private boolean useNullMovePruning = Boolean.parseBoolean(
            System.getProperty("chessengine.nullMove", "true")
    );

    @Getter
    private long nodesVisited = 0;
    @Getter
    private long nullMoveCount = 0;

    public AI(Engine mainEngine) {
        this(mainEngine, AiTuning.defaults());
    }

    public AI(Engine mainEngine, AiTuning tuning) {
        this.mainEngine = Objects.requireNonNull(mainEngine, "mainEngine");
        this.tuning = tuning != null ? tuning : AiTuning.defaults();
        this.searchThreads = this.tuning.searchThreads();
        this.lazySmpThreads = Math.max(1, this.tuning.lazySmpThreads());
        this.hashSizeMb = this.tuning.hashSizeMb();
        this.maxDepth = this.tuning.maxDepth();
        this.timeLimit = this.tuning.timeLimitMillis();
        this.useNullMovePruning = this.tuning.nullMovePruning();

        log.info("### SearchThreads = " + searchThreads + ", LazySmpThreads = " + lazySmpThreads);

        this.globalHeuristics = new Heuristics(maxDepth);
        this.threadHeuristics = ThreadLocal.withInitial(() -> new Heuristics(maxDepth));

        rebuildTranspositionTables();

        this.searchPool = createSearchPool();

        this.mainEngine.setOnPositionChanged(h -> updateBoardStateHash());
    }

    private ExecutorService createSearchPool() {
        if (searchThreads <= 1) {
            return null;
        }
        return Executors.newFixedThreadPool(searchThreads, r -> {
            Thread t = new Thread(r, "AI-Search-" + System.identityHashCode(r));
            t.setDaemon(true);
            return t;
        });
    }

    private long acquireWriteLock() {
        long start = System.nanoTime();
        long stamp = heuristicsLock.writeLock();
        heuristicsLockMetrics.recordWriteAcquisition(System.nanoTime() - start);
        return stamp;
    }

    private long acquireReadLock() {
        long start = System.nanoTime();
        long stamp = heuristicsLock.readLock();
        heuristicsLockMetrics.recordReadAcquisition(System.nanoTime() - start);
        return stamp;
    }

    private void releaseWriteLock(long stamp) {
        heuristicsLock.unlockWrite(stamp);
    }

    private void releaseReadLock(long stamp) {
        heuristicsLock.unlockRead(stamp);
    }

    private Heuristics.Snapshot captureHeuristicsSnapshot(int requiredDepth) {
        while (true) {
            long stamp = heuristicsLock.tryOptimisticRead();
            if (stamp != 0L) {
                Heuristics.Snapshot optimistic = globalHeuristics.snapshot(requiredDepth);
                if (heuristicsLock.validate(stamp)) {
                    heuristicsLockMetrics.recordOptimisticSnapshot();
                    return optimistic;
                }
            }
            long readStamp = acquireReadLock();
            try {
                heuristicsLockMetrics.recordOptimisticFallback();
                return globalHeuristics.snapshot(requiredDepth);
            } finally {
                releaseReadLock(readStamp);
            }
        }
    }

    LockMetricsSnapshot snapshotHeuristicsLockMetrics() {
        return heuristicsLockMetrics.snapshot();
    }

    private void rebuildTranspositionTables() {
        boolean concurrent = Math.max(searchThreads, lazySmpThreads) > 1;
        long totalBytes = Math.max(1L, (long) hashSizeMb * 1024L * 1024L);

        double totalWeight = MAIN_TT_WEIGHT + CAPTURE_TT_WEIGHT;
        long mainBudget = Math.max(1L, (long) (totalBytes * (MAIN_TT_WEIGHT / totalWeight)));
        long captureBudget = Math.max(1L, totalBytes - mainBudget);
        if (captureBudget <= 0) {
            captureBudget = 1L;
            mainBudget = Math.max(1L, totalBytes - captureBudget);
        }

        int mainCapacity = computeTableCapacity(mainBudget, MAIN_TT_ENTRY_BYTES,
                MIN_MAIN_TT_ENTRIES, MAX_MAIN_TT_ENTRIES);
        int captureCapacity = computeTableCapacity(captureBudget, CAPTURE_TT_ENTRY_BYTES,
                MIN_CAPTURE_TT_ENTRIES, MAX_CAPTURE_TT_ENTRIES);

        this.transpositionTableCapacity = mainCapacity;
        this.captureTranspositionTableCapacity = captureCapacity;

        this.transpositionTable = concurrent
                ? new FixedSizeTranspositionTable<>(mainCapacity)
                : new PlainFixedSizeTranspositionTable<>(mainCapacity, TranspositionTableEntry.class);

        this.captureTranspositionTable = concurrent
                ? new FixedSizeTranspositionTable<>(captureCapacity)
                : new PlainFixedSizeTranspositionTable<>(captureCapacity, CaptureTranspositionTableEntry.class);
    }

    private static int computeTableCapacity(long budgetBytes, int entryBytes, int minEntries, int maxEntries) {
        if (entryBytes <= 0) {
            throw new IllegalArgumentException("Entry byte estimate must be positive");
        }

        long estimatedEntries = Math.max(1L, budgetBytes / entryBytes);
        if (estimatedEntries > Integer.MAX_VALUE) {
            estimatedEntries = Integer.MAX_VALUE;
        }

        int candidate = (int) estimatedEntries;
        if (candidate < minEntries) {
            candidate = minEntries;
        } else if (candidate > maxEntries) {
            candidate = maxEntries;
        }

        int rounded = roundUpToPowerOfTwo(candidate);
        if (rounded < minEntries) {
            rounded = minEntries;
        }
        while (rounded > maxEntries && rounded > 1) {
            rounded >>= 1;
        }
        return rounded;
    }

    private static int roundUpToPowerOfTwo(int value) {
        if (value <= 1) {
            return 1;
        }
        int highest = Integer.highestOneBit(value);
        if (highest == value) {
            return value;
        }
        if (highest > (1 << 30)) {
            return 1 << 30;
        }
        return highest << 1;
    }

    /**
     * Adjust the requested hash size (in megabytes) and rebuild the transposition tables.
     * Values outside the supported range are clamped between {@value MIN_HASH_SIZE_MB} MB
     * and {@value MAX_HASH_SIZE_MB} MB. The resulting table capacities are also limited
     * to {@value MIN_MAIN_TT_ENTRIES}/{@value MAX_MAIN_TT_ENTRIES} for the main table and
     * {@value MIN_CAPTURE_TT_ENTRIES}/{@value MAX_CAPTURE_TT_ENTRIES} for the capture table.
     */
    public synchronized void setHashSizeMb(int hashSizeMb) {
        int clamped = Math.max(MIN_HASH_SIZE_MB, Math.min(hashSizeMb, MAX_HASH_SIZE_MB));
        if (clamped == this.hashSizeMb) {
            return;
        }

        if (transpositionTable != null) {
            transpositionTable.clear();
        }
        if (captureTranspositionTable != null) {
            captureTranspositionTable.clear();
        }

        this.hashSizeMb = clamped;
        rebuildTranspositionTables();

        log.info("Hash size set to {} MB (main TT capacity: {}, capture TT capacity: {})",
                this.hashSizeMb, transpositionTableCapacity, captureTranspositionTableCapacity);
    }

    /**
     * Override the maximum depth for iterative deepening. The killer-move table is
     * grown on demand so deeper searches can proceed without being clamped by the
     * previous allocation. The requested depth is always respected (minimum 1).
     */
    public synchronized void setMaxDepth(int depth) {
        int requestedDepth = Math.max(1, depth);
        long stamp = acquireWriteLock();
        try {
            globalHeuristics.ensureCapacity(requestedDepth);
        } finally {
            releaseWriteLock(stamp);
        }
        threadHeuristics.get().ensureCapacity(requestedDepth);
        this.maxDepth = requestedDepth;
    }


    public Integer getCurrentBestMoveInt() {
        if (!searchResultReady) {
            return -1;
        }
        int move = currentBestMove;
        if (move == -1) {
            return -1;
        }

        long currentHash = mainEngine.getBoardStateHash();
        if (currentHash != bestMoveForHash) {
            return -1;
        }

        return move;
    }

    private void startCalculationThread() {
        keepCalculating = true;

        if (calculationCoordinator != null && calculationCoordinator.isAlive()) {
            enqueueCalculationRequest();
            return;
        }

        calculationRequests.clear();
        searchJobs.clear();

        calculationCoordinator = new Thread(this::calculateLine, "Simulator-Dispatcher");
        calculationCoordinator.setDaemon(true);
        calculationCoordinator.start();

        calculationThreads = new Thread[lazySmpThreads];
        for (int i = 0; i < lazySmpThreads; i++) {
            final int workerIndex = i;
            Thread worker = new Thread(() -> searchWorkerLoop(workerIndex), "Simulator-" + workerIndex);
            worker.setDaemon(true);
            worker.start();
            calculationThreads[i] = worker;
        }

        enqueueCalculationRequest();
    }

    private void searchWorkerLoop(int workerIndex) {
        Engine simulator;
        try {
            simulator = mainEngine.createSimulation();
        } catch (RuntimeException e) {
            log.error("Failed to create initial simulation for worker {}", workerIndex, e);
            return;
        }

        while (!Thread.currentThread().isInterrupted()) {
            SearchJob job;
            try {
                job = searchJobs.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (job.stop || !keepCalculating) {
                break;
            }

            SearchTask task = job.task;
            if (task == null) {
                continue;
            }

            try {
                simulator.copyFrom(task.getRootSnapshot());
            } catch (RuntimeException e) {
                log.error("Failed to sync simulation for worker {}", workerIndex, e);
                task.workerDone();
                continue;
            }

            SplittableRandom rng = lazySmpThreads > 1
                    ? new SplittableRandom(task.getBoardHash() ^ (0x9E3779B97F4A7C15L * (workerIndex + 1L)))
                    : null;

            threadSearchTask.set(task);
            try {
                iterativeDeepening(task, simulator, rng);
            } catch (Exception e) {
                log.error("Search worker {} encountered an error", workerIndex, e);
            } finally {
                threadSearchTask.remove();
                task.workerDone();
            }
        }
    }

    private void dispatchSearchTask(SearchTask task) {
        if (task == null) return;
        if (lazySmpThreads <= 0) {
            task.workerDone();
            return;
        }
        for (int i = 0; i < lazySmpThreads; i++) {
            searchJobs.offer(SearchJob.work(task));
        }
    }

    private void enqueueCalculationRequest() {
        if (!keepCalculating) {
            return;
        }
        long hash = mainEngine.getBoardStateHash();
        currentBoardState = hash;
        calculationRequests.clear();
        calculationRequests.offer(new CalculationRequest(hash, false));
    }

    private void iterativeDeepening(SearchTask task, Engine simulatorEngine, SplittableRandom rng) {
        Double lastIterScore = null;
        Heuristics heuristics = threadHeuristics.get();

        for (int currentDepth = 1; currentDepth <= maxDepth; currentDepth++) {
            if (shouldStopCalculating(task.getDeadline())) break;

            boolean firstAtDepth = task.beginIteration(currentDepth);
            prepareIterationState(task, heuristics, currentDepth, firstAtDepth);

            /**
             * Ply hint for distance-to-mate normalization (set per ID iteration).
             */
            MoveAndScore ms = null;

            double alpha = Double.NEGATIVE_INFINITY, beta = Double.POSITIVE_INFINITY;
            if (lastIterScore != null && currentDepth >= 3) {
                double window = 50.0;
                if (rng != null) window = Math.max(10.0, window + rng.nextDouble(-10.0, 10.0));
                alpha = lastIterScore - window;
                beta = lastIterScore + window;

                int retries = 0;
                while (!shouldStopCalculating(task.getDeadline())) {
                    ms = searchRootMoves(simulatorEngine, task, currentDepth, alpha, beta, rng);
                    if (ms == null) break;
                    if (ms.score <= alpha) {
                        window *= 2.0;
                        alpha = ms.score - window;
                        if (++retries > 3) {
                            alpha = Double.NEGATIVE_INFINITY;
                            beta = Double.POSITIVE_INFINITY;
                        } else continue;
                    }
                    if (ms.score >= beta) {
                        window *= 2.0;
                        beta = ms.score + window;
                        if (++retries > 3) {
                            alpha = Double.NEGATIVE_INFINITY;
                            beta = Double.POSITIVE_INFINITY;
                        } else continue;
                    }
                    break;
                }
            }

            if (ms == null) {
                ms = searchRootMoves(simulatorEngine, task, currentDepth, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, rng);
                if (ms == null) {
                    if (heuristics.hasUpdates()) {
                        mergeThreadHeuristics(heuristics);
                    }
                    break;
                }
            }

            lastIterScore = ms.score;
            task.publishBest(ms, currentDepth, simulatorEngine);
            if (heuristics.hasUpdates()) {
                mergeThreadHeuristics(heuristics);
            }
            if (task.isStopRequested()) break;
        }
    }

    private void prepareIterationState(SearchTask task, Heuristics heuristics, int currentDepth, boolean firstAtDepth) {
        long stamp = acquireWriteLock();
        try {
            globalHeuristics.ensureCapacity(maxDepth);
            if (firstAtDepth) {
                if (transpositionTable != null) {
                    transpositionTable.advanceAge();
                }
                if (captureTranspositionTable != null) {
                    captureTranspositionTable.advanceAge();
                }
                globalHeuristics.decayHistory();
            }
        } finally {
            releaseWriteLock(stamp);
        }
        Heuristics.Snapshot snapshot = captureHeuristicsSnapshot(maxDepth);
        heuristics.beginIteration(snapshot, maxDepth);
        heuristics.markPrepared(task.getId(), currentDepth);
    }

    private void mergeThreadHeuristics(Heuristics heuristics) {
        long stamp = acquireWriteLock();
        try {
            heuristics.mergeInto(globalHeuristics);
        } finally {
            releaseWriteLock(stamp);
        }
        heuristics.resetUpdates();
    }

    private Heuristics prepareHelperHeuristics(SearchTask task, int depth) {
        Heuristics heuristics = threadHeuristics.get();
        if (!heuristics.isPreparedFor(task.getId(), depth)) {
            long stamp = acquireWriteLock();
            try {
                globalHeuristics.ensureCapacity(maxDepth);
            } finally {
                releaseWriteLock(stamp);
            }
            Heuristics.Snapshot snapshot = captureHeuristicsSnapshot(maxDepth);
            heuristics.beginIteration(snapshot, maxDepth);
            heuristics.markPrepared(task.getId(), depth);
        }
        return heuristics;
    }

    protected MoveAndScore searchRootMoves(Engine sim, SearchTask task, int depth, double alpha, double beta, SplittableRandom rng) {
        if (searchThreads > 1) {
            return getBestMoveParallel(sim, task, depth, task.getDeadline(), alpha, beta, rng);
        }
        return getBestMove(sim, task.isWhiteToMove(), depth, task.getDeadline(), alpha, beta, rng);
    }

    public synchronized MoveAndScore searchBestMoveBlocking(long timeLimitMillis) {
        long previousTimeLimit = this.timeLimit;
        if (timeLimitMillis > 0) {
            this.timeLimit = timeLimitMillis;
        }
        try {
            Engine simulatorEngine = mainEngine.createSimulation();
            long boardStateHash = simulatorEngine.getBoardStateHash();
            long deadline = System.nanoTime() + this.timeLimit * 1_000_000L;

            SearchTask task = new SearchTask(
                    searchIdGenerator.incrementAndGet(),
                    boardStateHash,
                    simulatorEngine.whitesTurn(),
                    deadline,
                    1,
                    simulatorEngine.createSimulation()
            );

            activeSearch.set(task);
            currentBoardState = boardStateHash;
            beforeCalculationBoardState = boardStateHash;
            currentBestMove = -1;
            bestMoveForHash = -1;
            previousBestMove = -1;
            previousBestMoveHash = -1;
            searchResultReady = false;
            this.calculatedLine = Collections.synchronizedList(new ArrayList<>());

            SplittableRandom rng = (lazySmpThreads > 1)
                    ? new SplittableRandom(boardStateHash ^ System.nanoTime())
                    : null;

            threadSearchTask.set(task);
            try {
                iterativeDeepening(task, simulatorEngine, rng);
            } finally {
                threadSearchTask.remove();
            }

            task.workerDone();
            completeSearchTask(task, simulatorEngine);
            task.requestStop();

            if (searchResultReady && currentBestMove != -1 && bestMoveForHash == boardStateHash) {
                return new MoveAndScore(currentBestMove, task.getBest().score);
            }
            return null;
        } catch (RuntimeException ex) {
            log.error("Synchronous search failed", ex);
            return null;
        } finally {
            activeSearch.set(null);
            this.timeLimit = previousTimeLimit;
        }
    }


    public void reset() {
        stopCalculation();
        currentBestMove = -1;
        bestMoveForHash = -1;
        previousBestMove = -1;
        previousBestMoveHash = -1;
        searchResultReady = false;
        currentBoardState = -1;
        beforeCalculationBoardState = -2;
        calculatedLine = Collections.synchronizedList(new ArrayList<>());
        mainEngine.startNewGame();
        clearHistoryTable();
    }

    public void stopCalculation() {
        keepCalculating = false;

        SearchTask task = activeSearch.get();
        if (task != null) task.requestStop();

        calculationRequests.clear();
        searchJobs.clear();
        calculationRequests.offer(new CalculationRequest(0L, true));
        for (int i = 0; i < lazySmpThreads; i++) {
            searchJobs.offer(SearchJob.stopSignal());
        }

        if (calculationThreads != null) {
            for (Thread worker : calculationThreads) {
                if (worker != null && worker.isAlive()) worker.interrupt();
            }
        }
        if (calculationCoordinator != null && calculationCoordinator.isAlive()) {
            calculationCoordinator.interrupt();
        }

        if (calculationThreads != null) {
            for (Thread worker : calculationThreads) {
                if (worker == null) continue;
                try {
                    worker.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Thread interruption error", e);
                }
            }
            calculationThreads = null;
        }

        if (calculationCoordinator != null) {
            try {
                calculationCoordinator.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Thread interruption error", e);
            }
            calculationCoordinator = null;
        }

        activeSearch.set(null);
        calculatedLine = Collections.synchronizedList(new ArrayList<>());
        currentBestMove = -1;
        bestMoveForHash = -1;
        previousBestMove = -1;
        previousBestMoveHash = -1;
        searchResultReady = false;
    }


    public void startAutoPlay(boolean aiIsWhite, boolean aiIsBlack) {
        log.debug("timelimit is: " + timeLimit);
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow(); // Ensure previous scheduler is stopped
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();

        startCalculationThread();
        scheduler.scheduleAtFixedRate(() -> {
            log.debug("state: {}, keepCalculating: {}", mainEngine.getGameState().getState(), keepCalculating);
            if (mainEngine.getGameState().isGameOver() || !keepCalculating) {
                stopCalculation();
                scheduler.shutdown();
                return;
            }
            if ((aiIsWhite && mainEngine.whitesTurn()) || (aiIsBlack && !mainEngine.whitesTurn())) {
                if (searchResultReady && currentBestMove != -1 &&
                        bestMoveForHash == mainEngine.getBoardStateHash()) {
                    performMove();
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        stopCalculation();

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }

        if (searchPool != null) {
            searchPool.shutdown();
            try {
                if (!searchPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    searchPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                searchPool.shutdownNow();
            }
        }
    }

    public void performMove() {
        if (currentBestMove == -1) return;

        long now = mainEngine.getBoardStateHash();
        if (now != bestMoveForHash) {
            log.info("Stale best move for hash {}, current {}", bestMoveForHash, now);
            currentBestMove = -1;                           // <-- stop re-trying the stale move
            bestMoveForHash = -1;
            previousBestMove = -1;
            previousBestMoveHash = -1;
            searchResultReady = false;
            enqueueCalculationRequest();
            return;
        }

        if (MoveHelper.isWhitesMove(currentBestMove) != mainEngine.whitesTurn()) {
            log.info("Best move {} not for side to move", Move.convertIntToMove(currentBestMove));
            currentBestMove = -1;                           // (optional) also drop here
            bestMoveForHash = -1;
            previousBestMove = -1;
            previousBestMoveHash = -1;
            searchResultReady = false;
            return;
        }

        mainEngine.performMove(currentBestMove);
        enqueueCalculationRequest();
        currentBestMove = -1; // don’t re-play it
        bestMoveForHash = -1;
        previousBestMove = -1;
        previousBestMoveHash = -1;
        searchResultReady = false;
    }


    private void calculateLine() {
        long lastObservedHash = Long.MIN_VALUE;
        while (!Thread.currentThread().isInterrupted()) {
            CalculationRequest request;
            try {
                request = calculationRequests.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (request.stop || !keepCalculating) {
                break;
            }

            long targetHash = request.boardHash;
            if (targetHash == lastObservedHash) {
                continue;
            }

            currentBoardState = mainEngine.getBoardStateHash();
            beforeCalculationBoardState = currentBoardState;
            performCalculation();
            lastObservedHash = currentBoardState;
        }
    }


    private void performCalculation() {
        log.debug(" --- TranspositionTable[{}/{}] --- ", transpositionTable.size(), transpositionTableCapacity);

        try {
            Engine simulatorEngine = mainEngine.createSimulation();
            long boardStateHash = simulatorEngine.getBoardStateHash();
            log.debug("boardStateBeforeCalculation {}, currentBoardState {}", beforeCalculationBoardState, currentBoardState);

            boolean isWhite = simulatorEngine.whitesTurn();
            long deadline = System.nanoTime() + timeLimit * 1_000_000;
            beforeCalculationBoardState = boardStateHash;

            int bookMove = mainEngine.getOpeningBook().getRandomMoveForBoardStateHash(boardStateHash);
            if (bookMove != -1) {
                currentBestMove = bookMove;
                bestMoveForHash = boardStateHash;
                previousBestMove = bookMove;
                previousBestMoveHash = boardStateHash;
                searchResultReady = true;
                this.calculatedLine = List.of(new MoveAndScore(bookMove, 0.0));
                return;
            }

            SearchTask task = new SearchTask(searchIdGenerator.incrementAndGet(), boardStateHash, isWhite, deadline, lazySmpThreads, simulatorEngine);
            activeSearch.set(task);
            if (bestMoveForHash == boardStateHash && currentBestMove != -1) {
                previousBestMove = currentBestMove;
                previousBestMoveHash = bestMoveForHash;
            } else {
                previousBestMove = -1;
                previousBestMoveHash = -1;
            }
            currentBestMove = -1;
            bestMoveForHash = -1;
            searchResultReady = false;
            this.calculatedLine = Collections.synchronizedList(new ArrayList<>());
            dispatchSearchTask(task);
            task.awaitCompletion();
            completeSearchTask(task, simulatorEngine);
            task.requestStop();

        } catch (IllegalStateException e) {
            log.warn("Illegal board state during search: {}", e.getMessage());
            if (bestMoveForHash == mainEngine.getBoardStateHash() && currentBestMove != -1) {
                previousBestMove = currentBestMove;
                previousBestMoveHash = bestMoveForHash;
                searchResultReady = true;
            } else {
                currentBestMove = -1;
                bestMoveForHash = -1;
                previousBestMove = -1;
                previousBestMoveHash = -1;
                searchResultReady = false;
            }
        } catch (Exception e) {
            log.error("Unexpected error during calculation", e);
        } finally {
            activeSearch.set(null);
        }
    }

    void completeSearchTask(SearchTask task, Engine simulatorEngine) {
        if (task == null) return;
        if (currentBoardState != task.getBoardHash()) return;

        BestMoveDepth best = task.getBest();
        int move = best.move;

        if (move != -1) {
            currentBestMove = move;
            bestMoveForHash = task.getBoardHash();
            previousBestMove = move;
            previousBestMoveHash = task.getBoardHash();
            searchResultReady = true;
            fillCalculatedLine(simulatorEngine);
            return;
        }

        if (previousBestMove != -1 && previousBestMoveHash == task.getBoardHash() &&
                isMoveStillLegal(simulatorEngine, previousBestMove)) {
            currentBestMove = previousBestMove;
            bestMoveForHash = task.getBoardHash();
            previousBestMoveHash = task.getBoardHash();
            searchResultReady = true;
            fillCalculatedLine(simulatorEngine);
            return;
        }

        currentBestMove = -1;
        bestMoveForHash = -1;
        previousBestMove = -1;
        previousBestMoveHash = -1;
        searchResultReady = false;
        this.calculatedLine = Collections.synchronizedList(new ArrayList<>());
    }

    private boolean isMoveStillLegal(Engine simulatorEngine, int move) {
        IntArrayList legalMoves = simulatorEngine.getAllLegalMoves();
        return MoveContainerUtils.contains(legalMoves, move);
    }

    private void maybeRotateRootMoves(IntArrayList moves, SplittableRandom rng) {
        if (rng == null) return;
        int size = moves.size();
        if (size == 0) return;

        int bound = Math.min(size, 4);
        if (bound <= 1) return;

        int rotation = rng.nextInt(bound);
        if (rotation == 0) return;

        MoveContainerUtils.rotateLeft(moves, rotation);
    }

    private boolean abortRequested(long deadline) {
        if (Thread.currentThread().isInterrupted()) return true;
        if (System.nanoTime() > deadline) return true;
        if (positionChanged()) return true;
        SearchTask t = threadSearchTask.get();
        return t != null && t.isStopRequested();
    }

    private MoveAndScore getBestMoveParallel(Engine simulatorEngine,
                                             SearchTask task,
                                             int depth,
                                             long deadline,
                                             double alpha,
                                             double beta,
                                             SplittableRandom rng) {
        if (searchPool == null || abortRequested(deadline)) {
            return getBestMove(simulatorEngine, task.isWhiteToMove(), depth, deadline, alpha, beta, rng);
        }

        final boolean isWhitesTurn = task.isWhiteToMove();
        IntArrayList legal = simulatorEngine.getAllLegalMoves();
        IntArrayList orderedMoves = sortMovesByEfficiency(legal, depth, simulatorEngine.getBoardStateHash(), -1, simulatorEngine);
        if (orderedMoves.size() == 0) return null;
        maybeRotateRootMoves(orderedMoves, rng);

        int firstMove = orderedMoves.getInt(0);
        int bestMove = -1;
        double bestScore = isWhitesTurn ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;

        if (abortRequested(deadline)) return null;

        simulatorEngine.performMove(firstMove);
        double firstScore;
        if (simulatorEngine.getGameState().isInStateCheckMate()) {
            firstScore = isWhitesTurn ? (CHECKMATE - 1) : -(CHECKMATE - 1);
        } else if (simulatorEngine.getGameState().isInStateDraw()) {
            firstScore = evaluateStaticPosition(simulatorEngine.getGameState(), !isWhitesTurn, depth);
            if (isWhitesTurn) {
                firstScore = -firstScore;
            }
        } else {
            firstScore = alphaBeta(simulatorEngine, depth - 1, alpha, beta, !isWhitesTurn, deadline, firstMove, 1, 0);
            if (firstScore == EXIT_FLAG || abortRequested(deadline)) {
                simulatorEngine.undoLastMove();
                return null;
            }
        }
        simulatorEngine.undoLastMove();

        bestMove = firstMove;
        bestScore = firstScore;

        if (isWhitesTurn) alpha = Math.max(alpha, firstScore);
        else beta = Math.min(beta, firstScore);
        if (alpha >= beta) return new MoveAndScore(bestMove, bestScore);

        final int fanout = Math.min(ROOT_PARALLEL_LIMIT, orderedMoves.size() - 1);
        if (fanout <= 0) return new MoveAndScore(bestMove, bestScore);

        final CompletionService<MoveAndScore> ecs = new ExecutorCompletionService<>(searchPool);
        final List<Future<MoveAndScore>> futures = new ArrayList<>(fanout);

        final AtomicReference<Double> alphaRef = new AtomicReference<>(alpha);
        final AtomicReference<Double> betaRef = new AtomicReference<>(beta);
        final java.util.concurrent.atomic.AtomicInteger bestMoveRef = new java.util.concurrent.atomic.AtomicInteger(bestMove);
        final AtomicReference<Double> bestScoreRef = new AtomicReference<>(bestScore);
        final AtomicBoolean stopRef = new AtomicBoolean(false);
        final java.util.concurrent.locks.ReentrantLock fullResLock = new java.util.concurrent.locks.ReentrantLock();

        for (int i = 1; i <= fanout; i++) {
            final int moveInt = orderedMoves.getInt(i);
            futures.add(ecs.submit(() -> {
                if (stopRef.get() || abortRequested(deadline)) return null;
                Heuristics helperHeuristics = prepareHelperHeuristics(task, depth);
                try {
                    Engine e = simulatorEngine.createSimulation();
                    e.performMove(moveInt);

                    double currentAlpha = alphaRef.get();
                    double currentBeta = betaRef.get();
                    double pAlpha, pBeta;
                    if (isWhitesTurn) {
                        pAlpha = currentAlpha;
                        pBeta = currentAlpha + 1;
                    } else {
                        pAlpha = currentBeta - 1;
                        pBeta = currentBeta;
                    }

                    double probe;
                    if (e.getGameState().isInStateCheckMate()) {
                        probe = isWhitesTurn ? (CHECKMATE - 1) : -(CHECKMATE - 1);
                    } else if (e.getGameState().isInStateDraw()) {
                        probe = evaluateStaticPosition(e.getGameState(), !isWhitesTurn, depth);
                        if (isWhitesTurn) {
                            probe = -probe;
                        }
                    } else {
                        probe = alphaBeta(e, depth - 1, pAlpha, pBeta, !isWhitesTurn, deadline, moveInt, 1, 0);
                        if (probe == EXIT_FLAG || abortRequested(deadline)) return null;
                    }

                    boolean needsFull = isWhitesTurn ? (probe > alphaRef.get()) : (probe < betaRef.get());
                    double finalScore = probe;

                    if (needsFull && !stopRef.get()) {
                        fullResLock.lock();
                        try {
                            if (!stopRef.get() && !abortRequested(deadline)) {
                                double aNow = alphaRef.get(), bNow = betaRef.get();
                                double full = alphaBeta(e, depth - 1, aNow, bNow, !isWhitesTurn, deadline, moveInt, 1, 0);
                                if (full != EXIT_FLAG) {
                                    finalScore = full;
                                    if (isWhitesTurn) {
                                        if (full > aNow) alphaRef.set(full);
                                    } else {
                                        if (full < bNow) betaRef.set(full);
                                    }
                                    Double curBest = bestScoreRef.get();
                                    if (isBetterScore(isWhitesTurn, full, curBest)) {
                                        bestScoreRef.set(full);
                                        bestMoveRef.set(moveInt);
                                    }
                                    if (alphaRef.get() >= betaRef.get()) stopRef.set(true);
                                }
                            }
                        } finally {
                            fullResLock.unlock();
                        }
                    } else {
                        Double curBest = bestScoreRef.get();
                        if (isBetterScore(isWhitesTurn, finalScore, curBest)) {
                            bestScoreRef.set(finalScore);
                            bestMoveRef.set(moveInt);
                            if (isWhitesTurn && finalScore > alphaRef.get()) alphaRef.set(finalScore);
                            if (!isWhitesTurn && finalScore < betaRef.get()) betaRef.set(finalScore);
                            if (alphaRef.get() >= betaRef.get()) stopRef.set(true);
                        }
                    }

                    return new MoveAndScore(moveInt, finalScore);
                } finally {
                    if (helperHeuristics.hasUpdates()) {
                        mergeThreadHeuristics(helperHeuristics);
                    } else {
                        helperHeuristics.resetUpdates();
                    }
                }
            }));
        }

        int completed = 0;
        try {
            while (completed < fanout) {
                if (stopRef.get() || abortRequested(deadline)) break;
                Future<MoveAndScore> f = ecs.take();
                completed++;
                MoveAndScore res = f.get();
                if (res == null) continue;

                alpha = alphaRef.get();
                beta = betaRef.get();
                bestMove = bestMoveRef.get();
                bestScore = bestScoreRef.get();

                if (alpha >= beta) {
                    stopRef.set(true);
                    break;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warn("Parallel root YBWC error", ex);
        } finally {
            for (Future<MoveAndScore> f : futures) if (!f.isDone()) f.cancel(true);
        }

        return bestMove != -1 ? new MoveAndScore(bestMove, bestScore) : null;
    }


    private boolean shouldStopCalculating(long deadline) {
        return abortRequested(deadline);
    }

    private void fillCalculatedLine(Engine simulation) {
        long rootHash = simulation.getBoardStateHash();
        List<MoveAndScore> pv = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int movesPerformed = 0;

        // Helper to check legality
        java.util.function.IntPredicate isLegalNow = (mv) -> {
            IntArrayList legal = simulation.getAllLegalMoves();
            return MoveContainerUtils.contains(legal, mv);
        };

        // 1) Try to get a ROOT move (prefer EXACT, else accept LOWER/UPPER), else use currentBestMove, else first legal.
        TranspositionTableEntry rootEntry = transpositionTable.get(rootHash);
        int seedMove = -1;
        Double seedScore = null; // nullable to indicate "unknown"

        if (rootEntry != null && rootEntry.bestMove != -1 && MoveHelper.isWhitesMove(rootEntry.bestMove) == simulation.whitesTurn() && isLegalNow.test(rootEntry.bestMove)) {
            // Prefer EXACT; otherwise still accept to seed PV so it's not empty
            seedMove = rootEntry.bestMove;
            if (rootEntry.nodeType == NodeType.EXACT) {
                seedScore = rootEntry.score;
            }
        }

        if (seedMove == -1 && currentBestMove != -1 && MoveHelper.isWhitesMove(currentBestMove) == simulation.whitesTurn() && isLegalNow.test(currentBestMove)) {
            seedMove = currentBestMove;
            // score unknown; will remain null
        }

        if (seedMove == -1) {
            // Fallback: first legal move, if any
            IntArrayList legal = simulation.getAllLegalMoves();
            if (!legal.isEmpty()) {
                int mv = legal.getInt(0);
                if (MoveHelper.isWhitesMove(mv) == simulation.whitesTurn()) {
                    seedMove = mv;
                }
            }
        }

        // If still nothing, no PV can be constructed
        if (seedMove == -1) {
            this.calculatedLine = Collections.synchronizedList(new ArrayList<>());
            if (log.isDebugEnabled()) log.debug("PV empty: no root move available/legal.");
            return;
        }

        // Play the seed move
        pv.add(new MoveAndScore(seedMove, seedScore != null ? seedScore : 0.0));
        simulation.performMove(seedMove);
        movesPerformed++;
        long curHash = simulation.getBoardStateHash();

        // 2) Follow ONLY EXACT entries beyond root, with full validation
        while (true) {
            if (!seen.add(curHash)) break;

            TranspositionTableEntry e = transpositionTable.get(curHash);
            if (e == null || e.bestMove == -1 || e.nodeType != NodeType.EXACT) break;

            int mv = e.bestMove;

            // side-to-move must match and move must be legal now
            if (MoveHelper.isWhitesMove(mv) != simulation.whitesTurn()) break;
            if (!isLegalNow.test(mv)) break;

            pv.add(new MoveAndScore(mv, e.score));
            simulation.performMove(mv);
            movesPerformed++;
            curHash = simulation.getBoardStateHash();
        }

        // Undo simulation
        for (int i = 0; i < movesPerformed; i++) simulation.undoLastMove();

        this.calculatedLine = Collections.synchronizedList(pv);
    }


    private MoveAndScore getBestMove(Engine simulatorEngine, boolean isWhitesTurn, int depth, long deadline,
                                     double alpha, double beta, SplittableRandom rng) {
        int bestMove = -1;
        double bestScore = isWhitesTurn ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;

        IntArrayList sortedMoves = sortMovesByEfficiency(simulatorEngine.getAllLegalMoves(), depth,
                simulatorEngine.getBoardStateHash(), -1, simulatorEngine);
        maybeRotateRootMoves(sortedMoves, rng);

        for (int idx = 0; idx < sortedMoves.size(); idx++) {
            int moveInt = sortedMoves.getInt(idx);
            if (abortRequested(deadline)) break;

            simulatorEngine.performMove(moveInt);
            double score;
            if (simulatorEngine.getGameState().isInStateCheckMate()) {
                score = isWhitesTurn ? (CHECKMATE - 1) : -(CHECKMATE - 1);
            } else if (simulatorEngine.getGameState().isInStateDraw()) {
                score = evaluateStaticPosition(simulatorEngine.getGameState(), !isWhitesTurn, depth);
                if (isWhitesTurn) {
                    score = -score;
                }
            } else {
                score = alphaBeta(simulatorEngine, depth - 1, alpha, beta, !isWhitesTurn, deadline, moveInt, 1, 0);
                if (score == EXIT_FLAG || abortRequested(deadline)) {
                    simulatorEngine.undoLastMove();
                    break;
                }
            }
            simulatorEngine.undoLastMove();

            if (isBetterScore(isWhitesTurn, score, bestScore)) {
                bestScore = score;
                bestMove = moveInt;
            }
            if (isWhitesTurn) alpha = Math.max(alpha, score);
            else beta = Math.min(beta, score);
            if (alpha >= beta) break;
        }
        return bestMove != -1 ? new MoveAndScore(bestMove, bestScore) : null;
    }


    /**
     * *
     * 5rkr/pp2Rp2/1b1p1Pb1/3P2Q1/2n3P1/2p5/P4P2/4R1K1 w - - 1 0
     * *
     */
    // AI.java
    private double alphaBeta(Engine simulatorEngine, int depth, double alpha, double beta,
                             boolean isWhite, long deadline, int prevMove, int plyFromRoot,
                             int extStreak) {
        nodesVisited++;

        if (abortRequested(deadline)) return EXIT_FLAG;

        if (plyFromRoot >= maxDepth + ABS_PLY_LIMIT_MARGIN) {
            double eval = evaluateBoard(simulatorEngine, isWhite, deadline);
            if (eval == EXIT_FLAG) return EXIT_FLAG;
            if (!isWhite) eval = -eval;
            return eval;
        }

        boolean inCheck = isSideInCheck(simulatorEngine, isWhite);

        // Terminal states first, with distance-to-mate
        if (simulatorEngine.getGameState().isInStateCheckMate()) {
            double m = CHECKMATE - plyFromRoot;
            return isWhite ? -m : +m; // side-to-move is losing here
        }
        if (simulatorEngine.getGameState().isInStateDraw()) {
            return evaluateStaticPosition(simulatorEngine.getGameState(), isWhite, plyFromRoot);
        }

        // STRICT DEPTH MANAGEMENT:
        // Do NOT increase 'depth' at node entry. Child depth is non-increasing and
        // at most MAX_CHECK_EXTENSIONS_IN_A_ROW extensions may keep it equal to the
        // parent before we enforce a decrease.
        if (depth <= 0) {
            // Quiescence returns white-oriented score; flip for black-to-move
            double eval = evaluateBoard(simulatorEngine, isWhite, deadline);
            if (eval == EXIT_FLAG) return EXIT_FLAG;
            if (!isWhite) eval = -eval;
            return eval;
        }

        long boardHash = simulatorEngine.getBoardStateHash();

        // Transposition table lookup
        TranspositionTableEntry entry = transpositionTable.get(boardHash);
        if (entry != null && entry.depth >= depth) {
            if (entry.nodeType == NodeType.EXACT) return entry.score;
            if (entry.nodeType == NodeType.LOWERBOUND && entry.score > alpha) alpha = entry.score;
            else if (entry.nodeType == NodeType.UPPERBOUND && entry.score < beta) beta = entry.score;
            if (alpha >= beta) return entry.score;
        }

        // -------- Safer Null-move pruning (same as before, but use depthHere) --------
        IntArrayList moves = simulatorEngine.getAllLegalMoves();
        int mobility = moves.size();
        BitBoard bitBoard = simulatorEngine.getBitBoard();
        boolean allowNullMove = useNullMovePruning
                && !inCheck
                && !simulatorEngine.isEndgame()
                && prevMove != -1;

        if (allowNullMove) {
            double mateThreatScore = CHECKMATE - (plyFromRoot + 1);
            if ((isWhite && beta >= mateThreatScore) || (!isWhite && alpha <= -mateThreatScore)) {
                allowNullMove = false;
            }
        }

        if (allowNullMove) {
            int reduction = computeNullMoveReduction(bitBoard, depth, isWhite, mobility);
            int savedEp = simulatorEngine.doNullMoveForSearch();
            nullMoveCount++;
            double nullScore = alphaBeta(simulatorEngine, depth - 1 - reduction, alpha, beta, !isWhite, deadline, -1, plyFromRoot + 1, 0);
            simulatorEngine.undoNullMoveForSearch(savedEp);

            if (nullScore == EXIT_FLAG) return EXIT_FLAG;

            boolean nullFailHigh = isWhite ? nullScore >= beta : nullScore <= alpha;
            if (nullFailHigh) {
                double mateThreshold = CHECKMATE - (plyFromRoot + 1);
                double windowEdge = isWhite ? beta : alpha;
                double swingThreshold = Math.max(600, mateThreshold / 64.0);
                double swing = Double.isFinite(windowEdge) ? Math.abs(nullScore - windowEdge) : Math.abs(nullScore);
                boolean requiresVerification = Math.abs(nullScore) >= mateThreshold
                        || swing >= swingThreshold;

                if (requiresVerification) {
                    double verificationScore = alphaBeta(simulatorEngine, depth - 1, alpha, beta, isWhite, deadline, prevMove, plyFromRoot, 0);
                    if (verificationScore == EXIT_FLAG) return EXIT_FLAG;
                    if (Math.abs(verificationScore) < mateThreshold) {
                        nullFailHigh = isWhite ? verificationScore >= beta : verificationScore <= alpha;
                    } else {
                        nullFailHigh = false;
                    }
                }
            }

            if (nullFailHigh) {
                return isWhite ? beta : alpha;
            }
        }
        // ---------------------------------------------------------------------------

        double alphaOriginal = alpha;
        double betaOriginal = beta;

        if (isWhite) {
            return maximizer(simulatorEngine, depth, alpha, beta, isWhite, boardHash, alphaOriginal, moves, deadline, prevMove, plyFromRoot, extStreak);
        } else {
            return minimizer(simulatorEngine, depth, alpha, beta, isWhite, boardHash, betaOriginal, moves, deadline, prevMove, plyFromRoot, extStreak);
        }
    }


    private boolean isSideInCheck(Engine engine, boolean isWhite) {
        GameStateEnum state = engine.getGameState().getState();
        return (isWhite && state == GameStateEnum.WHITE_IN_CHECK) || (!isWhite && state == GameStateEnum.BLACK_IN_CHECK);
    }

    private boolean attacksOpponentQueenNow(Engine e, boolean moverIsWhite) {
        BitBoard bb = e.getBitBoard();
        long enemyQueen = moverIsWhite ? bb.getBlackQueens() : bb.getWhiteQueens();
        if (enemyQueen == 0) return false;
        long myAttacks = bb.getAttackBitboard(moverIsWhite);
        return (myAttacks & enemyQueen) != 0L;
    }

    private boolean attacksOpponentKingZone(Engine e, boolean moverIsWhite) {
        BitBoard bb = e.getBitBoard();
        long enemyKing = moverIsWhite ? bb.getBlackKing() : bb.getWhiteKing();
        if (enemyKing == 0L) {
            return false;
        }
        int kingIndex = Long.numberOfTrailingZeros(enemyKing);
        long kingZone = KING_ATTACKS[kingIndex];
        long myAttacks = bb.getAttackBitboard(moverIsWhite);
        return (myAttacks & kingZone) != 0L;
    }

    private int computeNullMoveReduction(BitBoard board, int depth, boolean isWhite, int mobility) {
        int maxReduction = depth - 2;
        if (maxReduction <= 0) {
            return 0;
        }

        long pieces = isWhite ? board.getWhitePieces() : board.getBlackPieces();
        long pawns = isWhite ? board.getWhitePawns() : board.getBlackPawns();
        int nonPawnMaterial = Long.bitCount(pieces) - Long.bitCount(pawns);
        if (nonPawnMaterial < 0) {
            nonPawnMaterial = 0;
        }

        double depthFactor = Math.min(depth, 10) / 10.0;
        double materialFactor = Math.min(nonPawnMaterial, 12) / 12.0;
        double mobilityFactor = Math.min(Math.max(mobility, 0), 30) / 30.0;

        double reductionEstimate = 1.25
                + (depthFactor * 1.5)
                + (materialFactor * 0.75)
                + (mobilityFactor * 0.5);

        if (nonPawnMaterial <= 2 || mobility <= 4) {
            reductionEstimate -= 0.75;
        }
        if (mobility <= 2) {
            reductionEstimate -= 0.5;
        }

        int reduction = (int) Math.floor(Math.max(0.0, reductionEstimate));
        return Math.min(reduction, maxReduction);
    }

    private int countPawnsOnFile(BitBoard board, long fileMask) {
        if (fileMask == 0L) {
            return 0;
        }
        long pawns = (board.getWhitePawns() | board.getBlackPawns()) & fileMask;
        return Long.bitCount(pawns);
    }

    private boolean openedFileTowardKing(BitBoard boardAfterMove, long kingFileMask,
                                         int pawnsBefore, boolean interactsWithKingFile) {
        if (!interactsWithKingFile || kingFileMask == 0L || pawnsBefore <= 0) {
            return false;
        }
        int pawnsAfter = countPawnsOnFile(boardAfterMove, kingFileMask);
        return pawnsAfter < pawnsBefore;
    }

    /**
     * LMR reduction: larger for deeper plies and later moves; tuned to be safe.
     */
    private static final int HISTORY_REDUCTION_MAX = 4000;

    private int lmrReduction(int depth, int moveIndex, int historyScore) {
        if (depth <= 1) {
            return 0;
        }

        int clampedDepth = Math.max(1, Math.min(depth, LMR_MAX_DEPTH));
        int clampedMoveIndex = Math.max(0, Math.min(moveIndex, LMR_MAX_MOVES - 1));

        int history = Math.max(0, Math.min(historyScore, HISTORY_REDUCTION_MAX));
        double normalized = HISTORY_REDUCTION_MAX == 0
                ? 0.0
                : history / (double) HISTORY_REDUCTION_MAX;
        double bucketPosition = normalized * (HISTORY_BUCKETS - 1);
        int lowerBucket = (int) Math.floor(bucketPosition);
        int upperBucket = Math.min(HISTORY_BUCKETS - 1, lowerBucket + 1);
        double fraction = bucketPosition - lowerBucket;

        int lowerValue = LMR_REDUCTION_TABLE[clampedDepth][clampedMoveIndex][lowerBucket];
        if (upperBucket == lowerBucket) {
            return Math.min(lowerValue, depth - 1);
        }

        int upperValue = LMR_REDUCTION_TABLE[clampedDepth][clampedMoveIndex][upperBucket];
        double interpolated = lowerValue + fraction * (upperValue - lowerValue);
        int reduction = (int) Math.floor(interpolated + 1e-9);
        int maxReduction = Math.max(0, depth - 1);
        if (reduction < 0) {
            reduction = 0;
        }
        if (reduction > maxReduction) {
            reduction = maxReduction;
        }
        return reduction;
    }

    private double maximizer(Engine simulatorEngine, int depth, double alpha, double beta,
                             boolean isWhite, long boardHash, double alphaOriginal,
                             IntArrayList moves, long deadline, int prevMove, int plyFromRoot,
                             int extStreak) {

        long start = log.isDebugEnabled() ? System.nanoTime() : 0L;
        double maxEval = Double.NEGATIVE_INFINITY;
        int bestMoveAtThisNode = -1;

        final boolean inCheckAtNode = isSideInCheck(simulatorEngine, isWhite);
        final Heuristics heuristics = threadHeuristics.get();
        final int[][] historyTable = heuristics.history;

        IntArrayList orderedMoves = sortMovesByEfficiency(moves, depth, boardHash, prevMove, simulatorEngine);
        final Map<Integer, Integer> seeCache = seeCacheThreadLocal.get();
        seeCache.clear();
        for (int index = 0; index < orderedMoves.size(); index++) {
            if (abortRequested(deadline)) {
                return EXIT_FLAG;
            }
            int move = orderedMoves.getInt(index);

            int from = MoveHelper.deriveFromIndex(move);
            int to = MoveHelper.deriveToIndex(move);
            int movingPieceBits = MoveHelper.derivePieceTypeBits(move);
            int capturedPieceBits = MoveHelper.deriveCapturedPieceTypeBits(move);
            boolean isCapture = MoveHelper.isCapture(move);
            boolean isPromotion = MoveHelper.isPawnPromotionMove(move);
            boolean isQuiet = !isCapture && !isPromotion;
            int historyScore = historyTable[from][to];

            int seeGain = 0;
            boolean seeEvaluated = false;
            boolean seeWinsMaterial = false;

            // SEE pruning for losing captures/quiets (keep checks/promotions)
            boolean seePruneCandidate = (!inCheckAtNode && isCapture && !isPromotion) || isQuiet;
            if (seePruneCandidate) {
                seeGain = seeCache.computeIfAbsent(move, simulatorEngine::see);
                seeEvaluated = true;
                if (seeGain < 0) {
                    simulatorEngine.performMove(move);
                    boolean givesCheckTmp = isSideInCheck(simulatorEngine, !isWhite);
                    simulatorEngine.undoLastMove();
                    if (!givesCheckTmp) {
                        continue;
                    }
                }
            }

            if (seeEvaluated) {
                seeWinsMaterial = seeGain > 0;
            }

            BitBoard boardBefore = simulatorEngine.getBitBoard();
            long enemyKingBB = isWhite ? boardBefore.getBlackKing() : boardBefore.getWhiteKing();
            int enemyKingSquare = enemyKingBB != 0L ? Long.numberOfTrailingZeros(enemyKingBB) : -1;
            int enemyKingFile = enemyKingSquare >= 0 ? (enemyKingSquare & 7) : -1;
            long kingFileMask = enemyKingFile >= 0 ? FileMasks[enemyKingFile] : 0L;
            boolean touchesKingFile = enemyKingSquare >= 0 && (((from & 7) == enemyKingFile) || ((to & 7) == enemyKingFile));
            boolean affectsKingFilePawns = touchesKingFile && (movingPieceBits == 1 || capturedPieceBits == 1);
            int pawnsOnFileBefore = (enemyKingSquare >= 0 && affectsKingFilePawns) ? countPawnsOnFile(boardBefore, kingFileMask) : 0;

            boolean isTactical = isCapture || isPromotion;
            int lmpThreshold = 8 + depth * 2;
            if (!inCheckAtNode && !isTactical && depth <= 3 && index > lmpThreshold) {
                simulatorEngine.performMove(move);
                boolean givesCheckTmp = isSideInCheck(simulatorEngine, !isWhite);
                boolean attacksQueenTmp = attacksOpponentQueenNow(simulatorEngine, isWhite);
                simulatorEngine.undoLastMove();
                if (!givesCheckTmp && !attacksQueenTmp) continue;
            }

            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();

            boolean givesCheck = isSideInCheck(simulatorEngine, !isWhite);
            boolean attacksQueen = attacksOpponentQueenNow(simulatorEngine, isWhite);
            boolean attacksKingZone = attacksOpponentKingZone(simulatorEngine, isWhite);
            boolean opensKingFile = openedFileTowardKing(simulatorEngine.getBitBoard(), kingFileMask, pawnsOnFileBefore, affectsKingFilePawns);

            int nextDepth = depth - 1;
            boolean forcing = givesCheck || attacksQueen;
            boolean allowExtend = forcing && extStreak < MAX_CHECK_EXTENSIONS_IN_A_ROW;
            if (allowExtend) nextDepth++;
            int nextExtStreak = allowExtend ? extStreak + 1 : 0;

            double eval;
            TranspositionTableEntry entry = transpositionTable.get(newBoardHash);
            boolean ttExactHit = entry != null
                    && entry.nodeType == NodeType.EXACT
                    && entry.depth >= nextDepth;

            if (ttExactHit) {
                eval = entry.score;
            } else {
                boolean canReduce = !inCheckAtNode
                        && !isTactical
                        && !givesCheck
                        && !attacksQueen
                        && !attacksKingZone
                        && !opensKingFile
                        && !seeWinsMaterial
                        && nextDepth >= 2
                        && index >= 3;

                if (plyFromRoot <= 1) {
                    canReduce = false;
                }

                boolean usePvs = index > 0 && alpha != Double.NEGATIVE_INFINITY && beta != Double.POSITIVE_INFINITY;
                double pAlpha = alpha;
                double pBeta = usePvs ? (alpha + 1) : beta;

                int reduction = 0;
                if (canReduce) {
                    reduction = lmrReduction(nextDepth, index, historyScore);
                    if (reduction <= 0) canReduce = false;
                }

                if (canReduce) {
                    int reduced = Math.max(1, nextDepth - reduction);
                    eval = alphaBeta(simulatorEngine, reduced, pAlpha, pBeta, !isWhite, deadline, move, plyFromRoot + 1, nextExtStreak);
                    if (eval == EXIT_FLAG || positionChanged()) {
                        simulatorEngine.undoLastMove();
                        return EXIT_FLAG;
                    }

                    boolean promising = eval > alpha;
                    if (promising) {
                        eval = alphaBeta(simulatorEngine, nextDepth, usePvs ? alpha : pAlpha, usePvs ? beta : pBeta,
                                !isWhite, deadline, move, plyFromRoot + 1, nextExtStreak);
                        if (eval == EXIT_FLAG || positionChanged()) {
                            simulatorEngine.undoLastMove();
                            return EXIT_FLAG;
                        }
                    }
                } else {
                    eval = alphaBeta(simulatorEngine, nextDepth, pAlpha, pBeta, !isWhite, deadline, move, plyFromRoot + 1, nextExtStreak);
                    if (eval == EXIT_FLAG || positionChanged()) {
                        simulatorEngine.undoLastMove();
                        return EXIT_FLAG;
                    }
                    if (usePvs && eval > alpha && eval < beta) {
                        eval = alphaBeta(simulatorEngine, nextDepth, alpha, beta, !isWhite, deadline, move, plyFromRoot + 1, nextExtStreak);
                        if (eval == EXIT_FLAG || positionChanged()) {
                            simulatorEngine.undoLastMove();
                            return EXIT_FLAG;
                        }
                    }
                }
            }

            if (log.isDebugEnabled()) {
                long endTime = System.nanoTime();
                log.debug("DEPTH: {} --- {}", depth, Move.convertIntToMove(move));
                log.debug("--> [+] Time taken for maximizer: {} ms", (endTime - start) / 1e6);
            }

            simulatorEngine.undoLastMove();

            if (eval > maxEval) {
                maxEval = eval;
                bestMoveAtThisNode = move;
            }

            alpha = Math.max(alpha, eval);
            if (beta <= alpha) {
                updateKillerMoves(depth, move);
                incrementHistory(move, depth);
                heuristics.recordCounterMove(prevMove, move);
                break;
            }
        }

        TranspositionTableEntry existingEntry = transpositionTable.get(boardHash);
        boolean shouldUpdate = existingEntry == null || existingEntry.depth < depth;

        if (maxEval <= alphaOriginal && shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(maxEval, depth, NodeType.UPPERBOUND, bestMoveAtThisNode), depth);
        } else if (maxEval >= beta && shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(maxEval, depth, NodeType.LOWERBOUND, bestMoveAtThisNode), depth);
        } else if (shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(maxEval, depth, NodeType.EXACT, bestMoveAtThisNode), depth);
        }

        return maxEval;
    }


    private double minimizer(Engine simulatorEngine, int depth, double alpha, double beta,
                             boolean isWhite, long boardHash, double betaOriginal,
                             IntArrayList moves, long deadline, int prevMove, int plyFromRoot,
                             int extStreak) {

        long start = log.isDebugEnabled() ? System.nanoTime() : 0L;
        double minEval = Double.POSITIVE_INFINITY;
        int bestMoveAtThisNode = -1;

        final boolean inCheckAtNode = isSideInCheck(simulatorEngine, isWhite);
        final Heuristics heuristics = threadHeuristics.get();
        final int[][] historyTable = heuristics.history;

        IntArrayList orderedMoves = sortMovesByEfficiency(moves, depth, boardHash, prevMove, simulatorEngine);
        final Map<Integer, Integer> seeCache = seeCacheThreadLocal.get();
        seeCache.clear();
        for (int index = 0; index < orderedMoves.size(); index++) {
            if (Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
                return EXIT_FLAG;
            }

            int move = orderedMoves.getInt(index);

            int from = MoveHelper.deriveFromIndex(move);
            int to = MoveHelper.deriveToIndex(move);
            int movingPieceBits = MoveHelper.derivePieceTypeBits(move);
            int capturedPieceBits = MoveHelper.deriveCapturedPieceTypeBits(move);
            boolean isCapture = MoveHelper.isCapture(move);
            boolean isPromotion = MoveHelper.isPawnPromotionMove(move);
            boolean isQuiet = !isCapture && !isPromotion;
            int historyScore = historyTable[from][to];

            int seeGain = 0;
            boolean seeEvaluated = false;
            boolean seeWinsMaterial = false;

            boolean seePruneCandidate = (!inCheckAtNode && isCapture && !isPromotion) || isQuiet;
            if (seePruneCandidate) {
                seeGain = seeCache.computeIfAbsent(move, simulatorEngine::see);
                seeEvaluated = true;
                if (seeGain < 0) {
                    simulatorEngine.performMove(move);
                    boolean givesCheckTmp = isSideInCheck(simulatorEngine, !isWhite);
                    simulatorEngine.undoLastMove();
                    if (!givesCheckTmp) {
                        continue;
                    }
                }
            }

            if (seeEvaluated) {
                seeWinsMaterial = seeGain > 0;
            }

            BitBoard boardBefore = simulatorEngine.getBitBoard();
            long enemyKingBB = isWhite ? boardBefore.getBlackKing() : boardBefore.getWhiteKing();
            int enemyKingSquare = enemyKingBB != 0L ? Long.numberOfTrailingZeros(enemyKingBB) : -1;
            int enemyKingFile = enemyKingSquare >= 0 ? (enemyKingSquare & 7) : -1;
            long kingFileMask = enemyKingFile >= 0 ? FileMasks[enemyKingFile] : 0L;
            boolean touchesKingFile = enemyKingSquare >= 0 && (((from & 7) == enemyKingFile) || ((to & 7) == enemyKingFile));
            boolean affectsKingFilePawns = touchesKingFile && (movingPieceBits == 1 || capturedPieceBits == 1);
            int pawnsOnFileBefore = (enemyKingSquare >= 0 && affectsKingFilePawns) ? countPawnsOnFile(boardBefore, kingFileMask) : 0;

            boolean isTactical = isCapture || isPromotion;

            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();

            boolean givesCheck = isSideInCheck(simulatorEngine, !isWhite);
            boolean attacksQueen = attacksOpponentQueenNow(simulatorEngine, isWhite);
            boolean attacksKingZone = attacksOpponentKingZone(simulatorEngine, isWhite);
            boolean opensKingFile = openedFileTowardKing(simulatorEngine.getBitBoard(), kingFileMask, pawnsOnFileBefore, affectsKingFilePawns);

            int nextDepth = depth - 1;
            boolean forcing = givesCheck || attacksQueen;
            boolean allowExtend = forcing && extStreak < MAX_CHECK_EXTENSIONS_IN_A_ROW;
            if (allowExtend) nextDepth++;
            int nextExtStreak = allowExtend ? extStreak + 1 : 0;

            double eval;
            TranspositionTableEntry entry = transpositionTable.get(newBoardHash);
            boolean ttExactHit = entry != null
                    && entry.nodeType == NodeType.EXACT
                    && entry.depth >= nextDepth;

            if (ttExactHit) {
                eval = entry.score;
            } else {
                boolean canReduce = !inCheckAtNode
                        && !isTactical
                        && !givesCheck
                        && !attacksQueen
                        && !attacksKingZone
                        && !opensKingFile
                        && !seeWinsMaterial
                        && nextDepth >= 2
                        && index >= 3;

                if (plyFromRoot <= 1) {
                    canReduce = false;
                }

                boolean usePvs = index > 0 && alpha != Double.NEGATIVE_INFINITY && beta != Double.POSITIVE_INFINITY;
                double pAlpha = usePvs ? (beta - 1) : alpha;
                double pBeta = beta;

                int reduction = 0;
                if (canReduce) {
                    reduction = lmrReduction(nextDepth, index, historyScore);
                    if (reduction <= 0) canReduce = false;
                }

                if (canReduce) {
                    int reduced = Math.max(1, nextDepth - reduction);
                    eval = alphaBeta(simulatorEngine, reduced, pAlpha, pBeta, !isWhite, deadline, move, plyFromRoot + 1, nextExtStreak);
                    if (eval == EXIT_FLAG || positionChanged()) {
                        simulatorEngine.undoLastMove();
                        return EXIT_FLAG;
                    }

                    boolean promising = eval < beta;
                    if (promising) {
                        eval = alphaBeta(simulatorEngine, nextDepth, usePvs ? alpha : pAlpha, usePvs ? beta : pBeta,
                                !isWhite, deadline, move, plyFromRoot + 1, nextExtStreak);
                        if (eval == EXIT_FLAG || positionChanged()) {
                            simulatorEngine.undoLastMove();
                            return EXIT_FLAG;
                        }
                    }
                } else {
                    eval = alphaBeta(simulatorEngine, nextDepth, pAlpha, pBeta, !isWhite, deadline, move, plyFromRoot + 1, nextExtStreak);
                    if (eval == EXIT_FLAG || positionChanged()) {
                        simulatorEngine.undoLastMove();
                        return EXIT_FLAG;
                    }

                    if (usePvs && eval > alpha && eval < beta) {
                        eval = alphaBeta(simulatorEngine, nextDepth, alpha, beta, !isWhite, deadline, move, plyFromRoot + 1, nextExtStreak);
                        if (eval == EXIT_FLAG || positionChanged()) {
                            simulatorEngine.undoLastMove();
                            return EXIT_FLAG;
                        }
                    }
                }
            }

            if (log.isDebugEnabled()) {
                long endTime = System.nanoTime();
                log.debug("DEPTH: {} --- {}", depth, Move.convertIntToMove(move));
                log.debug("<-- [-] Time taken for minimizer: {} ms", (endTime - start) / 1e6);
            }

            simulatorEngine.undoLastMove();

            if (eval < minEval) {
                minEval = eval;
                bestMoveAtThisNode = move;
            }

            beta = Math.min(beta, eval);
            if (alpha >= beta) {
                updateKillerMoves(depth, move);
                incrementHistory(move, depth);
                heuristics.recordCounterMove(prevMove, move);
                break;
            }
        }

        TranspositionTableEntry existingEntry = transpositionTable.get(boardHash);
        boolean shouldUpdate = existingEntry == null || existingEntry.depth < depth;

        if (minEval >= betaOriginal && shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(minEval, depth, NodeType.LOWERBOUND, bestMoveAtThisNode), depth);
        } else if (minEval <= alpha && shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(minEval, depth, NodeType.UPPERBOUND, bestMoveAtThisNode), depth);
        } else if (shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(minEval, depth, NodeType.EXACT, bestMoveAtThisNode), depth);
        }

        return minEval;
    }


    /**
     * Orders moves using a combination of transposition-table hints, promotions,
     * SEE-aware capture sorting, killer moves and history heuristics. Static
     * Exchange Evaluation (SEE) is used to distinguish between winning and
     * losing captures: winning trades (positive SEE) are promoted ahead of
     * neutral/losing captures while negative SEE trades are demoted within
     * their capture bucket. Results are cached per move within this ordering
     * pass so repeated SEE queries are avoided.
     */
    IntArrayList sortMovesByEfficiency(IntArrayList moves, int currentDepth, long boardHash, int prevMove,
                                       Engine simulatorEngine) {
        final int size = moves.size();
        final Map<Integer, Integer> seeCache = seeCacheThreadLocal.get();
        seeCache.clear();

        if (size == 0) {
            return moves;
        }

        final SortBuffers buffers = sortBuffers.get();
        final int[] moveBuffer = buffers.moveBuffer;
        final int[] scoreBuffer = buffers.scoreBuffer;
        final long[] sortKeys = buffers.sortKeyBuffer;

        final Heuristics heuristics = threadHeuristics.get();
        final int[][] killerMoves = heuristics.killers;
        final int[][] historyTable = heuristics.history;
        final int[][] counterMove = heuristics.counter;

        final int depthIndex = Math.max(0, Math.min(currentDepth, killerMoves.length - 1));

        // Category encoding (higher is earlier):
        // 7: TT move, 6: promotions, 5: good captures, 4: equal captures,
        // 3: killer[0], 2: killer[1], 1: quiets (history), 0: bad captures
        final int CAT_TT = 7, CAT_PROMO = 6, CAT_CAP_GOOD = 5, CAT_CAP_EQUAL = 4,
                CAT_KILLER0 = 3, CAT_KILLER1 = 2, CAT_QUIET = 1, CAT_CAP_BAD = 0;

        // Lightweight bonuses (local so no class changes):
        final int PROMOTION_ORDER_BONUS = 900;   // strong push for promotions
        final int KILLER0_BONUS = 50;            // distinguish first vs second killer
        final int KILLER1_BONUS = 30;

        // Hash move (TT) handling — keep your "pin to front" approach
        TranspositionTableEntry ttEntry = transpositionTable.get(boardHash);
        final int ttMove = ttEntry != null ? ttEntry.bestMove : -1;
        int ttIndex = -1;

        // Pre-fetch killers for this depth
        final int k0 = killerMoves[depthIndex][0];
        final int k1 = killerMoves[depthIndex][1];

        final int prevFrom = (prevMove >= 0) ? (prevMove & 0x3F) : -1;
        final int prevTo = (prevMove >= 0) ? ((prevMove >>> 6) & 0x3F) : -1;
        final int cm = (prevFrom >= 0) ? counterMove[prevFrom][prevTo] : -1;
        final int COUNTER_MOVE_BONUS = 400;

        for (int i = 0; i < size; i++) {
            final int moveInt = moves.getInt(i);

            // Track TT move position for the "pin to front" trick
            if (moveInt == ttMove) {
                ttIndex = i;
            }

            // Compute base features
            final boolean isCapture = MoveHelper.isCapture(moveInt);
            final boolean isPromotion = MoveHelper.isPawnPromotionMove(moveInt);

            int seeValue = 0;
            boolean hasSee = false;
            if (isCapture) {
                seeValue = seeCache.computeIfAbsent(moveInt, simulatorEngine::see);
                hasSee = true;
            }

            int category;
            int score;

            if (moveInt == ttMove) {
                // TT move gets the top category; score acts as tie-breaker only
                category = CAT_TT;
                score = Integer.MAX_VALUE; // ensure it stays first within its bucket
            } else if (isPromotion) {
                // Promotions are extremely forcing — sort before captures
                category = CAT_PROMO;
                int base = calculateMvvLvaScore(moveInt); // promotion-captures benefit, quiet promos keep 0
                int seeBonus = 0;
                if (hasSee) {
                    int cappedSee = Math.max(-512, Math.min(512, seeValue));
                    seeBonus = cappedSee * 16; // modest SEE influence within promotion bucket
                }
                score = base + PROMOTION_ORDER_BONUS + seeBonus;
            } else if (isCapture) {
                // MVV-LVA for captures; classify as good/equal/bad without SEE
                final int mvvLva = calculateMvvLvaScore(moveInt); // victim - attacker (can be negative)
                if (seeValue > 0) {
                    category = CAT_CAP_GOOD;
                } else if (seeValue == 0) {
                    category = CAT_CAP_EQUAL;
                } else {
                    category = CAT_CAP_BAD;
                }
                // Scale captures so bigger victims / smaller attackers bubble up
                int cappedSee = Math.max(-2048, Math.min(2048, seeValue));
                score = (mvvLva * 16) + (cappedSee * 32);
                if (score < 0) {
                    score = 0;
                }
            } else if (moveInt == k0) {
                category = CAT_KILLER0;
                score = KILLER_MOVE_SCORE + KILLER0_BONUS;
            } else if (moveInt == k1) {
                category = CAT_KILLER1;
                score = KILLER_MOVE_SCORE + KILLER1_BONUS;
            } else {
                // Quiet with history
                final int from = moveInt & 0x3F;
                final int to = (moveInt >>> 6) & 0x3F;
                category = CAT_QUIET;
                score = historyTable[from][to]; // butterfly history
                if (moveInt == cm) score += COUNTER_MOVE_BONUS;
            }

            // Persist (kept for compatibility with your buffers)
            moveBuffer[i] = moveInt;
            scoreBuffer[i] = score;

            // Compose a sortable 64-bit key:
            // [8 bits category][24 bits score clamped to unsigned][32 bits move id]
            int s = score;
            if (s < 0) s = 0;
            else if (s > 0x00FFFFFF) s = 0x00FFFFFF; // clamp to 24 bits
            long key = (((long) category) << 56) | (((long) s) << 32) | (moveInt & 0xFFFFFFFFL);
            sortKeys[i] = key;
        }

        // Keep TT move hard-pinned at the front (index 0), sort the remainder by key
        int sortStart = 0;
        if (ttIndex > 0) {
            long ttCombined = sortKeys[ttIndex];
            System.arraycopy(sortKeys, 0, sortKeys, 1, ttIndex);
            sortKeys[0] = ttCombined;
            sortStart = 1;
        }

        Arrays.sort(sortKeys, sortStart, size); // ascending by key

        // Build result in descending order (bigger category/score first)
        int outIndex = 0;
        if (ttIndex != -1) {
            moveBuffer[outIndex++] = (int) (sortKeys[0] & 0xFFFFFFFFL);
            for (int i = size - 1; i >= 1; i--) {
                moveBuffer[outIndex++] = (int) (sortKeys[i] & 0xFFFFFFFFL);
            }
        } else {
            for (int i = size - 1; i >= 0; i--) {
                moveBuffer[outIndex++] = (int) (sortKeys[i] & 0xFFFFFFFFL);
            }
        }

        MoveContainerUtils.overwriteFromBuffer(moves, moveBuffer, size);

        return moves;
    }


    public double evaluateBoard(Engine simulatorEngine, boolean isWhitesTurn, long deadline) {
        if (simulatorEngine.getGameState().isInStateCheckMate()) {
            // Side to move has no legal moves and is in check → losing for side to move
            return -CHECKMATE; // alphaBeta handles mate distance; this path is rarely hit
        }

        if (simulatorEngine.getGameState().isInStateDraw()) {
            double scoreDiff = simulatorEngine.getGameState().getScore().getScoreDifference();
            // stronger bias than ±0.01 to steer away from draws when ahead
            final double DRAW_BIAS = 0.20;
            if ((isWhitesTurn && scoreDiff > 0) || (!isWhitesTurn && scoreDiff < 0)) {
                return DRAW - DRAW_BIAS; // discourage draws when ahead
            } else if ((isWhitesTurn && scoreDiff < 0) || (!isWhitesTurn && scoreDiff > 0)) {
                return DRAW + DRAW_BIAS; // prefer draws when behind
            }
            return DRAW;
        }

        double alpha = Double.NEGATIVE_INFINITY;
        double beta = Double.POSITIVE_INFINITY;

        long boardStateHash = simulatorEngine.getBoardStateHash();
        CaptureTranspositionTableEntry entry = captureTranspositionTable.get(boardStateHash);

        // Check if the entry exists and is relevant for the current search
        if (entry != null && entry.isWhite() == isWhitesTurn) {
            return entry.getScore();
        }

        double score = quiescenceSearch(simulatorEngine, isWhitesTurn, alpha, beta, deadline, 0);

        // don't cache timeouts in the capture TT (qsearch TT)
        if (score != EXIT_FLAG) {
            captureTranspositionTable.put(boardStateHash, new CaptureTranspositionTableEntry(score, isWhitesTurn), 0);
        }

        return score;
    }

    private double quiescenceSearch(Engine simulatorEngine, boolean isWhitesTurn,
                                    double alpha, double beta, long deadline, int depth) {
        // early stop
        if (abortRequested(deadline)) {
            return AI.EXIT_FLAG;
        }

        // If side to move is in check, search all legal evasions (not only captures)
        boolean inCheck = isSideInCheck(simulatorEngine, isWhitesTurn);

        double standPat = evaluateStaticPosition(simulatorEngine.getGameState(), isWhitesTurn, depth);
        if (!inCheck) {
            if (standPat >= beta) {
                return beta; // fail-hard beta
            }
            if (alpha < standPat) {
                alpha = standPat; // raise alpha via stand-pat
            }

            // Simple delta/futility-like guard: if even a big swing cannot beat alpha, cut
            final int BIG_DELTA = 1000; // ~queen
            if (standPat + BIG_DELTA < alpha) {
                return alpha;
            }
        }

        // Generate moves: evasions if in check, else captures/promotions
        IntArrayList moves = inCheck ? simulatorEngine.getAllLegalMoves() : getPossibleCapturesOrPromotions(simulatorEngine);

        // Order them (captures first via MVV-LVA/promotion bonus, killers/history still help)
        IntArrayList ordered = sortMovesByEfficiency(moves, 0, simulatorEngine.getBoardStateHash(), -1,
                simulatorEngine);

        for (int i = 0; i < ordered.size(); i++) {
            int m = ordered.getInt(i);
            boolean isCapture = MoveHelper.isCapture(m);
            boolean isPromotion = MoveHelper.isPawnPromotionMove(m);
            boolean isQuiet = !isCapture && !isPromotion;

            // --- SEE pruning: drop clearly losing captures or quiet moves (keeps promotions) ---
            if ((!inCheck && isCapture && !isPromotion) || isQuiet) {
                int see = simulatorEngine.see(m);
                if (see < 0) {
                    simulatorEngine.performMove(m);
                    boolean givesCheck = isSideInCheck(simulatorEngine, !isWhitesTurn);
                    simulatorEngine.undoLastMove();
                    if (!givesCheck) continue;
                }
            }
            simulatorEngine.performMove(m);
            // Propagate timeout BEFORE negation
            double child = quiescenceSearch(simulatorEngine, !isWhitesTurn, -beta, -alpha, deadline, depth + 1);
            simulatorEngine.undoLastMove();

            if (child == EXIT_FLAG) return EXIT_FLAG;

            double score = -child;

            if (score >= beta) {
                return beta;
            }
            if (score > alpha) {
                alpha = score;
            }
        }
        return alpha;
    }

    private double evaluateStaticPosition(GameState gameState, boolean isWhitesTurn, int depthOrPly) {

        if (gameState.isInStateCheckMate()) {
            return -(CHECKMATE - depthOrPly);
        }
        if (gameState.isInStateDraw()) {
            if (log.isDebugEnabled()) {
                log.debug("DRAW");
            }
            double scoreDiff = gameState.getScore().getScoreDifference();
            // stronger bias than ±0.01 to steer decisively
            final double DRAW_BIAS = 0.20;
            if ((isWhitesTurn && scoreDiff > 0) || (!isWhitesTurn && scoreDiff < 0)) {
                return DRAW - DRAW_BIAS; // avoid draws when ahead
            } else if ((isWhitesTurn && scoreDiff < 0) || (!isWhitesTurn && scoreDiff > 0)) {
                return DRAW + DRAW_BIAS; // accept draws when behind
            }
            return DRAW;
        }
        double scoreDifference = gameState.getScore().getScoreDifference();

        if (log.isDebugEnabled()) {
            log.debug("Evaluate static position score {}, {} ",
                    isWhitesTurn ? scoreDifference : -scoreDifference,
                    isWhitesTurn ? "WHITE" : "BLACK");
        }
        return isWhitesTurn ? scoreDifference : -scoreDifference;
    }

    private IntArrayList getPossibleCapturesOrPromotions(Engine simulatorEngine) {
        IntArrayList allLegalMoves = simulatorEngine.getAllLegalMoves();
        return MoveContainerUtils.filterCapturesAndPromotions(allLegalMoves);
    }

    /**
     * Checks whether the board hash has changed since the current search task started.
     *
     * <p>The hashes live in {@code volatile} fields, so this is a lock-free visibility check that
     * avoids serialising worker threads on a monitor. The volatile semantics already provide the
     * required cross-thread visibility.</p>
     */
    private boolean positionChanged() {
        return currentBoardState != beforeCalculationBoardState;
    }

    /**
     * Checks if the current score is better than the best score based on the player's color.
     */
    private boolean isBetterScore(boolean isWhite, double score, double bestScore) {
        return isWhite ? score > bestScore : score < bestScore;
    }

    public void updateBoardStateHash() {
        enqueueCalculationRequest();
    }

    private void updateKillerMoves(int depth, int move) {
        threadHeuristics.get().recordKiller(depth, move);
    }

    private void incrementHistory(int move, int depth) {
        threadHeuristics.get().addHistory(move, depth);
    }

    private void decayHistoryTable() {
        long stamp = acquireWriteLock();
        try {
            globalHeuristics.decayHistory();
        } finally {
            releaseWriteLock(stamp);
        }
    }

    private void clearHistoryTable() {
        long stamp = acquireWriteLock();
        try {
            globalHeuristics.clearHistory();
            globalHeuristics.clearCounter();
        } finally {
            releaseWriteLock(stamp);
        }
    }

    int[][] snapshotKillerMoves() {
        long stamp = acquireReadLock();
        try {
            return globalHeuristics.snapshotKillers();
        } finally {
            releaseReadLock(stamp);
        }
    }

    private int calculateMvvLvaScore(int move) {
        if (!MoveHelper.isCapture(move)) {
            return 0; // Not a capture move
        }
        int victimValue = Score.getPieceValue(MoveHelper.deriveCapturedPieceTypeBits(move));
        int attackerValue = Score.getPieceValue(MoveHelper.derivePieceTypeBits(move));
        return victimValue - attackerValue;
    }

    static final class LockMetricsSnapshot {
        private final long maxReadWaitNanos;
        private final long maxWriteWaitNanos;
        private final long readAcquisitions;
        private final long writeAcquisitions;
        private final long optimisticSnapshots;
        private final long optimisticFallbacks;

        LockMetricsSnapshot(long maxReadWaitNanos,
                            long maxWriteWaitNanos,
                            long readAcquisitions,
                            long writeAcquisitions,
                            long optimisticSnapshots,
                            long optimisticFallbacks) {
            this.maxReadWaitNanos = maxReadWaitNanos;
            this.maxWriteWaitNanos = maxWriteWaitNanos;
            this.readAcquisitions = readAcquisitions;
            this.writeAcquisitions = writeAcquisitions;
            this.optimisticSnapshots = optimisticSnapshots;
            this.optimisticFallbacks = optimisticFallbacks;
        }

        long getMaxReadWaitNanos() {
            return maxReadWaitNanos;
        }

        long getMaxWriteWaitNanos() {
            return maxWriteWaitNanos;
        }

        long getReadAcquisitions() {
            return readAcquisitions;
        }

        long getWriteAcquisitions() {
            return writeAcquisitions;
        }

        long getOptimisticSnapshots() {
            return optimisticSnapshots;
        }

        long getOptimisticFallbacks() {
            return optimisticFallbacks;
        }
    }

    private static final class LockMetrics {
        private final AtomicLong maxReadWait = new AtomicLong();
        private final AtomicLong maxWriteWait = new AtomicLong();
        private final LongAdder readAcquisitions = new LongAdder();
        private final LongAdder writeAcquisitions = new LongAdder();
        private final LongAdder optimisticSnapshots = new LongAdder();
        private final LongAdder optimisticFallbacks = new LongAdder();

        void recordReadAcquisition(long waitNanos) {
            readAcquisitions.increment();
            updateMax(maxReadWait, waitNanos);
        }

        void recordWriteAcquisition(long waitNanos) {
            writeAcquisitions.increment();
            updateMax(maxWriteWait, waitNanos);
        }

        void recordOptimisticSnapshot() {
            optimisticSnapshots.increment();
        }

        void recordOptimisticFallback() {
            optimisticFallbacks.increment();
        }

        LockMetricsSnapshot snapshot() {
            return new LockMetricsSnapshot(
                    maxReadWait.get(),
                    maxWriteWait.get(),
                    readAcquisitions.sum(),
                    writeAcquisitions.sum(),
                    optimisticSnapshots.sum(),
                    optimisticFallbacks.sum()
            );
        }

        private static void updateMax(AtomicLong target, long value) {
            target.accumulateAndGet(value, (cur, v) -> Math.max(cur, v));
        }
    }

    private static final class Heuristics {
        private static final int BOARD_SQUARES = 64;
        private static final int HISTORY_SIZE = BOARD_SQUARES * BOARD_SQUARES;

        private int[][] killers;
        private final int[][] history;
        private final int[][] counter;

        private boolean[] killerDirty;
        private int[] killerDirtyList;
        private int killerDirtyCount;

        private final int[] historyDelta;
        private final boolean[] historyDirty;
        private final int[] historyDirtyList;
        private int historyDirtyCount;

        private final int[] counterUpdates;
        private final boolean[] counterDirty;
        private final int[] counterDirtyList;
        private int counterDirtyCount;

        private long preparedTaskId = Long.MIN_VALUE;
        private int preparedDepth = -1;

        Heuristics(int depth) {
            this.killers = allocateKillers(Math.max(1, depth));
            this.history = new int[BOARD_SQUARES][BOARD_SQUARES];
            this.counter = new int[BOARD_SQUARES][BOARD_SQUARES];
            for (int f = 0; f < BOARD_SQUARES; f++) {
                Arrays.fill(counter[f], -1);
            }
            this.killerDirty = new boolean[Math.max(1, depth)];
            this.killerDirtyList = new int[Math.max(1, depth)];
            this.historyDelta = new int[HISTORY_SIZE];
            this.historyDirty = new boolean[HISTORY_SIZE];
            this.historyDirtyList = new int[HISTORY_SIZE];
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

        void ensureCapacity(int depth) {
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

        void beginIteration(Snapshot snapshot, int requiredDepth) {
            resetUpdates();
            ensureCapacity(requiredDepth);
            int limit = Math.min(requiredDepth, snapshot.killers.length);
            for (int d = 0; d < limit; d++) {
                System.arraycopy(snapshot.killers[d], 0, killers[d], 0, NUM_KILLER_MOVES);
            }
            for (int d = limit; d < requiredDepth; d++) {
                Arrays.fill(killers[d], -1);
            }
            for (int f = 0; f < BOARD_SQUARES; f++) {
                System.arraycopy(snapshot.history[f], 0, history[f], 0, BOARD_SQUARES);
                System.arraycopy(snapshot.counter[f], 0, counter[f], 0, BOARD_SQUARES);
            }
        }

        Snapshot snapshot(int requiredDepth) {
            int killerDepth = Math.min(requiredDepth, killers.length);
            int[][] killerCopy = new int[killerDepth][];
            for (int d = 0; d < killerDepth; d++) {
                killerCopy[d] = Arrays.copyOf(killers[d], NUM_KILLER_MOVES);
            }
            int[][] historyCopy = new int[BOARD_SQUARES][];
            int[][] counterCopy = new int[BOARD_SQUARES][];
            for (int f = 0; f < BOARD_SQUARES; f++) {
                historyCopy[f] = Arrays.copyOf(history[f], BOARD_SQUARES);
                counterCopy[f] = Arrays.copyOf(counter[f], BOARD_SQUARES);
            }
            return new Snapshot(killerCopy, historyCopy, counterCopy);
        }

        static final class Snapshot {
            final int[][] killers;
            final int[][] history;
            final int[][] counter;

            Snapshot(int[][] killers, int[][] history, int[][] counter) {
                this.killers = killers;
                this.history = history;
                this.counter = counter;
            }
        }

        boolean isPreparedFor(long taskId, int depth) {
            return preparedTaskId == taskId && preparedDepth == depth;
        }

        void markPrepared(long taskId, int depth) {
            this.preparedTaskId = taskId;
            this.preparedDepth = depth;
        }

        void resetUpdates() {
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

            for (int i = 0; i < counterDirtyCount; i++) {
                int idx = counterDirtyList[i];
                counterDirty[idx] = false;
                counterUpdates[idx] = -1;
            }
            counterDirtyCount = 0;
            preparedTaskId = Long.MIN_VALUE;
            preparedDepth = -1;
        }

        boolean hasUpdates() {
            return killerDirtyCount > 0 || historyDirtyCount > 0 || counterDirtyCount > 0;
        }

        void recordKiller(int depth, int move) {
            if (move == -1) {
                return;
            }
            int depthIndex = Math.max(0, Math.min(depth, killers.length - 1));
            int[] row = killers[depthIndex];
            for (int i = 0; i < row.length; i++) {
                if (row[i] == move) {
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

        void addHistory(int move, int depth) {
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

        void recordCounterMove(int prevMove, int move) {
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

        void mergeInto(Heuristics target) {
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
            for (int i = 0; i < counterDirtyCount; i++) {
                int idx = counterDirtyList[i];
                int from = idx >>> 6;
                int to = idx & 0x3F;
                target.counter[from][to] = counterUpdates[idx];
            }
        }

        void insertKiller(int depth, int move) {
            if (move == -1) {
                return;
            }
            int depthIndex = Math.max(0, Math.min(depth, killers.length - 1));
            int[] row = killers[depthIndex];
            for (int i = 0; i < row.length; i++) {
                if (row[i] == move) {
                    return;
                }
            }
            for (int i = row.length - 1; i > 0; i--) {
                row[i] = row[i - 1];
            }
            row[0] = move;
        }

        void decayHistory() {
            for (int f = 0; f < BOARD_SQUARES; f++) {
                for (int t = 0; t < BOARD_SQUARES; t++) {
                    history[f][t] >>= 1;
                }
            }
        }

        void clearHistory() {
            for (int f = 0; f < BOARD_SQUARES; f++) {
                Arrays.fill(history[f], 0);
            }
            resetUpdates();
        }

        void clearCounter() {
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

        int[][] snapshotKillers() {
            int[][] snapshot = new int[killers.length][];
            for (int i = 0; i < killers.length; i++) {
                snapshot[i] = Arrays.copyOf(killers[i], killers[i].length);
            }
            return snapshot;
        }
    }
}
