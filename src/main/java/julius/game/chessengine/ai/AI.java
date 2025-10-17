package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import julius.game.chessengine.ai.time.TimeManager;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.Move;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.evaluation.EvaluationContext;
import julius.game.chessengine.syzygy.SyzygyMove;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.tuning.AiTuning;
import julius.game.chessengine.tuning.AspirationParameters;
import julius.game.chessengine.tuning.LmrParameters;
import julius.game.chessengine.tuning.MoveOrderingParameters;
import julius.game.chessengine.tuning.NullMoveParameters;
import julius.game.chessengine.tuning.SearchPruningParameters;
import julius.game.chessengine.tuning.Tuning;
import julius.game.chessengine.utils.Score;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntFunction;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.StampedLock;
import java.util.stream.Collectors;

import static julius.game.chessengine.helper.BitHelper.FileMasks;
import static julius.game.chessengine.helper.KingHelper.KING_ATTACKS;
import static julius.game.chessengine.utils.Score.*;

@Log4j2
public class AI {

    @Getter
    private final Engine mainEngine;

    @Getter
    private final AiTuning tuning;

    private final SyzygyTablebaseService tablebaseService;

    /**
     * Number of threads used for searching. Defaults to single-threaded search but
     * can be adjusted at runtime via the UCI "Threads" option.
     */
    @Getter
    private volatile int searchThreads = Integer.getInteger("chessengine.searchThreads", 1);

    // number of Lazy SMP workers (≥1)
    @Getter
    private int lazySmpThreads = Math.max(1, Integer.getInteger("chessengine.lazySmpThreads", 1));

    private Thread calculationCoordinator;
    private Thread[] calculationThreads;
    private final Object workerLifecycleLock = new Object();
    private volatile boolean workerShutdown;
    private static final String WORKER_STATS_PROPERTY = "chessengine.diagnostics.workerStats";
    private static final String ROOT_FANOUT_PROPERTY = "chessengine.diagnostics.rootFanout";
    private static final String ROOT_FANOUT_RATIO_PROPERTY = "chessengine.rootFanoutRatio";
    private final boolean workerStatsEnabled;
    private final boolean rootFanoutStatsEnabled;
    private final double rootFanoutRatio;
    private WorkerInstrumentation workerInstrumentation;

    private static final int ABS_PLY_LIMIT_MARGIN = 32;

    private final AtomicReference<SearchTask> activeSearch = new AtomicReference<>();
    private final ThreadLocal<SearchTask> threadSearchTask = new ThreadLocal<>();
    private final AtomicLong searchIdGenerator = new AtomicLong();
    private final Object searchConfigLock = new Object();
    private boolean reconfiguringSearchThreads = false;
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

    public static final int MIN_HASH_SIZE_MB = 1;
    public static final int MAX_HASH_SIZE_MB = 4096;

    /**
     * Thread pool for root-split parallel search (created only if searchThreads > 1).
     */
    private volatile ExecutorService searchPool;

    /**
     * Limit how many root moves we fan out in parallel to avoid oversubscription.
     */
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

    @Getter
    private int rootParallelLimit;

    /**
     * Global heuristic tables shared across searches. Search threads work with
     * thread-local copies that are periodically merged back into this
     * structure between iterative-deepening iterations.
     */
    private final Heuristics globalHeuristics;

    private final MoveOrderingParameters.Snapshot moveOrderingParameters;
    private final MoveBucket[] moveBucketOrder;
    private final double moveOrderingHistoryScale;
    private final int moveOrderingHistoryDecayDivisor;
    private final SearchPruningParameters.Snapshot searchPruningParameters;
    private final AspirationParameters.Snapshot aspirationParameters;
    private final NullMoveParameters.Snapshot nullMoveParameters;
    private final int[][][] lmrReductionTable;
    private final int lmrBucketCount;
    private final int futilityMaxDepth;
    private final int lmpMaxDepth;
    private final double nullSwingGuardMinCp;
    private final double nullSwingGuardDivisor;
    private final double quiescenceMaxDeltaPawn;
    private final double drawBias;
    private final double ttMainWeight;
    private final double ttCaptureWeight;
    private final boolean preferFastMate;
    private final boolean tbTieBreak;

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

    private enum MoveBucket {
        TT,
        PROMOTION,
        CAPTURE_GOOD,
        CAPTURE_EQUAL,
        KILLER0,
        KILLER1,
        QUIET,
        CAPTURE_BAD
    }

    private final ThreadLocal<SortBuffers> sortBuffers =
            ThreadLocal.withInitial(() -> new SortBuffers(MAX_MOVE_LIST_SIZE, MoveBucket.values().length));
    private static final int SEE_CACHE_MISS = Integer.MIN_VALUE;
    private final ThreadLocal<Int2IntOpenHashMap> seeCacheThreadLocal =
            ThreadLocal.withInitial(() -> {
                Int2IntOpenHashMap map = new Int2IntOpenHashMap(64);
                map.defaultReturnValue(SEE_CACHE_MISS);
                return map;
            });

    private final ThreadLocal<Long2DoubleOpenHashMap> staticEvalCache =
            ThreadLocal.withInitial(() -> {
                Long2DoubleOpenHashMap map = new Long2DoubleOpenHashMap(512);
                map.defaultReturnValue(Double.NaN);
                return map;
            });

    /**
     * Root-split worker simulations. Each search thread reuses its own clone to avoid
     * repeated allocations during parallel search fan-out.
     */
    private final ThreadLocal<Deque<Engine>> workerSimulationPool =
            ThreadLocal.withInitial(ArrayDeque::new);

    private record TablebaseHit(double score, int bestMove, TablebaseResult result) {
    }

    private record TablebaseContinuation(int move, double evaluation, TablebaseResult result,
                                         boolean zeroingMove) {
    }

    private record TablebaseInfo(int dtz, int dtm, int whiteWdlSign) {
        boolean hasDtz() { return dtz >= 0; }
    }

    private static final int LMR_MAX_DEPTH = 64;
    private static final int LMR_MAX_MOVES = MAX_MOVE_LIST_SIZE;
    private static final double MATE_SCORE_MARGIN = 2048.0;
    private static final double TB_TIE_EPSILON = 0.01;

    private ScheduledExecutorService scheduler;

    private final CalculationQueue calculationRequests = new CalculationQueue();
    private final BlockingQueue<SearchJob> searchJobs = new LinkedBlockingQueue<>();

    private record CalculationRequest(long boardHash, boolean stop) {
        static CalculationRequest work(long boardHash) {
            return new CalculationRequest(boardHash, false);
        }

        static CalculationRequest stopSignal() {
            return new CalculationRequest(0L, true);
        }
    }

    private static final class CalculationQueue extends LinkedBlockingQueue<CalculationRequest> {
        private final AtomicBoolean inFlight = new AtomicBoolean(false);

        @Override
        public boolean isEmpty() {
            return super.isEmpty() && !inFlight.get();
        }

        void markProcessingStart() {
            inFlight.set(true);
        }

        void markProcessingEnd() {
            inFlight.set(false);
        }
    }

    private record SearchJob(SearchTask task, boolean stop) {

        static SearchJob work(SearchTask task) {
            return new SearchJob(task, false);
        }

        static SearchJob stopSignal() {
            return new SearchJob(null, true);
        }
    }

    private static final long UNINITIALIZED_BOARD_STATE = Long.MIN_VALUE;

    private volatile boolean keepCalculating = true;

    private volatile long currentBoardState = UNINITIALIZED_BOARD_STATE;
    private volatile long beforeCalculationBoardState = UNINITIALIZED_BOARD_STATE;

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

    private final TimeManager timeManager;

    private boolean useNullMovePruning = Boolean.parseBoolean(
            System.getProperty("chessengine.nullMove", "true")
    );

    @Getter
    private long nodesVisited = 0;
    @Getter
    private long nullMoveCount = 0;

    public AI(Engine mainEngine) {
        this(mainEngine, AiTuning.defaults(), null);
    }

    public AI(Engine mainEngine, SyzygyTablebaseService tablebaseService) {
        this(mainEngine, AiTuning.defaults(), tablebaseService);
    }

    public AI(Engine mainEngine, AiTuning tuning) {
        this(mainEngine, tuning, null);
    }

    public AI(Engine mainEngine, AiTuning tuning, SyzygyTablebaseService tablebaseService) {
        this.mainEngine = Objects.requireNonNull(mainEngine, "mainEngine");
        this.tuning = tuning != null ? tuning : AiTuning.defaults();
        this.tablebaseService = tablebaseService;
        if (tablebaseService != null) {
            Score.setTablebaseService(tablebaseService);
        }
        this.searchThreads = this.tuning.searchThreads();
        this.lazySmpThreads = Math.max(1, this.tuning.lazySmpThreads());
        this.hashSizeMb = this.tuning.hashSizeMb();
        this.maxDepth = this.tuning.maxDepth();
        this.timeManager = new TimeManager(this.tuning.timeLimitMillis());
        this.useNullMovePruning = this.tuning.nullMovePruning();
        this.rootParallelLimit = Math.max(1, this.tuning.rootParallelLimit());
        this.workerStatsEnabled = Boolean.parseBoolean(System.getProperty(WORKER_STATS_PROPERTY, "false"));
        this.rootFanoutStatsEnabled = Boolean.parseBoolean(System.getProperty(ROOT_FANOUT_PROPERTY, "false"));
        double ratioCandidate;
        try {
            ratioCandidate = Double.parseDouble(System.getProperty(ROOT_FANOUT_RATIO_PROPERTY, "1.0"));
        } catch (NumberFormatException ex) {
            ratioCandidate = 1.0d;
        }
        if (Double.isNaN(ratioCandidate) || ratioCandidate <= 0d) {
            ratioCandidate = 1.0d;
        } else if (ratioCandidate > 1.0d) {
            ratioCandidate = 1.0d;
        }
        this.rootFanoutRatio = ratioCandidate;
        this.workerInstrumentation = workerStatsEnabled && this.lazySmpThreads > 0
                ? new WorkerInstrumentation(this.lazySmpThreads)
                : null;

        log.info("### SearchThreads = {}, LazySmpThreads = {}", searchThreads, lazySmpThreads);

        this.moveOrderingParameters = MoveOrderingParameters.snapshot();
        this.moveBucketOrder = buildMoveBucketOrder(this.moveOrderingParameters);
        this.moveOrderingHistoryScale = Math.max(0.0, moveOrderingParameters.historyScale());
        this.moveOrderingHistoryDecayDivisor = Math.max(1, moveOrderingParameters.historyDecayDivisor());
        this.searchPruningParameters = SearchPruningParameters.snapshot();
        this.aspirationParameters = AspirationParameters.snapshot();
        this.nullMoveParameters = NullMoveParameters.snapshot();
        LmrParameters.Snapshot lmrSnapshot = LmrParameters.snapshot();
        this.lmrReductionTable = lmrSnapshot.buildReductionTable(LMR_MAX_DEPTH, LMR_MAX_MOVES);
        this.lmrBucketCount = lmrReductionTable.length > 0 && lmrReductionTable[0].length > 0
                ? Math.max(1, lmrReductionTable[0][0].length)
                : 1;
        this.futilityMaxDepth = Math.max(0, Tuning.searchFpMaxDepth());
        this.lmpMaxDepth = Math.max(0, Tuning.searchLmpMaxDepth());
        this.nullSwingGuardMinCp = Tuning.searchNullSwingGuardMinCp();
        this.nullSwingGuardDivisor = Math.max(1e-9, Tuning.searchNullSwingGuardDivisor());
        this.quiescenceMaxDeltaPawn = Tuning.searchQsMaxDeltaPawn();
        this.drawBias = Tuning.searchDrawBias();
        this.ttMainWeight = Math.max(1e-9, Tuning.searchTtMainWeight());
        this.ttCaptureWeight = Math.max(1e-9, Tuning.searchTtCaptureWeight());
        this.preferFastMate = Tuning.searchPreferFastMate();
        this.tbTieBreak = Tuning.searchTbTieBreak() && this.tablebaseService != null;
        this.globalHeuristics = new Heuristics(maxDepth);
        this.threadHeuristics = ThreadLocal.withInitial(() -> new Heuristics(maxDepth));

        rebuildTranspositionTables();

        this.searchPool = createSearchPool();

        this.mainEngine.setOnPositionChanged(_ -> updateBoardStateHash());
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

    private void rebuildTranspositionTables() {
        boolean concurrent = Math.max(searchThreads, lazySmpThreads) > 1;
        long totalBytes = Math.max(1L, (long) hashSizeMb * 1024L * 1024L);

        double totalWeight = ttMainWeight + ttCaptureWeight;
        if (totalWeight <= 0.0) {
            totalWeight = 1.0;
        }
        long mainBudget = Math.max(1L, (long) (totalBytes * (ttMainWeight / totalWeight)));
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
        // define behavior for non-positive
        if (value <= 1) return 1;

        // anything above 2^30 must saturate (since 2^31 doesn't fit in signed int)
        if (value > (1 << 30)) return 1 << 30;

        // exact power-of-two? return unchanged
        int hib = Integer.highestOneBit(value);
        if (hib == value) return value;

        // next power-of-two (safe because value <= 2^30)
        return hib << 1;
    }

    /**
     * Update the number of threads used for root-split search. The method stops any
     * running search before replacing the executor service and rebuilding the
     * transposition tables so workers never observe disposed infrastructure.
     *
     * @param threads requested number of search threads (values &lt;= 0 are treated as 1)
     */
    public void setSearchThreads(int threads) {
        int requested = Math.max(1, threads);

        SearchTask running;
        ExecutorService oldPool;
        int previous;

        synchronized (searchConfigLock) {
            previous = this.searchThreads;
            if (requested == previous) {
                return;
            }

            reconfiguringSearchThreads = true;

            running = activeSearch.get();
            if (running != null) {
                running.requestStop();
            }

            oldPool = this.searchPool;
        }

        if (running != null) {
            running.awaitCompletion();

            while (activeSearch.get() == running) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (oldPool != null) {
            oldPool.shutdown();
            try {
                if (!oldPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    oldPool.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                oldPool.shutdownNow();
            }
        }

        synchronized (searchConfigLock) {
            this.searchThreads = requested;
            try {
                rebuildTranspositionTables();
                this.searchPool = createSearchPool();

                if (searchPool != null) {
                    log.info("Search threads updated from {} to {} (parallel pool recreated)", previous, requested);
                } else {
                    log.info("Search threads updated from {} to {} (parallel pool disabled)", previous, requested);
                }
            } finally {
                reconfiguringSearchThreads = false;
                searchConfigLock.notifyAll();
            }
        }
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

    public void setTimeLimit(long timeLimitMillis) {
        timeManager.setDefaultPerMoveMillis(Math.max(1L, timeLimitMillis));
    }

    public long getTimeLimit() {
        return timeManager.getDefaultPerMoveMillis();
    }

    public void submitTimeRequest(TimeManager.Request request) {
        timeManager.submit(request);
    }

    public void promotePonderHit() {
        TimeManager.TimeBudget budget = timeManager.promotePonderHit();
        SearchTask task = activeSearch.get();
        if (task != null && budget != null) {
            task.updateBudget(budget);
        }
    }

    public TimeManager.TimeBudget getActiveTimeBudget() {
        return timeManager.activeBudget();
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
        ensureCalculationThreadsRunning();
        enqueueCalculationRequestIfNeeded();
    }

    private void ensureCalculationThreadsRunning() {
        synchronized (workerLifecycleLock) {
            if (workerShutdown) {
                workerShutdown = false;
            }

            if (calculationCoordinator == null || !calculationCoordinator.isAlive()) {
                calculationRequests.clear();
                searchJobs.clear();
                calculationCoordinator = createCoordinatorThread();
                calculationCoordinator.start();
            }

            if (calculationThreads == null) {
                calculationThreads = new Thread[lazySmpThreads];
            } else if (calculationThreads.length < lazySmpThreads) {
                calculationThreads = Arrays.copyOf(calculationThreads, lazySmpThreads);
            }
            if (workerStatsEnabled) {
                if (workerInstrumentation == null) {
                    workerInstrumentation = new WorkerInstrumentation(lazySmpThreads);
                } else {
                    workerInstrumentation.ensureCapacity(lazySmpThreads);
                }
            }

            for (int i = 0; i < lazySmpThreads; i++) {
                Thread worker = calculationThreads[i];
                if (worker == null || !worker.isAlive()) {
                    Thread newWorker = createWorkerThread(i);
                    newWorker.start();
                    calculationThreads[i] = newWorker;
                }
            }
        }
    }

    private Thread createCoordinatorThread() {
        Thread dispatcher = new Thread(this::calculateLine, "Simulator-Dispatcher");
        dispatcher.setDaemon(true);
        return dispatcher;
    }

    private Thread createWorkerThread(int workerIndex) {
        Thread worker = new Thread(() -> searchWorkerLoop(workerIndex), "Simulator-" + workerIndex);
        worker.setDaemon(true);
        return worker;
    }

    private void enqueueCalculationRequestIfNeeded() {
        if (searchResultReady && currentBestMove != -1) {
            long currentHash = mainEngine.getBoardStateHash();
            if (bestMoveForHash == currentHash) {
                return;
            }
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
            if (workerShutdown) {
                break;
            }
            WorkerInstrumentation instrumentation = workerStatsEnabled ? workerInstrumentation : null;
            long idleStart = instrumentation != null ? System.nanoTime() : 0L;
            SearchJob job;
            try {
                job = searchJobs.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (instrumentation != null) {
                instrumentation.recordIdle(workerIndex, System.nanoTime() - idleStart);
            }

            if (job.stop || workerShutdown) {
                break;
            }

            SearchTask task = job.task;
            if (task == null) {
                continue;
            }

            if (!keepCalculating) {
                task.requestStop();
                task.workerDone();
                if (instrumentation != null) {
                    instrumentation.recordActive(workerIndex, 0L);
                    instrumentation.incrementJobs(workerIndex);
                }
                continue;
            }

            long activeStart = instrumentation != null ? System.nanoTime() : 0L;

            try {
                simulator.copyFrom(task.getRootSnapshot());
            } catch (RuntimeException e) {
                log.error("Failed to sync simulation for worker {}", workerIndex, e);
                task.workerDone();
                if (instrumentation != null) {
                    instrumentation.recordActive(workerIndex, System.nanoTime() - activeStart);
                    instrumentation.incrementJobs(workerIndex);
                }
                continue;
            }

            SplittableRandom rng = createLazyWorkerRng(task, workerIndex);

            threadSearchTask.set(task);
            try {
                iterativeDeepening(task, simulator, rng);
            } catch (Exception e) {
                log.error("Search worker {} encountered an error", workerIndex, e);
            } finally {
                threadSearchTask.remove();
                task.workerDone();
                if (instrumentation != null) {
                    instrumentation.recordActive(workerIndex, System.nanoTime() - activeStart);
                    instrumentation.incrementJobs(workerIndex);
                }
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
        calculationRequests.offer(CalculationRequest.work(hash));
    }

    private void iterativeDeepening(SearchTask task, Engine simulatorEngine, SplittableRandom rng) {
        clearStaticEvalCache();
        Double lastIterScore = null;
        Heuristics heuristics = threadHeuristics.get();
        AspirationController aspirationController = new AspirationController(aspirationParameters);

        for (int currentDepth = 1; currentDepth <= maxDepth; currentDepth++) {
            if (shouldStopCalculating(task)) break;

            boolean firstAtDepth = task.beginIteration(currentDepth);
            prepareIterationState(task, heuristics, currentDepth, firstAtDepth);

            RootSearchResult result = null;
            boolean usedFullWindow = false;
            boolean attemptedAspiration = false;

            if (lastIterScore != null && currentDepth >= 3) {
                attemptedAspiration = true;
                AspirationController.State aspirationState =
                        aspirationController.beginDepth(currentDepth, lastIterScore, rng);
                double alpha = aspirationState.alpha();
                double beta = aspirationState.beta();

                while (!shouldStopCalculating(task)) {
                    result = searchRootMoves(simulatorEngine, task, currentDepth, alpha, beta, rng);
                    if (!result.hasCandidate()) break;

                    MoveAndScore candidate = result.bestMove();
                    if (!result.isCompleted()) break;

                    if (candidate.score <= alpha) {
                        AspirationController.Adjustment adjustment =
                                aspirationController.onFailLow(candidate.score);
                        if (adjustment.isFullWindow()) {
                            alpha = Double.NEGATIVE_INFINITY;
                            beta = Double.POSITIVE_INFINITY;
                            usedFullWindow = true;
                            continue;
                        }
                        alpha = adjustment.alpha();
                        beta = adjustment.beta();
                        continue;
                    }

                    if (candidate.score >= beta) {
                        AspirationController.Adjustment adjustment =
                                aspirationController.onFailHigh(candidate.score);
                        if (adjustment.isFullWindow()) {
                            alpha = Double.NEGATIVE_INFINITY;
                            beta = Double.POSITIVE_INFINITY;
                            usedFullWindow = true;
                            continue;
                        }
                        alpha = adjustment.alpha();
                        beta = adjustment.beta();
                        continue;
                    }

                    aspirationController.onSuccess(candidate.score);
                    break;
                }
            }

            if (result == null || !result.hasCandidate()) {
                result = searchRootMoves(simulatorEngine, task, currentDepth,
                        Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, rng);
                if (attemptedAspiration) {
                    usedFullWindow = true;
                }
            }

            if (!result.hasCandidate()) {
                if (heuristics.hasUpdates()) mergeThreadHeuristics(heuristics);
                if (!result.isCompleted() && currentDepth == 1) {
                    // Depth-1 special-case still requires a candidate; none here.
                }
                break;
            }

            MoveAndScore sealed = result.bestMove();

            if (!result.isCompleted()) {
                if (currentDepth == 1) {
                    task.publishBest(sealed, currentDepth, simulatorEngine);
                }
                if (heuristics.hasUpdates()) mergeThreadHeuristics(heuristics);
                break;
            }

            lastIterScore = sealed.score;
            aspirationController.finishIteration(sealed.score, attemptedAspiration, usedFullWindow);
            task.publishBest(sealed, currentDepth, simulatorEngine);

            if (heuristics.hasUpdates()) mergeThreadHeuristics(heuristics);
            if (task.isStopRequested()) break;
        }
    }

    private void prepareIterationState(SearchTask task, Heuristics heuristics, int currentDepth, boolean firstAtDepth) {
        if (firstAtDepth) {
            long stamp = acquireWriteLock();
            try {
                globalHeuristics.ensureCapacity(maxDepth);
                if (transpositionTable != null) {
                    transpositionTable.advanceAge();
                }
                if (captureTranspositionTable != null) {
                    captureTranspositionTable.advanceAge();
                }
                globalHeuristics.decayHistory(moveOrderingHistoryDecayDivisor);
            } finally {
                releaseWriteLock(stamp);
            }
        }
        Heuristics.Snapshot snapshot = captureHeuristicsSnapshot(maxDepth);
        heuristics.beginIteration(snapshot, maxDepth);
        heuristics.markPrepared(task.getId(), currentDepth);
    }

    private void mergeThreadHeuristics(Heuristics heuristics) {
        if (!heuristics.hasPendingUpdates()) {
            return;
        }
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

    private Engine borrowWorkerSimulation(Engine template) {
        Deque<Engine> pool = workerSimulationPool.get();
        Engine borrowed = pool.pollFirst();
        if (borrowed == null) {
            return template.createSimulation();
        }
        borrowed.copyFrom(template);
        return borrowed;
    }

    private void releaseWorkerSimulation(Engine borrowed, Engine baseline) {
        if (borrowed == null) {
            return;
        }
        try {
            while (borrowed.getLastMove() != -1) {
                borrowed.undoLastMove();
            }
        } catch (Exception ex) {
            log.warn("Failed to rewind pooled simulation engine", ex);
            try {
                if (baseline != null) {
                    borrowed.copyFrom(baseline);
                } else {
                    borrowed.startNewGame();
                }
            } catch (Exception suppressed) {
                log.debug("Unable to reset simulation engine via copy", suppressed);
                try {
                    borrowed.startNewGame();
                } catch (Exception ignored) {
                    log.debug("Unable to reset simulation engine via startNewGame", ignored);
                }
            }
        }
        workerSimulationPool.get().offerFirst(borrowed);
        if (Thread.currentThread().isInterrupted()) {
            workerSimulationPool.remove();
        }
    }

    private void clearStaticEvalCache() {
        staticEvalCache.get().clear();
    }

    private double resolveScoreDifference(GameState gameState, long boardHash, boolean whiteToMove) {
        Long2DoubleOpenHashMap cache = staticEvalCache.get();
        Optional<TablebaseResult> tablebase = gameState.getLastTablebaseResult();
        if (tablebase.isPresent() && isExactWdl(tablebase.get())) {
            double exact = Score.tablebaseToEvaluation(tablebase.get(), whiteToMove,
                    gameState.getHalfmoveClock());
            cache.put(boardHash, exact);
            return exact;
        }
        double cached = cache.get(boardHash);
        if (!Double.isNaN(cached)) {
            return cached;
        }
        double computed = gameState.getScore().getScoreDifference();
        cache.put(boardHash, computed);
        return computed;
    }

    protected RootSearchResult searchRootMoves(Engine sim, SearchTask task, int depth, double alpha, double beta, SplittableRandom rng) {
        if (searchThreads > 1) {
            return getBestMoveParallel(sim, task, depth, task.getHardDeadline(), alpha, beta, rng);
        }
        return getBestMove(sim, task.isWhiteToMove(), depth, task.getHardDeadline(), alpha, beta, rng);
    }

    public synchronized MoveAndScore searchBestMoveBlocking(long timeLimitMillis) {
        try {
            Engine simulatorEngine = mainEngine.createSimulation();
            long boardStateHash = simulatorEngine.getBoardStateHash();
            TimeManager.TimeBudget budget = (timeLimitMillis > 0)
                    ? timeManager.beginSearchWithOverride(timeLimitMillis)
                    : timeManager.beginSearch();

            SearchTask task;
            synchronized (searchConfigLock) {
                while (reconfiguringSearchThreads) {
                    try {
                        searchConfigLock.wait();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }

                task = new SearchTask(
                        searchIdGenerator.incrementAndGet(),
                        boardStateHash,
                        simulatorEngine.whitesTurn(),
                        budget,
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
            }

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
        }
    }

    private SplittableRandom createLazyWorkerRng(SearchTask task, int workerIndex) {
        if (lazySmpThreads <= 1 || workerIndex <= 0 || task == null) {
            return null;
        }
        long seed = task.getBoardHash() ^ (0x9E3779B97F4A7C15L * (workerIndex + 1L));
        return new SplittableRandom(seed);
    }


    public void reset() {
        stopCalculation();
        currentBestMove = -1;
        bestMoveForHash = -1;
        previousBestMove = -1;
        previousBestMoveHash = -1;
        searchResultReady = false;
        currentBoardState = UNINITIALIZED_BOARD_STATE;
        beforeCalculationBoardState = UNINITIALIZED_BOARD_STATE;
        calculatedLine = Collections.synchronizedList(new ArrayList<>());
        mainEngine.startNewGame();
        clearHistoryTable();
    }

    public void stopCalculation() {
        SearchTask task = activeSearch.get();
        if (task != null) {
            task.requestStop();
        }

        keepCalculating = false;
        calculationRequests.clear();

        if (task != null) {
            task.awaitCompletion();
            while (activeSearch.get() == task && !activeSearch.compareAndSet(task, null)) {
                Thread.yield();
            }
        } else {
            activeSearch.set(null);
        }

        calculatedLine = Collections.synchronizedList(new ArrayList<>());
        currentBestMove = -1;
        bestMoveForHash = -1;
        previousBestMove = -1;
        previousBestMoveHash = -1;
        searchResultReady = false;
    }

    private void shutdownWorkerThreads() {
        Thread coordinatorSnapshot;
        Thread[] workersSnapshot;

        synchronized (workerLifecycleLock) {
            workerShutdown = true;
            calculationRequests.clear();
            calculationRequests.offer(CalculationRequest.stopSignal());
            searchJobs.clear();

            coordinatorSnapshot = calculationCoordinator;
            workersSnapshot = calculationThreads != null ? calculationThreads.clone() : null;

            if (workersSnapshot != null) {
                for (Thread worker : workersSnapshot) {
                    if (worker != null && worker.isAlive()) {
                        searchJobs.offer(SearchJob.stopSignal());
                    }
                }
            }
        }

        if (workersSnapshot != null) {
            for (Thread worker : workersSnapshot) {
                if (worker == null) {
                    continue;
                }
                try {
                    worker.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Thread interruption error", e);
                }
            }
        }

        if (coordinatorSnapshot != null) {
            try {
                coordinatorSnapshot.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Thread interruption error", e);
            }
        }

        synchronized (workerLifecycleLock) {
            calculationThreads = null;
            calculationCoordinator = null;
        }
    }


    public void startAutoPlay(boolean aiIsWhite, boolean aiIsBlack) {
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
        shutdownWorkerThreads();

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

            calculationRequests.markProcessingStart();
            try {
                if (request.stop) {
                    if (workerShutdown) {
                        break;
                    }
                    continue;
                }

                if (!keepCalculating) {
                    lastObservedHash = Long.MIN_VALUE;
                    continue;
                }

                long targetHash = request.boardHash;
                if (targetHash == lastObservedHash) {
                    continue;
                }

                currentBoardState = mainEngine.getBoardStateHash();
                beforeCalculationBoardState = currentBoardState;
                performCalculation();
                lastObservedHash = currentBoardState;
            } finally {
                calculationRequests.markProcessingEnd();
            }
        }
    }


    private void performCalculation() {
        log.debug(" --- TranspositionTable[{}/{}] --- ", transpositionTable.size(), transpositionTableCapacity);

        try {
            Engine simulatorEngine = mainEngine.createSimulation();
            long boardStateHash = simulatorEngine.getBoardStateHash();
            log.debug("boardStateBeforeCalculation {}, currentBoardState {}", beforeCalculationBoardState, currentBoardState);

            boolean isWhite = simulatorEngine.whitesTurn();
            TimeManager.TimeBudget budget = timeManager.beginSearch();
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

            SearchTask task;
            synchronized (searchConfigLock) {
                while (reconfiguringSearchThreads) {
                    try {
                        searchConfigLock.wait();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                Engine rootSnapshot = simulatorEngine.createSimulation();
                task = new SearchTask(
                        searchIdGenerator.incrementAndGet(),
                        boardStateHash,
                        isWhite,
                        budget,
                        lazySmpThreads,
                        rootSnapshot
                );
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
            }
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
        clearStaticEvalCache();
        if (task == null) {
            logAndResetWorkerInstrumentation();
            return;
        }
        if (currentBoardState != task.getBoardHash()) {
            logAndResetWorkerInstrumentation();
            return;
        }

        BestMoveDepth best = task.getBest();
        int move = best.move;

        if (move != -1) {
            currentBestMove = move;
            bestMoveForHash = task.getBoardHash();
            previousBestMove = move;
            previousBestMoveHash = task.getBoardHash();
            searchResultReady = true;

            if (!simulatorEngine.getGameState().isTerminal()
                    && !simulatorEngine.getGameState().isInStateCheckMate()) {
                fillCalculatedLine(simulatorEngine);
            } else {
                this.calculatedLine = Collections.synchronizedList(new ArrayList<>());
            }
            logAndResetWorkerInstrumentation();
            return;
        }

        if (previousBestMove != -1 && previousBestMoveHash == task.getBoardHash() &&
                isMoveStillLegal(simulatorEngine, previousBestMove)) {
            currentBestMove = previousBestMove;
            bestMoveForHash = task.getBoardHash();
            previousBestMoveHash = task.getBoardHash();
            searchResultReady = true;
            fillCalculatedLine(simulatorEngine);
            logAndResetWorkerInstrumentation();
            return;
        }

        currentBestMove = -1;
        bestMoveForHash = -1;
        previousBestMove = -1;
        previousBestMoveHash = -1;
        searchResultReady = false;
        this.calculatedLine = Collections.synchronizedList(new ArrayList<>());
        logAndResetWorkerInstrumentation();
    }

    private boolean isMoveStillLegal(Engine simulatorEngine, int move) {
        if (simulatorEngine.getGameState().isTerminal()) return false;
        IntArrayList legalMoves = simulatorEngine.getAllLegalMoves();
        return MoveContainerUtils.contains(legalMoves, move);
    }

    private void logAndResetWorkerInstrumentation() {
        if (!workerStatsEnabled || workerInstrumentation == null) {
            return;
        }
        String summary = workerInstrumentation.buildSummaryAndReset(lazySmpThreads);
        if (!summary.isEmpty()) {
            log.info("Lazy SMP worker diagnostics: {}", summary);
        }
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

    private boolean abortRequested(long fallbackDeadline) {
        if (Thread.currentThread().isInterrupted()) return true;
        SearchTask t = threadSearchTask.get();
        long hardDeadline = fallbackDeadline;
        long softDeadline = Long.MAX_VALUE;
        if (t != null) {
            hardDeadline = t.getHardDeadline();
            softDeadline = t.getSoftDeadline();
        }
        long now = System.nanoTime();
        if (now >= hardDeadline) return true;
        if (now >= softDeadline && t != null) {
            t.requestStop();
        }
        if (positionChanged()) return true;
        return t != null && t.isStopRequested();
    }

    private RootSearchResult getBestMoveParallel(Engine simulatorEngine,
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
        if (orderedMoves.isEmpty()) return RootSearchResult.completed(null);

        int baseFanout = Math.min(rootParallelLimit, Math.max(0, orderedMoves.size() - 1));
        ExecutorService pool = this.searchPool;
        int helperCapacity = 0;
        if (pool instanceof ThreadPoolExecutor executor) {
            helperCapacity = executor.getMaximumPoolSize();
        } else if (searchThreads > 0) {
            helperCapacity = searchThreads;
        }

        if (helperCapacity <= 0 && baseFanout > 0) {
            return getBestMove(simulatorEngine, isWhitesTurn, depth, deadline, alpha, beta, rng);
        }

        int helperFanoutCap = helperCapacity > 0
                ? Math.max(1, (int) Math.round(helperCapacity * rootFanoutRatio))
                : baseFanout;

        final int fanout = helperCapacity > 0
                ? Math.min(baseFanout, helperFanoutCap)
                : baseFanout;
        final boolean logFanout = rootFanoutStatsEnabled && log.isInfoEnabled();

        maybeRotateRootMoves(orderedMoves, rng);

        int firstMove = orderedMoves.getInt(0);
        int bestMove;
        double bestScore;

        boolean aborted = false;

        if (abortRequested(deadline)) {
            return RootSearchResult.aborted(null);
        }

        simulatorEngine.performMove(firstMove);
        long firstMoveHash = simulatorEngine.getBoardStateHash();
        double firstScore;
        if (simulatorEngine.getGameState().isInStateCheckMate()) {
            firstScore = isWhitesTurn ? (CHECKMATE - 1) : -(CHECKMATE - 1);
        } else if (simulatorEngine.getGameState().isTerminal()) { // <-- terminal only (stalemate/50/3fold)
            firstScore = evaluateStaticPosition(simulatorEngine.getGameState(), firstMoveHash, !isWhitesTurn, depth);
            if (isWhitesTurn) firstScore = -firstScore;
        } else {
            // Non-terminal (incl. insufficient material): keep searching
            firstScore = alphaBeta(simulatorEngine, depth - 1, alpha, beta, !isWhitesTurn, deadline, firstMove, 1, 0);
            if (firstScore == EXIT_FLAG || abortRequested(deadline)) {
                simulatorEngine.undoLastMove();
                return RootSearchResult.aborted(null);
            }
        }
        simulatorEngine.undoLastMove();

        bestMove = firstMove;
        bestScore = firstScore;

        if (logFanout) {
            log.info("Root fanout diag start: task={}, depth={}, legalMoves={}, helperCapacity={}, baseFanout={}, fanout={}, firstMove={}",
                    task.getId(), depth, orderedMoves.size(), helperCapacity, baseFanout, fanout,
                    Move.convertIntToMove(firstMove));
        }

        if (isWhitesTurn) alpha = Math.max(alpha, firstScore);
        else beta = Math.min(beta, firstScore);
        if (alpha >= beta) {
            MoveAndScore candidate = createCandidate(bestMove, bestScore);
            return RootSearchResult.completed(candidate);
        }

        if (fanout <= 0) {
            MoveAndScore candidate = createCandidate(bestMove, bestScore);
            return RootSearchResult.completed(candidate);
        }

        final CompletionService<MoveAndScore> ecs = new ExecutorCompletionService<>(searchPool);
        final List<Future<MoveAndScore>> futures = new ArrayList<>();

        final AtomicReference<RootSearchState> stateRef = new AtomicReference<>(
                new RootSearchState(alpha, beta, new MoveAndScore(bestMove, bestScore))
        );
        final AtomicBoolean stopRef = new AtomicBoolean(false);
        final java.util.concurrent.locks.ReentrantLock fullResLock = new java.util.concurrent.locks.ReentrantLock();
        int submittedParallel = 0;
        int completedParallel = 0;
        int sequentialProcessed = 0;

        IntFunction<Callable<MoveAndScore>> taskFactory = (moveInt) -> () -> {
            Long2DoubleOpenHashMap evalCache = staticEvalCache.get();
            evalCache.clear();
            SearchTask previousTask = threadSearchTask.get();
            threadSearchTask.set(task);
            try {
                if (task.isStopRequested() || stopRef.get() || abortRequested(deadline)) return null;
                Heuristics helperHeuristics = prepareHelperHeuristics(task, depth);
                Engine workerEngine = null;
                try {
                    workerEngine = borrowWorkerSimulation(simulatorEngine);
                    workerEngine.performMove(moveInt);
                    long workerHash = workerEngine.getBoardStateHash();

                    RootSearchState snapshot = stateRef.get();
                    double currentAlpha = snapshot.alpha();
                    double currentBeta = snapshot.beta();
                    double pAlpha, pBeta;
                    if (isWhitesTurn) {
                        pAlpha = currentAlpha;
                        pBeta = currentAlpha + 1;
                    } else {
                        pAlpha = currentBeta - 1;
                        pBeta = currentBeta;
                    }

                    double probe;
                    if (workerEngine.getGameState().isInStateCheckMate()) {
                        probe = isWhitesTurn ? (CHECKMATE - 1) : -(CHECKMATE - 1);
                    } else if (workerEngine.getGameState().isTerminal()) { // <-- terminal only
                        probe = evaluateStaticPosition(workerEngine.getGameState(), workerHash, !isWhitesTurn, depth);
                        if (isWhitesTurn) probe = -probe;
                    } else {
                        probe = alphaBeta(workerEngine, depth - 1, pAlpha, pBeta, !isWhitesTurn, deadline, moveInt, 1, 0);
                        if (probe == EXIT_FLAG || abortRequested(deadline)) return null;
                    }

                    boolean needsFull = isWhitesTurn ? (probe > snapshot.alpha()) : (probe < snapshot.beta());
                    double finalScore = probe;

                    if (needsFull && !stopRef.get()) {
                        fullResLock.lock();
                        try {
                            if (!stopRef.get() && !abortRequested(deadline)) {
                                RootSearchState locked = stateRef.get();
                                double aNow = locked.alpha();
                                double bNow = locked.beta();
                                double full = alphaBeta(workerEngine, depth - 1, aNow, bNow, !isWhitesTurn, deadline, moveInt, 1, 0);
                                if (full != EXIT_FLAG) {
                                    finalScore = full;
                                    updateRootState(stateRef, isWhitesTurn, moveInt, full, stopRef);
                                }
                            }
                        } finally {
                            fullResLock.unlock();
                        }
                    } else {
                        updateRootState(stateRef, isWhitesTurn, moveInt, finalScore, stopRef);
                    }

                    return new MoveAndScore(moveInt, finalScore);
                } finally {
                    evalCache.clear();
                    releaseWorkerSimulation(workerEngine, task.getRootSnapshot());
                    if (helperHeuristics.hasUpdates()) mergeThreadHeuristics(helperHeuristics);
                    else helperHeuristics.resetUpdates();
                }
            } finally {
                if (previousTask != null) {
                    threadSearchTask.set(previousTask);
                } else {
                    threadSearchTask.remove();
                }
            }
        };

        int nextMoveIndex = 1;
        int active = 0;

        try {
            while (active < fanout && nextMoveIndex < orderedMoves.size() && !stopRef.get()) {
                if (abortRequested(deadline)) {
                    aborted = true;
                    break;
                }
                int moveInt = orderedMoves.getInt(nextMoveIndex++);
                Future<MoveAndScore> submitted = ecs.submit(taskFactory.apply(moveInt));
                futures.add(submitted);
                submittedParallel++;
                active++;
            }

            while (active > 0) {
                if (abortRequested(deadline)) {
                    aborted = true;
                    break;
                }

                Future<MoveAndScore> future = ecs.take();
                active--;
                try {
                    MoveAndScore res = future.get();
                    if (res != null) {
                        RootSearchState state = stateRef.get();
                        alpha = state.alpha();
                        beta = state.beta();
                        MoveAndScore best = state.best();
                        if (best != null) {
                            bestMove = best.move;
                            bestScore = best.score;
                        }
                        completedParallel++;
                    }
                } catch (ExecutionException ex) {
                    log.warn("Parallel root worker failed", ex.getCause());
                }

                if (stopRef.get()) {
                    continue;
                }

                while (active < fanout && nextMoveIndex < orderedMoves.size() && !stopRef.get()) {
                    if (abortRequested(deadline)) {
                        aborted = true;
                        break;
                    }
                    int moveInt = orderedMoves.getInt(nextMoveIndex++);
                    Future<MoveAndScore> submitted = ecs.submit(taskFactory.apply(moveInt));
                    futures.add(submitted);
                    submittedParallel++;
                    active++;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            aborted = true;
        } catch (Exception ex) {
            log.warn("Parallel root YBWC error", ex);
        } finally {
            for (Future<MoveAndScore> f : futures) if (!f.isDone()) f.cancel(true);
        }

        RootSearchState finalState = stateRef.get();
        MoveAndScore bestCandidate = finalState.best();
        alpha = finalState.alpha();
        beta = finalState.beta();
        if (bestCandidate != null) {
            bestMove = bestCandidate.move;
            bestScore = bestCandidate.score;
        }

        if (!stopRef.get()) {
            for (int i = nextMoveIndex; i < orderedMoves.size(); i++) {
                if (abortRequested(deadline)) {
                    aborted = true;
                    break;
                }

                int moveInt = orderedMoves.getInt(i);
                sequentialProcessed++;
                simulatorEngine.performMove(moveInt);

                double score;
                if (simulatorEngine.getGameState().isInStateCheckMate()) {
                    score = isWhitesTurn ? (CHECKMATE - 1) : -(CHECKMATE - 1);
                } else if (simulatorEngine.getGameState().isTerminal()) {
                    long childHash = simulatorEngine.getBoardStateHash();
                    score = evaluateStaticPosition(simulatorEngine.getGameState(), childHash, !isWhitesTurn, depth);
                    if (isWhitesTurn) {
                        score = -score;
                    }
                } else {
                    score = alphaBeta(simulatorEngine, depth - 1, alpha, beta, !isWhitesTurn, deadline, moveInt, 1, 0);
                    if (score == EXIT_FLAG || abortRequested(deadline)) {
                        simulatorEngine.undoLastMove();
                        aborted = true;
                        break;
                    }
                }
                simulatorEngine.undoLastMove();

                if (isBetterScore(isWhitesTurn, score, bestScore)) {
                    bestScore = score;
                    bestMove = moveInt;
                }

                if (isWhitesTurn) {
                    alpha = Math.max(alpha, score);
                } else {
                    beta = Math.min(beta, score);
                }

                if (alpha >= beta) {
                    stopRef.set(true);
                    break;
                }
        }

        if (logFanout) {
            int remainingCandidates = Math.max(0, (orderedMoves.size() - 1) - submittedParallel - sequentialProcessed);
            log.info("Root fanout diag end: task={}, depth={}, parallelSubmitted={}, parallelCompleted={}, sequentialFallback={}, remainingCandidates={}, aborted={}",
                    task.getId(), depth, submittedParallel, completedParallel, sequentialProcessed, remainingCandidates, aborted);
        }

        MoveAndScore updatedBest = createCandidate(bestMove, bestScore);
            if (updatedBest != null) {
                stateRef.set(new RootSearchState(alpha, beta, updatedBest));
            }
        }
        MoveAndScore candidate = bestMove != -1 ? createCandidate(bestMove, bestScore) : null;
        if (abortRequested(deadline)) {
            aborted = true;
        }
        return aborted ? RootSearchResult.aborted(candidate) : RootSearchResult.completed(candidate);
    }


    private void updateRootState(AtomicReference<RootSearchState> stateRef,
                                 boolean isWhiteToMove,
                                 int move,
                                 double score,
                                 AtomicBoolean stopRef) {
        while (true) {
            RootSearchState current = stateRef.get();
            double nextAlpha = current.alpha();
            double nextBeta = current.beta();
            MoveAndScore currentBest = current.best();
            boolean changed = false;

            if (isWhiteToMove) {
                if (score > nextAlpha) {
                    nextAlpha = score;
                    changed = true;
                }
            } else {
                if (score < nextBeta) {
                    nextBeta = score;
                    changed = true;
                }
            }

            MoveAndScore nextBest = currentBest;
            if (currentBest == null || isBetterScore(isWhiteToMove, score, currentBest.score)) {
                if (currentBest == null || currentBest.move != move || Double.compare(currentBest.score, score) != 0) {
                    nextBest = new MoveAndScore(move, score);
                    changed = true;
                }
            }

            if (!changed) {
                return;
            }

            RootSearchState updated = new RootSearchState(nextAlpha, nextBeta, nextBest);
            if (stateRef.compareAndSet(current, updated)) {
                if (updated.alpha() >= updated.beta()) {
                    stopRef.set(true);
                }
                return;
            }
        }
    }

    private record RootSearchState(double alpha, double beta, MoveAndScore best) {
        private RootSearchState {
            if (best == null) {
                throw new IllegalArgumentException("best must not be null");
            }
        }
    }


    private boolean shouldStopCalculating(SearchTask task) {
        long deadline = task != null ? task.getHardDeadline() : Long.MAX_VALUE;
        return abortRequested(deadline);
    }

    private void fillCalculatedLine(Engine simulation) {
        // If the root is terminal, there is no PV to construct.
        if (simulation.getGameState().isTerminal()
                || simulation.getGameState().isInStateCheckMate()) {
            this.calculatedLine = Collections.synchronizedList(new ArrayList<>());
            return;
        }

        long rootHash = simulation.getBoardStateHash();
        List<MoveAndScore> pv = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int movesPerformed = 0;

        java.util.function.IntPredicate isLegalNow = (mv) -> {
            // If terminal, no legal move exists, skip
            if (simulation.getGameState().isTerminal()) return false;
            IntArrayList legal = simulation.getAllLegalMoves();
            return MoveContainerUtils.contains(legal, mv);
        };

        TranspositionTableEntry rootEntry = transpositionTable.get(rootHash);
        int seedMove = -1;
        Double seedScore = null;

        if (rootEntry != null
                && rootEntry.bestMove != -1
                && MoveHelper.isWhitesMove(rootEntry.bestMove) == simulation.whitesTurn()
                && isLegalNow.test(rootEntry.bestMove)) {
            seedMove = rootEntry.bestMove;
            if (rootEntry.nodeType == NodeType.EXACT) {
                seedScore = rootEntry.score;
            }
        }

        if (seedMove == -1 && currentBestMove != -1
                && MoveHelper.isWhitesMove(currentBestMove) == simulation.whitesTurn()
                && isLegalNow.test(currentBestMove)) {
            seedMove = currentBestMove;
        }

        if (seedMove == -1) {
            // Fallback: first legal, if any (only when not terminal)
            if (!simulation.getGameState().isTerminal()) {
                IntArrayList legal = simulation.getAllLegalMoves();
                if (!legal.isEmpty()) {
                    int mv = legal.getInt(0);
                    if (MoveHelper.isWhitesMove(mv) == simulation.whitesTurn()) {
                        seedMove = mv;
                    }
                }
            }
        }

        if (seedMove == -1) {
            this.calculatedLine = Collections.synchronizedList(new ArrayList<>());
            return;
        }

        // Before making any move, double-check we’re still non-terminal
        if (simulation.getGameState().isTerminal()) {
            this.calculatedLine = Collections.synchronizedList(new ArrayList<>());
            return;
        }

        try {
            pv.add(new MoveAndScore(seedMove, seedScore != null ? seedScore : 0.0));
            simulation.performMove(seedMove);
            movesPerformed++;
        } catch (IllegalStateException ise) {
            // Defensive: if the engine still considers this node terminal, abort PV
            this.calculatedLine = Collections.synchronizedList(new ArrayList<>());
            return;
        }

        long curHash = simulation.getBoardStateHash();


        while (true) {
            if (!seen.add(curHash)) break;
            if (simulation.getGameState().isTerminal()) break;

            TranspositionTableEntry e = transpositionTable.get(curHash);
            if (e == null || e.bestMove == -1 || e.nodeType != NodeType.EXACT) break;

            int mv = e.bestMove;
            if (MoveHelper.isWhitesMove(mv) != simulation.whitesTurn()) break;
            if (!isLegalNow.test(mv)) break;

            pv.add(new MoveAndScore(mv, e.score));
            try {
                simulation.performMove(mv);
                movesPerformed++;
                curHash = simulation.getBoardStateHash();
            } catch (IllegalStateException ise) {
                // Stop PV growth if engine signals terminal unexpectedly
                break;
            }
        }

        for (int i = 0; i < movesPerformed; i++) simulation.undoLastMove();
        this.calculatedLine = Collections.synchronizedList(pv);
    }


    private RootSearchResult getBestMove(Engine simulatorEngine, boolean isWhitesTurn, int depth, long deadline,
                                         double alpha, double beta, SplittableRandom rng) {
        int bestMove = -1;
        double bestScore = isWhitesTurn ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;

        boolean aborted = false;

        IntArrayList sortedMoves = sortMovesByEfficiency(simulatorEngine.getAllLegalMoves(), depth,
                simulatorEngine.getBoardStateHash(), -1, simulatorEngine);
        maybeRotateRootMoves(sortedMoves, rng);
        promoteTablebaseMove(sortedMoves, simulatorEngine);

        Optional<TablebaseHit> rootTablebase = resolveTablebaseHit(simulatorEngine, isWhitesTurn);
        if (rootTablebase.isPresent()) {
            TablebaseHit hit = rootTablebase.get();
            int candidateMove = hit.bestMove();
            if (candidateMove >= 0 && MoveContainerUtils.contains(sortedMoves, candidateMove)) {
                return RootSearchResult.completed(createCandidate(candidateMove, hit.score()));
            }
        }

        for (int idx = 0; idx < sortedMoves.size(); idx++) {
            int moveInt = sortedMoves.getInt(idx);
            if (abortRequested(deadline)) {
                aborted = true;
                break;
            }

            simulatorEngine.performMove(moveInt);
            long childHash = simulatorEngine.getBoardStateHash();
            double score;
            if (simulatorEngine.getGameState().isInStateCheckMate()) {
                score = isWhitesTurn ? (CHECKMATE - 1) : -(CHECKMATE - 1);
            } else if (simulatorEngine.getGameState().isTerminal()) { // <-- terminal only
                score = evaluateStaticPosition(simulatorEngine.getGameState(), childHash, !isWhitesTurn, depth);
                if (isWhitesTurn) {
                    score = -score;
                }
            } else {
                // Non-terminal (incl. insufficient material):
                score = alphaBeta(simulatorEngine, depth - 1, alpha, beta, !isWhitesTurn, deadline, moveInt, 1, 0);
                if (score == EXIT_FLAG || abortRequested(deadline)) {
                    simulatorEngine.undoLastMove();
                    aborted = true;
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
        MoveAndScore candidate = bestMove != -1 ? createCandidate(bestMove, bestScore) : null;
        return aborted ? RootSearchResult.aborted(candidate) : RootSearchResult.completed(candidate);
    }

    private MoveAndScore createCandidate(int move, double score) {
        if (move == -1) return null;
        if (!Double.isFinite(score)) return null;
        return new MoveAndScore(move, score);
    }

    /**
     * *
     * 5rkr/pp2Rp2/1b1p1Pb1/3P2Q1/2n3P1/2p5/P4P2/4R1K1 w - - 1 0
     * *
     */
    private Optional<TablebaseHit> resolveTablebaseHit(Engine engine, boolean isWhite) {
        // Fetch any cached TB result from GameState
        TablebaseResult result = engine.getGameState().getLastTablebaseResult().orElse(null);

        // Always try probing the Syzygy tablebase if service is available
        if (tablebaseService != null) {
            Optional<SyzygyProbeResult> probe = tablebaseService.probe(engine.getBitBoard());
            if (probe.isPresent()) {
                result = TablebaseResult.from(probe.get());
                engine.getGameState().setLastTablebaseResult(result);
            }
        }

        // No usable WDL (unknown or incomplete probe)
        if (!isExactWdl(result)) {
            return Optional.empty();
        }

        // Stable evaluation: compute from White's perspective and keep the white-oriented
        // value so all callers reason about tablebase scores consistently.
        double whitePerspective = Score.tablebaseToEvaluation(result, engine.whitesTurn(),
                engine.getGameState().getHalfmoveClock());
        double eval = whitePerspective;

        // Determine best move via TB guidance (if available)
        int bestMove = determineTablebaseBestMove(engine, result, isWhite);

        return Optional.of(new TablebaseHit(eval, bestMove, result));
    }



    private int determineTablebaseBestMove(Engine simulatorEngine, TablebaseResult parentResult, boolean parentIsWhite) {
        IntArrayList legal = simulatorEngine.getAllLegalMoves();
        if (legal.isEmpty()) {
            return -1;
        }

        int suggestedMove = -1;
        TablebaseContinuation bestContinuation = null;

        if (parentResult != null) {
            Optional<SyzygyMove> suggestion = parentResult.recommendedMove();
            if (suggestion.isPresent()) {
                suggestedMove = findSuggestedMove(legal, suggestion.get());
                if (suggestedMove != -1) {
                    Optional<TablebaseContinuation> continuation = evaluateTablebaseContinuation(simulatorEngine, suggestedMove);
                    if (continuation.isPresent()) {
                        TablebaseContinuation candidate = continuation.get();
                        if (isContinuationConsistent(parentResult, candidate)) {
                            return candidate.move();
                        }
                        bestContinuation = candidate;
                    }
                }
            }
        }

        for (int i = 0; i < legal.size(); i++) {
            int move = legal.getInt(i);
            if (move == suggestedMove) {
                continue;
            }
            Optional<TablebaseContinuation> continuation = evaluateTablebaseContinuation(simulatorEngine, move);
            if (continuation.isEmpty()) {
                continue;
            }
            TablebaseContinuation candidate = continuation.get();
            if (bestContinuation == null
                    || isContinuationBetter(parentIsWhite, candidate, bestContinuation, parentResult)) {
                bestContinuation = candidate;
            }
        }
        return bestContinuation != null ? bestContinuation.move() : -1;
    }

    private Optional<TablebaseContinuation> evaluateTablebaseContinuation(Engine simulatorEngine, int move) {
        boolean zeroing = MoveHelper.isCapture(move) || MoveHelper.derivePieceTypeBits(move) == 1;
        simulatorEngine.performMove(move);
        try {
            Optional<TablebaseResult> childResult = resolveExactTablebaseResult(simulatorEngine);
            if (childResult.isEmpty()) {
                return Optional.empty();
            }
            TablebaseResult result = childResult.get();
            double evaluation = Score.tablebaseToEvaluation(result, simulatorEngine.whitesTurn(),
                    simulatorEngine.getGameState().getHalfmoveClock());
            return Optional.of(new TablebaseContinuation(move, evaluation, result, zeroing));
        } finally {
            simulatorEngine.undoLastMove();
        }
    }

    private Optional<TablebaseResult> resolveExactTablebaseResult(Engine engine) {
        TablebaseResult result = engine.getGameState().getLastTablebaseResult().orElse(null);
        if (tablebaseService != null) {
            Optional<SyzygyProbeResult> probe = tablebaseService.probe(engine.getBitBoard());
            if (probe.isPresent()) {
                result = TablebaseResult.from(probe.get());
                engine.getGameState().setLastTablebaseResult(result);
            }
        }
        if (!isExactWdl(result)) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    private boolean isContinuationConsistent(TablebaseResult parentResult, TablebaseContinuation continuation) {
        if (parentResult == null || continuation == null) {
            return false;
        }
        int parentSign = Integer.signum(parentResult.wdl().score());
        int childSign = Integer.signum(continuation.result().wdl().score());
        if (parentSign == 0) {
            return childSign == 0;
        }
        return parentSign == -childSign;
    }

    private boolean isContinuationBetter(boolean parentIsWhite,
                                         TablebaseContinuation candidate,
                                         TablebaseContinuation incumbent,
                                         TablebaseResult parentResult) {
        if (incumbent == null) {
            return true;
        }
        if (candidate == null) {
            return false;
        }

        double candidateEval = candidate.evaluation();
        double incumbentEval = incumbent.evaluation();
        if (!Double.isFinite(candidateEval)) {
            return false;
        }
        if (!Double.isFinite(incumbentEval)) {
            return true;
        }

        double diff = candidateEval - incumbentEval;
        if (Math.abs(diff) > TB_TIE_EPSILON) {
            return parentIsWhite ? diff > 0 : diff < 0;
        }

        int outcome = parentResult != null ? Integer.signum(parentResult.wdl().score()) : 0;
        return switch (outcome) {
            case 1 -> preferWinningContinuation(candidate, incumbent);
            case -1 -> preferDefensiveContinuation(candidate, incumbent);
            case 0 -> preferDrawingContinuation(candidate, incumbent);
            default -> false;
        };
    }

    private boolean preferWinningContinuation(TablebaseContinuation candidate, TablebaseContinuation incumbent) {
        int candidateDtz = normaliseDistance(candidate.result().dtz(), Integer.MAX_VALUE);
        int incumbentDtz = normaliseDistance(incumbent.result().dtz(), Integer.MAX_VALUE);
        if (candidateDtz != incumbentDtz) {
            return candidateDtz < incumbentDtz;
        }

        int candidateDtm = normaliseDistance(candidate.result().dtm(), Integer.MAX_VALUE);
        int incumbentDtm = normaliseDistance(incumbent.result().dtm(), Integer.MAX_VALUE);
        if (candidateDtm != incumbentDtm) {
            return candidateDtm < incumbentDtm;
        }

        if (candidate.zeroingMove() != incumbent.zeroingMove()) {
            return candidate.zeroingMove();
        }
        return false;
    }

    private boolean preferDefensiveContinuation(TablebaseContinuation candidate, TablebaseContinuation incumbent) {
        int candidateDtz = normaliseDistance(candidate.result().dtz(), Integer.MIN_VALUE);
        int incumbentDtz = normaliseDistance(incumbent.result().dtz(), Integer.MIN_VALUE);
        if (candidateDtz != incumbentDtz) {
            return candidateDtz > incumbentDtz;
        }

        int candidateDtm = normaliseDistance(candidate.result().dtm(), Integer.MIN_VALUE);
        int incumbentDtm = normaliseDistance(incumbent.result().dtm(), Integer.MIN_VALUE);
        if (candidateDtm != incumbentDtm) {
            return candidateDtm > incumbentDtm;
        }

        if (candidate.zeroingMove() != incumbent.zeroingMove()) {
            return !candidate.zeroingMove();
        }
        return false;
    }

    private boolean preferDrawingContinuation(TablebaseContinuation candidate, TablebaseContinuation incumbent) {
        int candidateDtz = normaliseDistance(candidate.result().dtz(), Integer.MAX_VALUE);
        int incumbentDtz = normaliseDistance(incumbent.result().dtz(), Integer.MAX_VALUE);
        if (candidateDtz != incumbentDtz) {
            return candidateDtz < incumbentDtz;
        }
        if (candidate.zeroingMove() != incumbent.zeroingMove()) {
            return candidate.zeroingMove();
        }
        int candidateDtm = normaliseDistance(candidate.result().dtm(), Integer.MAX_VALUE);
        int incumbentDtm = normaliseDistance(incumbent.result().dtm(), Integer.MAX_VALUE);
        if (candidateDtm != incumbentDtm) {
            return candidateDtm < incumbentDtm;
        }
        return false;
    }

    private int normaliseDistance(OptionalInt value, int fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        return Math.abs(value.getAsInt());
    }

    private int findSuggestedMove(IntArrayList legal, SyzygyMove suggestion) {
        int fromIndex = suggestion.fromIndex();
        int toIndex = suggestion.toIndex();
        int promotionBits = suggestion.promotionPieceTypeBits();
        for (int i = 0; i < legal.size(); i++) {
            int move = legal.getInt(i);
            if (MoveHelper.deriveFromIndex(move) != fromIndex) {
                continue;
            }
            if (MoveHelper.deriveToIndex(move) != toIndex) {
                continue;
            }
            int movePromotion = MoveHelper.derivePromotionPieceTypeBits(move);
            if (promotionBits == 0) {
                if (movePromotion != 0) {
                    continue;
                }
            } else if (movePromotion != promotionBits) {
                continue;
            }

            return move;
        }
        return -1;
    }

    private void promoteTablebaseMove(IntArrayList moves, Engine engine) {
        if (moves == null || moves.isEmpty()) {
            return;
        }
        TablebaseResult result = engine.getGameState().getLastTablebaseResult().orElse(null);
        if (result == null) {
            return;
        }
        Optional<SyzygyMove> suggestion = result.recommendedMove();
        if (suggestion.isEmpty()) {
            return;
        }
        int matchedMove = findSuggestedMove(moves, suggestion.get());
        if (matchedMove == -1) {
            return;
        }
        int index = moves.indexOf(matchedMove);
        if (index <= 0) {
            return;
        }
        int first = moves.getInt(0);
        moves.set(0, matchedMove);
        moves.set(index, first);
    }

    private double evaluateTablebaseChild(Engine simulatorEngine, boolean parentIsWhite) {
        Optional<TablebaseResult> childResult = resolveExactTablebaseResult(simulatorEngine);
        if (childResult.isEmpty()) {
            return Double.NaN;
        }
        double whitePerspective = Score.tablebaseToEvaluation(childResult.get(), simulatorEngine.whitesTurn(),
                simulatorEngine.getGameState().getHalfmoveClock());
        return whitePerspective;
    }

    private boolean isExactWdl(TablebaseResult result) {
        if (result == null) {
            return false;
        }
        SyzygyWdl wdl = result.wdl();
        return wdl == SyzygyWdl.WIN || wdl == SyzygyWdl.LOSS || wdl == SyzygyWdl.DRAW;
    }

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

        long boardHash = simulatorEngine.getBoardStateHash();
        boolean inCheck = isSideInCheck(simulatorEngine, isWhite);

        // Terminal states first, with distance-to-mate
        if (simulatorEngine.getGameState().isInStateCheckMate()) {
            double m = CHECKMATE - plyFromRoot;
            return isWhite ? -m : +m; // side-to-move is losing here
        }
        if (simulatorEngine.getGameState().isTerminal()) { // <-- terminal draw only
            return evaluateStaticPosition(simulatorEngine.getGameState(), boardHash, isWhite, plyFromRoot);
        }

        Optional<TablebaseHit> tablebaseHit = resolveTablebaseHit(simulatorEngine, isWhite);
        if (tablebaseHit.isPresent()) {
            TablebaseHit hit = tablebaseHit.get();
            int bestMove = hit.bestMove() >= 0 ? hit.bestMove() : -1;
            double storedScore = toStoredMateScore(hit.score(), plyFromRoot);
            transpositionTable.put(boardHash,
                    new TranspositionTableEntry(storedScore, depth, NodeType.EXACT, bestMove), depth);
            return hit.score();
        }

        if (depth <= 0) {
            double eval = evaluateBoard(simulatorEngine, isWhite, deadline);
            if (eval == EXIT_FLAG) return EXIT_FLAG;
            if (!isWhite) eval = -eval;
            return eval;
        }

        // Transposition table lookup
        TranspositionTableEntry entry = transpositionTable.get(boardHash);
        if (entry != null && entry.depth >= depth) {
            double ttScore = fromStoredMateScore(entry.score, plyFromRoot);
            if (entry.nodeType == NodeType.EXACT) return ttScore;
            if (entry.nodeType == NodeType.LOWERBOUND && ttScore > alpha) alpha = ttScore;
            else if (entry.nodeType == NodeType.UPPERBOUND && ttScore < beta) beta = ttScore;
            if (alpha >= beta) return ttScore;
        }

        // -------- Safer Null-move pruning (same as before, but use depthHere) --------
        IntArrayList moves = simulatorEngine.getAllLegalMoves();
        int mobility = moves.size();
        BitBoard bitBoard = simulatorEngine.getBitBoard();
        final int NULL_MOVE_SENTINEL = -1;
        boolean previousMoveWasNull = prevMove == NULL_MOVE_SENTINEL;
        if (previousMoveWasNull && plyFromRoot == 0) {
            // There was no real move before the root call, so treat it as eligible for a null move.
            previousMoveWasNull = false;
        }
        boolean allowNullMove = useNullMovePruning
                && !inCheck
                && !simulatorEngine.isEndgame()
                && !previousMoveWasNull;

        if (allowNullMove) {
            double mateThreatScore = CHECKMATE - (plyFromRoot + 1);
            boolean betaSignalsMateThreat = isWhite && Double.isFinite(beta) && beta >= mateThreatScore;
            boolean alphaSignalsMateThreat = !isWhite && Double.isFinite(alpha) && alpha <= -mateThreatScore;
            if (betaSignalsMateThreat || alphaSignalsMateThreat) {
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
                double swingThreshold = Math.max(nullSwingGuardMinCp, mateThreshold / nullSwingGuardDivisor);
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
            return maximizer(simulatorEngine, depth, alpha, beta, boardHash, alphaOriginal, betaOriginal, moves, deadline, prevMove, plyFromRoot, extStreak);
        } else {
            return minimizer(simulatorEngine, depth, alpha, beta, boardHash, alphaOriginal, betaOriginal, moves, deadline, prevMove, plyFromRoot, extStreak);
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

        double reductionEstimate = getReductionEstimate(depth, mobility, nonPawnMaterial);

        int reduction = (int) Math.floor(Math.max(0.0, reductionEstimate));
        return Math.min(reduction, maxReduction);
    }

    private double getReductionEstimate(int depth, int mobility, int nonPawnMaterial) {
        int depthCap = Math.max(1, nullMoveParameters.depthCap());
        int materialCap = Math.max(1, nullMoveParameters.materialCap());
        int mobilityCap = Math.max(1, nullMoveParameters.mobilityCap());

        double depthFactor = Math.min(Math.max(depth, 0), depthCap) / (double) depthCap;
        double materialFactor = Math.min(Math.max(nonPawnMaterial, 0), materialCap) / (double) materialCap;
        double mobilityFactor = Math.min(Math.max(mobility, 0), mobilityCap) / (double) mobilityCap;

        double reductionEstimate = nullMoveParameters.baseReduction()
                + (depthFactor * nullMoveParameters.depthWeight())
                + (materialFactor * nullMoveParameters.materialWeight())
                + (mobilityFactor * nullMoveParameters.mobilityWeight());

        if (nonPawnMaterial <= nullMoveParameters.lowMaterialThreshold()
                || mobility <= nullMoveParameters.lowMobilityThreshold()) {
            reductionEstimate -= nullMoveParameters.lowMaterialPenalty();
        }
        if (mobility <= nullMoveParameters.veryLowMobilityThreshold()) {
            reductionEstimate -= nullMoveParameters.veryLowMobilityPenalty();
        }
        return reductionEstimate;
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

    private int lmrReduction(int depth, int moveIndex, int historyScore) {
        if (depth <= 1) {
            return 0;
        }

        int clampedDepth = Math.max(1, Math.min(depth, LMR_MAX_DEPTH));
        int clampedMoveIndex = Math.max(0, Math.min(moveIndex, LMR_MAX_MOVES - 1));

        int historyReductionMax = Math.max(0, searchPruningParameters.historyReductionMax());
        int history = Math.max(0, Math.min(historyScore, historyReductionMax));
        double normalized = historyReductionMax == 0
                ? 0.0
                : history / (double) historyReductionMax;
        int buckets = Math.max(1, lmrBucketCount);
        double bucketPosition = buckets == 1 ? 0.0 : normalized * (buckets - 1);
        int lowerBucket = Math.max(0, (int) Math.floor(bucketPosition));
        int upperBucket = Math.min(buckets - 1, lowerBucket + 1);
        double fraction = bucketPosition - lowerBucket;

        int lowerValue = lmrReductionTable[clampedDepth][clampedMoveIndex][lowerBucket];
        if (upperBucket == lowerBucket) {
            return Math.min(lowerValue, depth - 1);
        }

        int upperValue = lmrReductionTable[clampedDepth][clampedMoveIndex][upperBucket];
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

    private int futilityMarginForRemainingDepth(int remainingDepth) {
        if (remainingDepth <= 1) {
            return searchPruningParameters.fpMarginDepth1();
        }
        if (remainingDepth == 2) {
            return searchPruningParameters.fpMarginDepth2();
        }
        return 0;
    }

    private double maximizer(Engine simulatorEngine, int depth, double alpha, double beta,
                             long boardHash, double alphaOriginal, double betaOriginal,
                             IntArrayList moves, long deadline, int prevMove, int plyFromRoot,
                             int extStreak) {
        double maxEval = Double.NEGATIVE_INFINITY;
        int bestMoveAtThisNode = -1;
        boolean bestZeroing = false;

        final boolean inCheckAtNode = isSideInCheck(simulatorEngine, true);
        final Heuristics heuristics = threadHeuristics.get();
        final int[][] historyTable = heuristics.history;

        IntArrayList orderedMoves = sortMovesByEfficiency(moves, depth, boardHash, prevMove, simulatorEngine);
        final Int2IntOpenHashMap seeCache = seeCacheThreadLocal.get();
        seeCache.clear();
        final SearchPruningParameters.Snapshot pruning = searchPruningParameters;
        final int hmpMinIndex = pruning.hmpMinIndex();
        final int hmpHistoryMax = pruning.hmpHistoryMax();
        final int lmpBase = pruning.lmpBase();
        final int lmpPerDepth = pruning.lmpPerDepth();
        final int iidReduceDepth = pruning.iidReduceDepth();
        final int lmrProtectPlyMax = pruning.lmrProtectPlyMax();
        final int lmrProtectIndexMax = pruning.lmrProtectIndexMax();
        final int lmrCapGoodQuiet = pruning.lmrCapForGoodQuiet();
        final int maxCheckExtensionStreak = Math.max(0, pruning.maxCheckExtensionStreak());
        final int seePruneNearRootPly = Math.max(0, pruning.seePruneNearRootPly());

        int baseRemainingDepth = Math.max(0, depth - 1);
        boolean futilityEligible = !inCheckAtNode
                && plyFromRoot > 0
                && depth >= 1
                && depth <= futilityMaxDepth
                && Double.isFinite(alpha);
        boolean futilityPossible = futilityEligible
                && ((baseRemainingDepth <= 1 && pruning.fpMarginDepth1() > 0)
                || (baseRemainingDepth == 2 && pruning.fpMarginDepth2() > 0));
        double staticEvalWhite = Double.NaN;
        if (futilityPossible) {
            staticEvalWhite = resolveScoreDifference(simulatorEngine.getGameState(), boardHash,
                    simulatorEngine.whitesTurn());
            if (!Double.isFinite(staticEvalWhite)) {
                futilityPossible = false;
            }
        }
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

            if (!inCheckAtNode
                    && isQuiet
                    && depth >= 2
                    && hmpMinIndex >= 0
                    && index >= hmpMinIndex
                    && historyScore <= hmpHistoryMax) {
                continue;
            }

            int seeGain = 0;
            boolean seeEvaluated = false;
            boolean seeWinsMaterial = false;

            // SEE pruning for losing captures/quiets (keep checks/promotions)
            boolean seePruneCandidate = (!inCheckAtNode && isCapture && !isPromotion) || isQuiet;
            if (seePruneCandidate) {
                seeGain = simulatorEngine.see(move, 0);
                seeEvaluated = true;
                boolean nearRoot = plyFromRoot <= seePruneNearRootPly;
                boolean allowSeePrune = seeGain < 0 && !nearRoot;
                if (allowSeePrune) {
                    simulatorEngine.performMove(move);
                    boolean givesCheckTmp = isSideInCheck(simulatorEngine, false);
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
            long enemyKingBB = boardBefore.getBlackKing();
            int enemyKingSquare = enemyKingBB != 0L ? Long.numberOfTrailingZeros(enemyKingBB) : -1;
            int enemyKingFile = enemyKingSquare >= 0 ? (enemyKingSquare & 7) : -1;
            long kingFileMask = enemyKingFile >= 0 ? FileMasks[enemyKingFile] : 0L;
            boolean touchesKingFile = enemyKingSquare >= 0 && (((from & 7) == enemyKingFile) || ((to & 7) == enemyKingFile));
            boolean affectsKingFilePawns = touchesKingFile && (movingPieceBits == 1 || capturedPieceBits == 1);
            int pawnsOnFileBefore = (enemyKingSquare >= 0 && affectsKingFilePawns) ? countPawnsOnFile(boardBefore, kingFileMask) : 0;

            boolean isTactical = isCapture || isPromotion;
            int lmpThreshold = lmpBase + depth * lmpPerDepth;
            if (!inCheckAtNode && !isTactical && depth <= lmpMaxDepth && index > lmpThreshold) {
                simulatorEngine.performMove(move);
                boolean givesCheckTmp = isSideInCheck(simulatorEngine, false);
                boolean attacksQueenTmp = attacksOpponentQueenNow(simulatorEngine, true);
                simulatorEngine.undoLastMove();
                if (!givesCheckTmp && !attacksQueenTmp) continue;
            }

            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();

            boolean givesCheck = isSideInCheck(simulatorEngine, false);
            boolean attacksQueen = attacksOpponentQueenNow(simulatorEngine, true);
            boolean attacksKingZone = attacksOpponentKingZone(simulatorEngine, true);
            boolean opensKingFile = openedFileTowardKing(simulatorEngine.getBitBoard(), kingFileMask, pawnsOnFileBefore, affectsKingFilePawns);

            if (futilityPossible && isQuiet && !givesCheck && !attacksQueen && !attacksKingZone
                    && !opensKingFile && !seeWinsMaterial) {
                int futilityMargin = futilityMarginForRemainingDepth(baseRemainingDepth);
                if (futilityMargin > 0 && staticEvalWhite + futilityMargin <= alpha) {
                    simulatorEngine.undoLastMove();
                    continue;
                }
            }

            int nextDepth = depth - 1;
            boolean forcing = givesCheck || attacksQueen;
            boolean allowExtend = forcing && extStreak < maxCheckExtensionStreak;
            if (allowExtend) nextDepth++;
            int nextExtStreak = allowExtend ? extStreak + 1 : 0;

            double eval = Double.NEGATIVE_INFINITY;
            TranspositionTableEntry entry = transpositionTable.get(newBoardHash);
            boolean ttExactHit = entry != null
                    && entry.nodeType == NodeType.EXACT
                    && entry.depth >= nextDepth;

            boolean usedTranspositionEval = false;
            if (ttExactHit) {
                eval = fromStoredMateScore(entry.score, plyFromRoot + 1);
                usedTranspositionEval = true;
            } else {
                if (!inCheckAtNode
                        && !isTactical
                        && !givesCheck
                        && !attacksQueen
                        && !attacksKingZone
                        && !opensKingFile
                        && iidReduceDepth > 0
                        && nextDepth - iidReduceDepth >= 1) {
                    double iidScore = alphaBeta(simulatorEngine, nextDepth - iidReduceDepth, alpha, beta,
                            false, deadline, move, plyFromRoot + 1, 0);
                    if (iidScore == EXIT_FLAG || positionChanged()) {
                        simulatorEngine.undoLastMove();
                        return EXIT_FLAG;
                    }
                    entry = transpositionTable.get(newBoardHash);
                    ttExactHit = entry != null
                            && entry.nodeType == NodeType.EXACT
                            && entry.depth >= nextDepth;
                    if (ttExactHit) {
                        eval = fromStoredMateScore(entry.score, plyFromRoot + 1);
                        usedTranspositionEval = true;
                    }
                }

                if (!usedTranspositionEval) {
                    boolean canReduce = !inCheckAtNode
                            && !isTactical
                            && !givesCheck
                            && !attacksQueen
                            && !attacksKingZone
                            && !opensKingFile
                            && !seeWinsMaterial
                            && nextDepth >= 2
                            && index > lmrProtectIndexMax;

                    if (plyFromRoot <= lmrProtectPlyMax) {
                        canReduce = false;
                    }

                    boolean usePvs = index > 0 && alpha != Double.NEGATIVE_INFINITY && beta != Double.POSITIVE_INFINITY;
                    double pBeta = usePvs ? (alpha + 1) : beta;

                    int reduction = 0;
                    if (canReduce) {
                        reduction = lmrReduction(nextDepth, index, historyScore);
                        if (reduction > 0 && isQuiet && historyScore > 0 && lmrCapGoodQuiet >= 0) {
                            reduction = Math.min(reduction, lmrCapGoodQuiet);
                        }
                        if (reduction <= 0) canReduce = false;
                    }

                    if (canReduce) {
                        int reduced = Math.max(1, nextDepth - reduction);
                        eval = alphaBeta(simulatorEngine, reduced, alpha, pBeta, false, deadline, move, plyFromRoot + 1, nextExtStreak);
                        if (eval == EXIT_FLAG || positionChanged()) {
                            simulatorEngine.undoLastMove();
                            return EXIT_FLAG;
                        }

                        boolean promising = eval > alpha;
                        if (promising) {
                            eval = alphaBeta(simulatorEngine, nextDepth, alpha, usePvs ? beta : pBeta,
                                    false, deadline, move, plyFromRoot + 1, nextExtStreak);
                            if (eval == EXIT_FLAG || positionChanged()) {
                                simulatorEngine.undoLastMove();
                                return EXIT_FLAG;
                            }
                        }
                    } else {
                        eval = alphaBeta(simulatorEngine, nextDepth, alpha, pBeta, false, deadline, move, plyFromRoot + 1, nextExtStreak);
                        if (eval == EXIT_FLAG || positionChanged()) {
                            simulatorEngine.undoLastMove();
                            return EXIT_FLAG;
                        }
                        if (usePvs && eval > alpha && eval < beta) {
                            eval = alphaBeta(simulatorEngine, nextDepth, alpha, beta, false, deadline, move, plyFromRoot + 1, nextExtStreak);
                            if (eval == EXIT_FLAG || positionChanged()) {
                                simulatorEngine.undoLastMove();
                                return EXIT_FLAG;
                            }
                        }
                    }
                }
            }

            simulatorEngine.undoLastMove();

            eval = adjustMateFromChild(eval);
            boolean zeroing = isZeroingMove(move);

            if (eval > maxEval) {
                maxEval = eval;
                bestMoveAtThisNode = move;
                bestZeroing = zeroing;
            } else if (bestMoveAtThisNode != -1
                    && shouldUseTablebaseTieBreak(eval, maxEval)
                    && preferCandidateByTablebase(simulatorEngine, move, eval, zeroing, bestMoveAtThisNode, bestZeroing)) {
                maxEval = eval;
                bestMoveAtThisNode = move;
                bestZeroing = zeroing;
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

        double storedScore = toStoredMateScore(maxEval, plyFromRoot);
        if (maxEval <= alphaOriginal && shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(storedScore, depth, NodeType.UPPERBOUND, bestMoveAtThisNode), depth);
        } else if (maxEval >= betaOriginal && shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(storedScore, depth, NodeType.LOWERBOUND, bestMoveAtThisNode), depth);
        } else if (shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(storedScore, depth, NodeType.EXACT, bestMoveAtThisNode), depth);
        }

        return maxEval;
    }


    private double minimizer(Engine simulatorEngine, int depth, double alpha, double beta,
                             long boardHash, double alphaOriginal, double betaOriginal,
                             IntArrayList moves, long deadline, int prevMove, int plyFromRoot,
                             int extStreak) {

        long start = log.isDebugEnabled() ? System.nanoTime() : 0L;
        double minEval = Double.POSITIVE_INFINITY;
        int bestMoveAtThisNode = -1;
        boolean bestZeroing = false;

        final boolean inCheckAtNode = isSideInCheck(simulatorEngine, false);
        final Heuristics heuristics = threadHeuristics.get();
        final int[][] historyTable = heuristics.history;

        IntArrayList orderedMoves = sortMovesByEfficiency(moves, depth, boardHash, prevMove, simulatorEngine);
        final Int2IntOpenHashMap seeCache = seeCacheThreadLocal.get();
        seeCache.clear();
        final SearchPruningParameters.Snapshot pruning = searchPruningParameters;
        final int hmpMinIndex = pruning.hmpMinIndex();
        final int hmpHistoryMax = pruning.hmpHistoryMax();
        final int lmpBase = pruning.lmpBase();
        final int lmpPerDepth = pruning.lmpPerDepth();
        final int iidReduceDepth = pruning.iidReduceDepth();
        final int lmrProtectPlyMax = pruning.lmrProtectPlyMax();
        final int lmrProtectIndexMax = pruning.lmrProtectIndexMax();
        final int lmrCapGoodQuiet = pruning.lmrCapForGoodQuiet();
        final int maxCheckExtensionStreak = Math.max(0, pruning.maxCheckExtensionStreak());
        final int seePruneNearRootPly = Math.max(0, pruning.seePruneNearRootPly());

        int baseRemainingDepth = Math.max(0, depth - 1);
        boolean futilityEligible = !inCheckAtNode
                && plyFromRoot > 0
                && depth >= 1
                && depth <= futilityMaxDepth
                && Double.isFinite(beta);
        boolean futilityPossible = futilityEligible
                && ((baseRemainingDepth <= 1 && pruning.fpMarginDepth1() > 0)
                || (baseRemainingDepth == 2 && pruning.fpMarginDepth2() > 0));
        double staticEvalWhite = Double.NaN;
        if (futilityPossible) {
            staticEvalWhite = resolveScoreDifference(simulatorEngine.getGameState(), boardHash,
                    simulatorEngine.whitesTurn());
            if (!Double.isFinite(staticEvalWhite)) {
                futilityPossible = false;
            }
        }

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
                seeGain = simulatorEngine.see(move, 0);
                seeEvaluated = true;
                boolean nearRoot = plyFromRoot <= seePruneNearRootPly;
                boolean allowSeePrune = seeGain < 0 && !nearRoot;
                if (allowSeePrune) {
                    simulatorEngine.performMove(move);
                    boolean givesCheckTmp = isSideInCheck(simulatorEngine, true);
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
            long enemyKingBB = boardBefore.getWhiteKing();
            int enemyKingSquare = enemyKingBB != 0L ? Long.numberOfTrailingZeros(enemyKingBB) : -1;
            int enemyKingFile = enemyKingSquare >= 0 ? (enemyKingSquare & 7) : -1;
            long kingFileMask = enemyKingFile >= 0 ? FileMasks[enemyKingFile] : 0L;
            boolean touchesKingFile = enemyKingSquare >= 0 && (((from & 7) == enemyKingFile) || ((to & 7) == enemyKingFile));
            boolean affectsKingFilePawns = touchesKingFile && (movingPieceBits == 1 || capturedPieceBits == 1);
            int pawnsOnFileBefore = (enemyKingSquare >= 0 && affectsKingFilePawns) ? countPawnsOnFile(boardBefore, kingFileMask) : 0;

            boolean isTactical = isCapture || isPromotion;
            int lmpThreshold = lmpBase + depth * lmpPerDepth;
            if (!inCheckAtNode && !isTactical && depth <= lmpMaxDepth && index > lmpThreshold) {
                simulatorEngine.performMove(move);
                boolean givesCheckTmp = isSideInCheck(simulatorEngine, true);
                boolean attacksQueenTmp = attacksOpponentQueenNow(simulatorEngine, false);
                simulatorEngine.undoLastMove();
                if (!givesCheckTmp && !attacksQueenTmp) continue;
            }

            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();

            boolean givesCheck = isSideInCheck(simulatorEngine, true);
            boolean attacksQueen = attacksOpponentQueenNow(simulatorEngine, false);
            boolean attacksKingZone = attacksOpponentKingZone(simulatorEngine, false);
            boolean opensKingFile = openedFileTowardKing(simulatorEngine.getBitBoard(), kingFileMask, pawnsOnFileBefore, affectsKingFilePawns);

            if (futilityPossible && isQuiet && !givesCheck && !attacksQueen && !attacksKingZone
                    && !opensKingFile && !seeWinsMaterial) {
                int futilityMargin = futilityMarginForRemainingDepth(baseRemainingDepth);
                if (futilityMargin > 0 && staticEvalWhite - futilityMargin >= beta) {
                    simulatorEngine.undoLastMove();
                    continue;
                }
            }

            int nextDepth = depth - 1;
            boolean forcing = givesCheck || attacksQueen;
            boolean allowExtend = forcing && extStreak < maxCheckExtensionStreak;
            if (allowExtend) nextDepth++;
            int nextExtStreak = allowExtend ? extStreak + 1 : 0;

            double eval = Double.POSITIVE_INFINITY;
            TranspositionTableEntry entry = transpositionTable.get(newBoardHash);
            boolean ttExactHit = entry != null
                    && entry.nodeType == NodeType.EXACT
                    && entry.depth >= nextDepth;

            boolean usedTranspositionEval = false;
            if (ttExactHit) {
                eval = fromStoredMateScore(entry.score, plyFromRoot + 1);
                usedTranspositionEval = true;
            } else {
                if (!inCheckAtNode
                        && !isTactical
                        && !givesCheck
                        && !attacksQueen
                        && !attacksKingZone
                        && !opensKingFile
                        && iidReduceDepth > 0
                        && nextDepth - iidReduceDepth >= 1) {
                    double iidScore = alphaBeta(simulatorEngine, nextDepth - iidReduceDepth, alpha, beta,
                            true, deadline, move, plyFromRoot + 1, 0);
                    if (iidScore == EXIT_FLAG || positionChanged()) {
                        simulatorEngine.undoLastMove();
                        return EXIT_FLAG;
                    }
                    entry = transpositionTable.get(newBoardHash);
                    ttExactHit = entry != null
                            && entry.nodeType == NodeType.EXACT
                            && entry.depth >= nextDepth;
                    if (ttExactHit) {
                        eval = fromStoredMateScore(entry.score, plyFromRoot + 1);
                        usedTranspositionEval = true;
                    }
                }

                if (!usedTranspositionEval) {
                    boolean canReduce = !inCheckAtNode
                            && !isTactical
                            && !givesCheck
                            && !attacksQueen
                            && !attacksKingZone
                            && !opensKingFile
                            && !seeWinsMaterial
                            && nextDepth >= 2
                            && index > lmrProtectIndexMax;

                    if (plyFromRoot <= lmrProtectPlyMax) {
                        canReduce = false;
                    }

                    boolean usePvs = index > 0 && alpha != Double.NEGATIVE_INFINITY && beta != Double.POSITIVE_INFINITY;
                    double pAlpha = usePvs ? (beta - 1) : alpha;

                    int reduction = 0;
                    if (canReduce) {
                        reduction = lmrReduction(nextDepth, index, historyScore);
                        if (reduction > 0 && isQuiet && historyScore > 0 && lmrCapGoodQuiet >= 0) {
                            reduction = Math.min(reduction, lmrCapGoodQuiet);
                        }
                        if (reduction <= 0) canReduce = false;
                    }

                    if (canReduce) {
                        int reduced = Math.max(1, nextDepth - reduction);
                        eval = alphaBeta(simulatorEngine, reduced, pAlpha, beta, true, deadline, move, plyFromRoot + 1, nextExtStreak);
                        if (eval == EXIT_FLAG || positionChanged()) {
                            simulatorEngine.undoLastMove();
                            return EXIT_FLAG;
                        }

                        boolean promising = eval < beta;
                        if (promising) {
                            eval = alphaBeta(simulatorEngine, nextDepth, usePvs ? alpha : pAlpha, beta,
                                    true, deadline, move, plyFromRoot + 1, nextExtStreak);
                            if (eval == EXIT_FLAG || positionChanged()) {
                                simulatorEngine.undoLastMove();
                                return EXIT_FLAG;
                            }
                        }
                    } else {
                        eval = alphaBeta(simulatorEngine, nextDepth, pAlpha, beta, true, deadline, move, plyFromRoot + 1, nextExtStreak);
                        if (eval == EXIT_FLAG || positionChanged()) {
                            simulatorEngine.undoLastMove();
                            return EXIT_FLAG;
                        }

                        if (usePvs && eval > alpha && eval < beta) {
                            eval = alphaBeta(simulatorEngine, nextDepth, alpha, beta, true, deadline, move, plyFromRoot + 1, nextExtStreak);
                            if (eval == EXIT_FLAG || positionChanged()) {
                                simulatorEngine.undoLastMove();
                                return EXIT_FLAG;
                            }
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

            eval = adjustMateFromChild(eval);
            boolean zeroing = isZeroingMove(move);

            if (eval < minEval) {
                minEval = eval;
                bestMoveAtThisNode = move;
                bestZeroing = zeroing;
            } else if (bestMoveAtThisNode != -1
                    && shouldUseTablebaseTieBreak(eval, minEval)
                    && preferCandidateByTablebase(simulatorEngine, move, eval, zeroing, bestMoveAtThisNode, bestZeroing)) {
                minEval = eval;
                bestMoveAtThisNode = move;
                bestZeroing = zeroing;
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

        double storedScore = toStoredMateScore(minEval, plyFromRoot);
        if (minEval >= betaOriginal && shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(storedScore, depth, NodeType.LOWERBOUND, bestMoveAtThisNode), depth);
        } else if (minEval <= alphaOriginal && shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(storedScore, depth, NodeType.UPPERBOUND, bestMoveAtThisNode), depth);
        } else if (shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(storedScore, depth, NodeType.EXACT, bestMoveAtThisNode), depth);
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
        final Int2IntOpenHashMap seeCache = seeCacheThreadLocal.get();
        seeCache.clear();

        if (size == 0) {
            return moves;
        }

        final SortBuffers buffers = sortBuffers.get();
        final int[] moveBuffer = buffers.moveBuffer;
        final int[] scoreBuffer = buffers.scoreBuffer;
        final int[] orderedBuffer = buffers.orderedBuffer;
        final IntArrayList[] bucketIndexes = buffers.bucketIndexes;
        for (IntArrayList bucket : bucketIndexes) {
            bucket.clear();
        }

        final IntArrayList ttBucket = bucketIndexes[MoveBucket.TT.ordinal()];
        final IntArrayList promotionBucket = bucketIndexes[MoveBucket.PROMOTION.ordinal()];
        final IntArrayList captureGoodBucket = bucketIndexes[MoveBucket.CAPTURE_GOOD.ordinal()];
        final IntArrayList captureEqualBucket = bucketIndexes[MoveBucket.CAPTURE_EQUAL.ordinal()];
        final IntArrayList killer0Bucket = bucketIndexes[MoveBucket.KILLER0.ordinal()];
        final IntArrayList killer1Bucket = bucketIndexes[MoveBucket.KILLER1.ordinal()];
        final IntArrayList quietBucket = bucketIndexes[MoveBucket.QUIET.ordinal()];
        final IntArrayList captureBadBucket = bucketIndexes[MoveBucket.CAPTURE_BAD.ordinal()];

        final Heuristics heuristics = threadHeuristics.get();
        final int[][] killerMoves = heuristics.killers;
        final int[][] historyTable = heuristics.history;
        final int[][] counterMove = heuristics.counter;

        final int depthIndex = Math.max(0, Math.min(currentDepth, killerMoves.length - 1));

        final int promotionBonus = moveOrderingParameters.promotionBonus();
        final int killer0Bonus = moveOrderingParameters.killer0Bonus();
        final int killer1Bonus = moveOrderingParameters.killer1Bonus();
        final int killerMoveScore = moveOrderingParameters.killerMoveScore();
        final int captureMvvMultiplier = moveOrderingParameters.captureMvvMultiplier();
        final int captureSeeMultiplier = moveOrderingParameters.captureSeeMultiplier();
        final int promotionSeeMultiplier = moveOrderingParameters.promotionSeeMultiplier();
        final int castlingBonus = moveOrderingParameters.castlingBonus();
        final int captureSeeClamp = Math.max(0, moveOrderingParameters.captureSeeClamp());
        final int promotionSeeClamp = Math.max(0, moveOrderingParameters.promotionSeeClamp());
        final int maxScore = Math.max(1, moveOrderingParameters.maxScore());

        // Hash move (TT)
        TranspositionTableEntry ttEntry = transpositionTable.get(boardHash);
        final int ttMove = ttEntry != null ? ttEntry.bestMove : -1;

        // Pre-fetch killers for this depth
        final int k0 = killerMoves[depthIndex][0];
        final int k1 = killerMoves[depthIndex][1];

        final int prevFrom = (prevMove >= 0) ? (prevMove & 0x3F) : -1;
        final int prevTo = (prevMove >= 0) ? ((prevMove >>> 6) & 0x3F) : -1;
        final int cm = (prevFrom >= 0) ? counterMove[prevFrom][prevTo] : -1;
        final int counterMoveBonus = moveOrderingParameters.counterMoveBonus();

        for (int i = 0; i < size; i++) {
            final int moveInt = moves.getInt(i);

            final boolean isCapture = MoveHelper.isCapture(moveInt);
            final boolean isPromotion = MoveHelper.isPawnPromotionMove(moveInt);

            int seeValue = SEE_CACHE_MISS;
            boolean hasSee = false;
            if (isCapture) {
                seeValue = seeCache.get(moveInt);
                hasSee = seeValue != SEE_CACHE_MISS;
                if (!hasSee) {
                    seeValue = simulatorEngine.see(moveInt);
                    seeCache.put(moveInt, seeValue);
                    hasSee = true;
                }
            }

            int score;
            IntArrayList targetBucket;

            if (moveInt == ttMove) {
                score = maxScore; // max within bucket
                targetBucket = ttBucket;
            } else if (isPromotion) {
                int base = calculateMvvLvaScore(moveInt);
                int seeBonus = 0;
                if (hasSee) {
                    int cappedSee = promotionSeeClamp > 0
                            ? Math.max(-promotionSeeClamp, Math.min(promotionSeeClamp, seeValue))
                            : seeValue;
                    seeBonus = cappedSee * promotionSeeMultiplier;
                }
                score = base + promotionBonus + seeBonus;
                targetBucket = promotionBucket;
            } else if (isCapture) {
                final int mvvLva = calculateMvvLvaScore(moveInt);
                int cappedSee = captureSeeClamp > 0
                        ? Math.max(-captureSeeClamp, Math.min(captureSeeClamp, seeValue))
                        : seeValue;
                score = (mvvLva * captureMvvMultiplier) + (cappedSee * captureSeeMultiplier);
                if (score < 0) score = 0;
                if (seeValue > 0) {
                    targetBucket = captureGoodBucket;
                } else if (seeValue == 0) {
                    targetBucket = captureEqualBucket;
                } else {
                    targetBucket = captureBadBucket;
                }
            } else if (moveInt == k0) {
                score = killerMoveScore + killer0Bonus;
                targetBucket = killer0Bucket;
            } else if (moveInt == k1) {
                score = killerMoveScore + killer1Bonus;
                targetBucket = killer1Bucket;
            } else {
                final int from = moveInt & 0x3F;
                final int to = (moveInt >>> 6) & 0x3F;
                score = historyTable[from][to];
                if (moveInt == cm) score += counterMoveBonus;
                if (MoveHelper.isCastlingMove(moveInt)) {
                    score += castlingBonus;
                }
                targetBucket = quietBucket;
            }

            moveBuffer[i] = moveInt;
            int s = score;
            if (s < 0) {
                s = 0;
            } else if (s > maxScore) {
                s = maxScore;
            }
            scoreBuffer[i] = s;
            insertByScore(targetBucket, i, scoreBuffer, moveBuffer);
        }

        int outIndex = 0;
        for (MoveBucket bucket : moveBucketOrder) {
            outIndex = writeBucket(bucketIndexes[bucket.ordinal()], moveBuffer, orderedBuffer, outIndex);
        }

        MoveContainerUtils.overwriteFromBuffer(moves, orderedBuffer, size);
        return moves;
    }


    public double evaluateBoard(Engine simulatorEngine, boolean isWhitesTurn, long deadline) {
        if (simulatorEngine.getGameState().isInStateCheckMate()) {
            return -CHECKMATE;
        }

        long boardStateHash = simulatorEngine.getBoardStateHash();

        // Treat both terminal draws and insufficient-material as draw for evaluation
        if (simulatorEngine.getGameState().isDrawForUIOrEval()) {
            double scoreDiff = resolveScoreDifference(simulatorEngine.getGameState(), boardStateHash,
                    simulatorEngine.whitesTurn());
            if ((isWhitesTurn && scoreDiff > 0) || (!isWhitesTurn && scoreDiff < 0)) {
                return DRAW - drawBias;
            } else if ((isWhitesTurn && scoreDiff < 0) || (!isWhitesTurn && scoreDiff > 0)) {
                return DRAW + drawBias;
            }
            return DRAW;
        }

        double alpha = Double.NEGATIVE_INFINITY;
        double beta = Double.POSITIVE_INFINITY;

        CaptureTranspositionTableEntry entry = captureTranspositionTable.get(boardStateHash);
        if (entry != null && entry.isWhite() == isWhitesTurn) {
            return entry.getScore();
        }

        if (!isSideInCheck(simulatorEngine, isWhitesTurn)
                && !hasImmediateTacticalMoves(simulatorEngine)) {
            if (abortRequested(deadline)) {
                return EXIT_FLAG;
            }
            double quietScore = evaluateStaticPosition(
                    simulatorEngine.getGameState(), boardStateHash, isWhitesTurn, 0
            );
            captureTranspositionTable.put(
                    boardStateHash, new CaptureTranspositionTableEntry(quietScore, isWhitesTurn), 0
            );
            return quietScore;
        }

        double score = quiescenceSearch(simulatorEngine, isWhitesTurn, alpha, beta, deadline, 0);
        if (score != EXIT_FLAG) {
            captureTranspositionTable.put(boardStateHash, new CaptureTranspositionTableEntry(score, isWhitesTurn), 0);
        }
        return score;
    }

    private double quiescenceSearch(Engine simulatorEngine,
                                    boolean isWhitesTurn,
                                    double alpha,
                                    double beta,
                                    long deadline,
                                    int depth) {
        // Early stop
        if (abortRequested(deadline)) {
            return AI.EXIT_FLAG;
        }

        // Never expand terminal nodes in qsearch: just evaluate
        if (simulatorEngine.getGameState().isTerminal()) {
            long boardHash = simulatorEngine.getBoardStateHash();
            return evaluateStaticPosition(simulatorEngine.getGameState(), boardHash, isWhitesTurn, depth);
        }

        final boolean inCheck = isSideInCheck(simulatorEngine, isWhitesTurn);

        long boardHash = simulatorEngine.getBoardStateHash();
        double standPat = evaluateStaticPosition(simulatorEngine.getGameState(), boardHash, isWhitesTurn, depth);

        double alphaBeforeStandPat = alpha;

        if (!inCheck) {
            // Fail-hard beta cut on stand-pat
            if (standPat >= beta) {
                return beta;
            }
            // Raise alpha to stand-pat
            if (standPat > alpha) {
                alpha = standPat;
            }
        }

        // Move gen: evasions if in check; else captures/promotions (and optional checking moves if you add them)
        IntArrayList moves = inCheck
                ? simulatorEngine.getAllLegalMoves()                // all evasions
                : getPossibleCapturesOrPromotions(simulatorEngine);  // tactical moves only

        if (!inCheck) {
            if (moves.isEmpty()) {
                return alpha;
            }

            double optimisticSwing = estimateMaxTacticalSwing(moves);
            // Delta (futility-like) pruning in qsearch: compare against original alpha window
            if (standPat + optimisticSwing <= alphaBeforeStandPat) {
                return alpha;
            }
        }

        // Order moves (MVV-LVA, history, hash-move etc.)
        IntArrayList ordered = sortMovesByEfficiency(
                moves, 0, simulatorEngine.getBoardStateHash(), -1, simulatorEngine
        );

        for (int i = 0; i < ordered.size(); i++) {
            int m = ordered.getInt(i);

            boolean isCapture   = MoveHelper.isCapture(m);
            boolean isPromotion = MoveHelper.isPawnPromotionMove(m);
            boolean isQuiet     = !isCapture && !isPromotion;

            // In qsearch (not in check), we normally do NOT consider quiet moves.
            // If you want to allow some checks, you can selectively allow quiet check moves.
            if (!inCheck && isQuiet) {
                continue;
            }

            // SEE pruning: drop clearly losing captures (keep promotions).
            // Optionally keep losing captures that give check (aggressive but sometimes good).
            if (!inCheck && isCapture && !isPromotion) {
                int see = simulatorEngine.see(m, 0);
                if (see < 0) {
                    // Cheap “gives check?” probe; only spend the make/undo if node didn't turn terminal.
                    if (!simulatorEngine.getGameState().isTerminal()) {
                        simulatorEngine.performMove(m);
                        boolean givesCheck = isSideInCheck(simulatorEngine, !isWhitesTurn);
                        simulatorEngine.undoLastMove();
                        if (!givesCheck) {
                            continue; // prune losing capture that doesn't give check
                        }
                    } else {
                        continue;
                    }
                }
            }

            // Safety guard before making the move (rare but keeps invariants)
            if (simulatorEngine.getGameState().isTerminal()) {
                return standPat; // nothing more to do from here
            }

            simulatorEngine.performMove(m);
            double child = quiescenceSearch(simulatorEngine, !isWhitesTurn, -beta, -alpha, deadline, depth + 1);
            simulatorEngine.undoLastMove();

            if (child == EXIT_FLAG) {
                return EXIT_FLAG;
            }

            double score = -child;
            score = adjustMateFromChild(score);

            // Fail-hard beta cutoff
            if (score >= beta) {
                return beta;
            }
            // Raise alpha
            if (score > alpha) {
                alpha = score;
            }
        }

        return alpha;
    }


    private double evaluateStaticPosition(GameState gameState, long boardHash, boolean isWhitesTurn, int depthOrPly) {
        if (gameState.isInStateCheckMate()) {
            return -(CHECKMATE - depthOrPly);
        }
        EvaluationContext context = gameState.getScore().getEvaluationContext();
        boolean whiteToMove = context != null && context.isWhiteToMove();
        Optional<TablebaseResult> tablebase = gameState.getLastTablebaseResult();
        if (tablebase.isPresent() && isExactWdl(tablebase.get())) {
            double whitePerspective = Score.tablebaseToEvaluation(tablebase.get(), whiteToMove,
                    gameState.getHalfmoveClock());
            return isWhitesTurn ? whitePerspective : -whitePerspective;
        }
        if (gameState.isDrawForUIOrEval()) { // <-- include insufficient material for eval/UI
            if (log.isDebugEnabled()) log.debug("DRAW");
            double scoreDiff = resolveScoreDifference(gameState, boardHash, whiteToMove);
            if ((isWhitesTurn && scoreDiff > 0) || (!isWhitesTurn && scoreDiff < 0)) {
                return DRAW - drawBias;
            } else if ((isWhitesTurn && scoreDiff < 0) || (!isWhitesTurn && scoreDiff > 0)) {
                return DRAW + drawBias;
            }
            return DRAW;
        }
        double scoreDifference = resolveScoreDifference(gameState, boardHash, whiteToMove);
        return isWhitesTurn ? scoreDifference : -scoreDifference;
    }

    private IntArrayList getPossibleCapturesOrPromotions(Engine simulatorEngine) {
        IntArrayList allLegalMoves = simulatorEngine.getAllLegalMoves();
        return MoveContainerUtils.filterCapturesAndPromotions(allLegalMoves);
    }

    private boolean hasImmediateTacticalMoves(Engine simulatorEngine) {
        return simulatorEngine.hasAnyCaptureOrPromotion();
    }

    private double estimateMaxTacticalSwing(IntArrayList moves) {
        double bestSwing = 0.0;
        final double pawnValue = Score.getPieceValue(1);

        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getInt(i);
            double swing = 0.0;

            if (MoveHelper.isCapture(move)) {
                int capturedBits = MoveHelper.deriveCapturedPieceTypeBits(move);
                double captureValue;
                if (capturedBits != 0) {
                    captureValue = Score.getPieceValue(capturedBits);
                } else if (MoveHelper.isEnPassantMove(move)) {
                    captureValue = pawnValue;
                } else {
                    captureValue = 0.0;
                }
                swing += captureValue;
            }

            if (MoveHelper.isPawnPromotionMove(move)) {
                int promotionBits = MoveHelper.derivePromotionPieceTypeBits(move);
                if (promotionBits != 0) {
                    double promotionDelta = Score.getPieceValue(promotionBits) - pawnValue;
                    if (promotionDelta > 0) {
                        swing += promotionDelta;
                    }
                }
            }

            if (swing > bestSwing) {
                bestSwing = swing;
                if (bestSwing >= quiescenceMaxDeltaPawn) {
                    return quiescenceMaxDeltaPawn;
                }
            }
        }

        return Math.min(bestSwing, quiescenceMaxDeltaPawn);
    }

    private boolean isMateValue(double score) {
        return Double.isFinite(score) && Math.abs(score) >= (CHECKMATE - MATE_SCORE_MARGIN);
    }

    private double adjustMateFromChild(double score) {
        if (!preferFastMate || !Double.isFinite(score)) {
            return score;
        }
        if (score >= CHECKMATE - MATE_SCORE_MARGIN) {
            return score - 1;
        }
        if (score <= -CHECKMATE + MATE_SCORE_MARGIN) {
            return score + 1;
        }
        return score;
    }

    private double toStoredMateScore(double score, int plyFromRoot) {
        if (!preferFastMate || !Double.isFinite(score)) {
            return score;
        }
        if (score >= CHECKMATE - MATE_SCORE_MARGIN) {
            return score + plyFromRoot;
        }
        if (score <= -CHECKMATE + MATE_SCORE_MARGIN) {
            return score - plyFromRoot;
        }
        return score;
    }

    private double fromStoredMateScore(double score, int plyFromRoot) {
        if (!preferFastMate || !Double.isFinite(score)) {
            return score;
        }
        if (score >= CHECKMATE - MATE_SCORE_MARGIN) {
            return score - plyFromRoot;
        }
        if (score <= -CHECKMATE + MATE_SCORE_MARGIN) {
            return score + plyFromRoot;
        }
        return score;
    }

    private boolean isZeroingMove(int move) {
        if (move < 0) {
            return false;
        }
        if (MoveHelper.isCapture(move)) {
            return true;
        }
        int pieceBits = MoveHelper.derivePieceTypeBits(move);
        return pieceBits == 1;
    }

    private boolean shouldUseTablebaseTieBreak(double candidateEval, double bestEval) {
        if (!tbTieBreak) {
            return false;
        }
        if (!Double.isFinite(candidateEval) || !Double.isFinite(bestEval)) {
            return false;
        }
        if (Math.abs(candidateEval - bestEval) > TB_TIE_EPSILON) {
            return false;
        }
        if (isMateValue(candidateEval) || isMateValue(bestEval)) {
            return false;
        }
        double candidateSign = Math.signum(candidateEval);
        double bestSign = Math.signum(bestEval);
        if (candidateSign == 0.0 || bestSign == 0.0) {
            return false;
        }
        return candidateSign == bestSign;
    }

    private TablebaseInfo probeMoveTablebase(Engine engine, int move) {
        if (!tbTieBreak || tablebaseService == null || move < 0) {
            return null;
        }
        engine.performMove(move);
        try {
            TablebaseResult result = engine.getGameState().getLastTablebaseResult().orElse(null);
            if (!isExactWdl(result)) {
                Optional<SyzygyProbeResult> probe = tablebaseService.probe(engine.getBitBoard());
                if (probe.isEmpty()) {
                    return null;
                }
                result = TablebaseResult.from(probe.get());
                if (!isExactWdl(result)) {
                    return null;
                }
                engine.getGameState().setLastTablebaseResult(result);
            }
            int dtz = result.dtz().isPresent() ? Math.abs(result.dtz().getAsInt()) : -1;
            int dtm = result.dtm().isPresent() ? Math.abs(result.dtm().getAsInt()) : -1;
            boolean childIsWhite = engine.whitesTurn();
            int wdlScore = result.wdl().score();
            int whiteWdlSign = childIsWhite ? wdlScore : -wdlScore;
            return new TablebaseInfo(dtz, dtm, whiteWdlSign);
        } finally {
            engine.undoLastMove();
        }
    }

    private boolean preferCandidateByTablebase(Engine engine,
                                               int candidateMove,
                                               double candidateEval,
                                               boolean candidateZeroing,
                                               int bestMove,
                                               boolean bestZeroing) {
        TablebaseInfo candidateInfo = probeMoveTablebase(engine, candidateMove);
        TablebaseInfo bestInfo = probeMoveTablebase(engine, bestMove);
        if (candidateInfo == null && bestInfo == null) {
            return false;
        }

        int candidateSign = candidateInfo != null ? Integer.signum(candidateInfo.whiteWdlSign()) : 0;
        int bestSign = bestInfo != null ? Integer.signum(bestInfo.whiteWdlSign()) : 0;

        if (candidateSign > 0 && bestSign > 0) {
            int candidateDtz = candidateInfo.hasDtz() ? candidateInfo.dtz() : Integer.MAX_VALUE;
            int bestDtz = bestInfo.hasDtz() ? bestInfo.dtz() : Integer.MAX_VALUE;
            if (candidateDtz < bestDtz) {
                return true;
            }
            if (candidateDtz > bestDtz) {
                return false;
            }
            if (candidateZeroing != bestZeroing) {
                return candidateZeroing;
            }
            return false;
        }

        if (candidateSign < 0 && bestSign < 0) {
            int candidateDtz = candidateInfo.hasDtz() ? candidateInfo.dtz() : -1;
            int bestDtz = bestInfo.hasDtz() ? bestInfo.dtz() : -1;
            if (candidateDtz > bestDtz) {
                return true;
            }
            if (candidateDtz < bestDtz) {
                return false;
            }
            if (candidateZeroing != bestZeroing) {
                return !candidateZeroing;
            }
            return false;
        }

        if (candidateInfo != null && bestInfo == null) {
            if (candidateSign > 0 && candidateInfo.hasDtz()) {
                return true;
            }
            if (candidateSign > 0 && candidateZeroing != bestZeroing) {
                return candidateZeroing;
            }
            return false;
        }

        if (candidateInfo == null) {
            if (bestSign > 0 && bestInfo.hasDtz()) {
                return false;
            }
            if (bestSign < 0 && bestInfo.hasDtz()) {
                return false;
            }
            if (bestSign > 0 && candidateZeroing != bestZeroing) {
                return candidateZeroing;
            }
            if (bestSign < 0 && candidateZeroing != bestZeroing) {
                return !candidateZeroing;
            }
        }

        return false;
    }

    /**
     * Checks whether the board hash has changed since the current search task started.
     *
     * <p>The hashes live in {@code volatile} fields, so this is a lock-free visibility check that
     * avoids serialising worker threads on a monitor. The volatile semantics already provide the
     * required cross-thread visibility.</p>
     */
    private boolean positionChanged() {
        return currentBoardState != UNINITIALIZED_BOARD_STATE
                && beforeCalculationBoardState != UNINITIALIZED_BOARD_STATE
                && currentBoardState != beforeCalculationBoardState;
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
        if (depth <= 0) {
            return;
        }
        long base = (long) depth * (long) depth;
        double scaled = moveOrderingHistoryScale * base;
        long deltaLong = Math.max(1L, Math.round(scaled));
        int delta = deltaLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) deltaLong;
        threadHeuristics.get().addHistory(move, delta);
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

    private static MoveBucket[] buildMoveBucketOrder(MoveOrderingParameters.Snapshot parameters) {
        MoveBucket[] order = MoveBucket.values().clone();
        Arrays.sort(order, Comparator
                .comparingInt((MoveBucket bucket) -> resolveCategoryWeight(bucket, parameters))
                .reversed()
                .thenComparingInt(MoveBucket::ordinal));
        return order;
    }

    private static int resolveCategoryWeight(MoveBucket bucket, MoveOrderingParameters.Snapshot parameters) {
        return switch (bucket) {
            case TT -> parameters.categoryTt();
            case PROMOTION -> parameters.categoryPromotion();
            case CAPTURE_GOOD -> parameters.categoryCaptureGood();
            case CAPTURE_EQUAL -> parameters.categoryCaptureEqual();
            case KILLER0 -> parameters.categoryKiller0();
            case KILLER1 -> parameters.categoryKiller1();
            case QUIET -> parameters.categoryQuiet();
            case CAPTURE_BAD -> parameters.categoryCaptureBad();
        };
    }

    private static void insertByScore(IntArrayList bucket, int moveIndex, int[] scoreBuffer, int[] moveBuffer) {
        int score = scoreBuffer[moveIndex];
        int move = moveBuffer[moveIndex];
        int insertPosition = bucket.size();
        while (insertPosition > 0) {
            int existingIndex = bucket.getInt(insertPosition - 1);
            int existingScore = scoreBuffer[existingIndex];
            if (score > existingScore) {
                insertPosition--;
                continue;
            }
            if (score == existingScore && move > moveBuffer[existingIndex]) {
                insertPosition--;
                continue;
            }
            break;
        }
        bucket.add(insertPosition, moveIndex);
    }

    private static int writeBucket(IntArrayList bucket, int[] sourceMoves, int[] target, int startIndex) {
        for (int i = 0, size = bucket.size(); i < size; i++) {
            target[startIndex++] = sourceMoves[bucket.getInt(i)];
        }
        return startIndex;
    }

    private int calculateMvvLvaScore(int move) {
        if (!MoveHelper.isCapture(move)) {
            return 0; // Not a capture move
        }
        int victimValue = Score.getPieceValue(MoveHelper.deriveCapturedPieceTypeBits(move));
        int attackerValue = Score.getPieceValue(MoveHelper.derivePieceTypeBits(move));
        return victimValue - attackerValue;
    }

    private static final class WorkerInstrumentation {
        private LongAdder[] idleNanos;
        private LongAdder[] activeNanos;
        private LongAdder[] jobCounters;

        WorkerInstrumentation(int threads) {
            ensureCapacityInternal(Math.max(1, threads));
        }

        synchronized void ensureCapacity(int threads) {
            ensureCapacityInternal(Math.max(1, threads));
        }

        void recordIdle(int workerIndex, long nanos) {
            if (idleNanos == null || workerIndex < 0 || workerIndex >= idleNanos.length || nanos <= 0L) {
                return;
            }
            idleNanos[workerIndex].add(nanos);
        }

        void recordActive(int workerIndex, long nanos) {
            if (activeNanos == null || workerIndex < 0 || workerIndex >= activeNanos.length || nanos <= 0L) {
                return;
            }
            activeNanos[workerIndex].add(nanos);
        }

        void incrementJobs(int workerIndex) {
            if (jobCounters == null || workerIndex < 0 || workerIndex >= jobCounters.length) {
                return;
            }
            jobCounters[workerIndex].increment();
        }

        synchronized String buildSummaryAndReset(int workers) {
            if (workers <= 0) {
                resetAll();
                return "";
            }
            ensureCapacityInternal(Math.max(1, workers));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < workers; i++) {
                long active = activeNanos[i].sum();
                long idle = idleNanos[i].sum();
                long jobs = jobCounters[i].sum();
                long total = active + idle;
                double utilisation = total > 0 ? (active * 100.0) / total : 0.0;
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append("worker=").append(i)
                        .append(" jobs=").append(jobs)
                        .append(" activeMs=").append(TimeUnit.NANOSECONDS.toMillis(active))
                        .append(" idleMs=").append(TimeUnit.NANOSECONDS.toMillis(idle))
                        .append(" util=").append(String.format(Locale.ROOT, "%.1f%%", utilisation));
            }
            resetAll();
            return sb.toString();
        }

        private void resetAll() {
            if (idleNanos != null) {
                for (LongAdder adder : idleNanos) {
                    adder.reset();
                }
            }
            if (activeNanos != null) {
                for (LongAdder adder : activeNanos) {
                    adder.reset();
                }
            }
            if (jobCounters != null) {
                for (LongAdder adder : jobCounters) {
                    adder.reset();
                }
            }
        }

        private void ensureCapacityInternal(int threads) {
            if (idleNanos != null && idleNanos.length >= threads) {
                return;
            }
            idleNanos = grow(idleNanos, threads);
            activeNanos = grow(activeNanos, threads);
            jobCounters = grow(jobCounters, threads);
        }

        private static LongAdder[] grow(LongAdder[] original, int newLength) {
            LongAdder[] expanded = new LongAdder[newLength];
            int existing = original != null ? original.length : 0;
            for (int i = 0; i < newLength; i++) {
                if (i < existing && original[i] != null) {
                    expanded[i] = original[i];
                } else {
                    expanded[i] = new LongAdder();
                }
            }
            return expanded;
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

        private static void updateMax(AtomicLong target, long value) {
            target.accumulateAndGet(value, Math::max);
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

        record Snapshot(int[][] killers, int[][] history, int[][] counter) {
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

        void addHistory(int move, int delta) {
            if (move == -1 || MoveHelper.isCapture(move) || delta <= 0) {
                return;
            }
            int from = move & 0x3F;
            int to = (move >>> 6) & 0x3F;
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

        boolean hasPendingUpdates() {
            return killerDirtyCount > 0 || historyDirtyCount > 0 || counterDirtyCount > 0;
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
            for (int j : row) {
                if (j == move) {
                    return;
                }
            }
            for (int i = row.length - 1; i > 0; i--) {
                row[i] = row[i - 1];
            }
            row[0] = move;
        }

        void decayHistory(int divisor) {
            if (divisor <= 1) {
                return;
            }
            for (int f = 0; f < BOARD_SQUARES; f++) {
                for (int t = 0; t < BOARD_SQUARES; t++) {
                    history[f][t] /= divisor;
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

    }
}
