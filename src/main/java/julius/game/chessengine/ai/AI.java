package julius.game.chessengine.ai;

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
import julius.game.chessengine.search.SearchPruning;
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

import java.util.concurrent.atomic.*;
import java.util.function.IntFunction;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.StampedLock;

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

    // ---- Extracted sub-components ----
    private final TablebaseProber tablebaseProber;
    private final TranspositionTableManager ttManager;
    private final HeuristicsManager heuristicsManager;
    private final QuiescenceSearcher quiescenceSearcher;

    /**
     * Number of threads used for searching. Defaults to single-threaded search but
     * can be adjusted at runtime via the UCI "Threads" option.
     */
    @Getter
    private volatile int searchThreads;

    // number of Lazy SMP workers (≥1)
    @Getter
    private int lazySmpThreads;

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
    private int hashSizeMb;

    // TT entry sizes and capacity limits are managed by TranspositionTableManager.
    public static final int MIN_HASH_SIZE_MB = TranspositionTableManager.MIN_HASH_SIZE_MB;
    public static final int MAX_HASH_SIZE_MB = TranspositionTableManager.MAX_HASH_SIZE_MB;

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

    private final MoveOrderer moveOrderer;
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

    /**
     * Per-thread heuristic state used during move ordering. The tables are
     * initialised from {@link #globalHeuristics} at the beginning of each
     * iterative-deepening iteration, updated locally during the search and
     * merged back afterwards.
     */
    private final ThreadLocal<Heuristics> threadHeuristics;

    // Lock state is managed by HeuristicsManager

    // Move ordering buffers and SEE cache are managed by MoveOrderer.

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

    // Tablebase records are now in TablebaseProber

    private static final int LMR_MAX_DEPTH = 64;
    private static final int LMR_MAX_MOVES = MAX_MOVE_LIST_SIZE;
    private static final double MATE_SCORE_MARGIN = 2048.0;
    private static final double TB_TIE_EPSILON = 0.01;
    private static final double TABLEBASE_CLAMP = 2000.0 / 100.0;

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
        this.moveOrderer = new MoveOrderer(this.moveOrderingParameters);
        this.moveBucketOrder = moveOrderer.getBucketOrder();
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
        this.globalHeuristics = new Heuristics(maxDepth);
        this.threadHeuristics = ThreadLocal.withInitial(() -> new Heuristics(maxDepth));

        // Initialise extracted sub-components
        this.tablebaseProber = new TablebaseProber(tablebaseService);
        this.ttManager = new TranspositionTableManager(this.hashSizeMb, ttMainWeight, ttCaptureWeight);
        this.heuristicsManager = new HeuristicsManager(maxDepth);
        this.quiescenceSearcher = new QuiescenceSearcher(
                drawBias, quiescenceMaxDeltaPawn, tablebaseProber, this::abortRequested);

        rebuildTranspositionTables();

        this.searchPool = createSearchPool();

        this.mainEngine.setOnPositionChanged(_ -> updateBoardStateHash());
    }

    private ExecutorService createSearchPool() {
        if (searchThreads <= 1) {
            return null;
        }
        final AtomicInteger tid = new AtomicInteger(1);
        return Executors.newFixedThreadPool(searchThreads, r -> {
            Thread t = new Thread(r, "AI-Search-" + tid.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    private long acquireWriteLock() {
        return heuristicsManager.acquireWriteLock();
    }

    private long acquireReadLock() {
        return heuristicsManager.acquireReadLock();
    }

    private void releaseWriteLock(long stamp) {
        heuristicsManager.releaseWriteLock(stamp);
    }

    private void releaseReadLock(long stamp) {
        heuristicsManager.releaseReadLock(stamp);
    }

    private Heuristics.Snapshot captureHeuristicsSnapshot(int requiredDepth) {
        return heuristicsManager.captureSnapshot(requiredDepth);
    }

    private void rebuildTranspositionTables() {
        boolean concurrent = Math.max(searchThreads, lazySmpThreads) > 1;
        ttManager.rebuild(concurrent);
        this.transpositionTable = ttManager.getMainTable();
        this.captureTranspositionTable = ttManager.getCaptureTable();
        this.transpositionTableCapacity = ttManager.getMainTableCapacity();
        this.captureTranspositionTableCapacity = ttManager.getCaptureTableCapacity();
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
        boolean lastIterTablebaseExact = false;
        Heuristics heuristics = threadHeuristics.get();
        AspirationController aspirationController = new AspirationController(aspirationParameters);

        for (int currentDepth = 1; currentDepth <= maxDepth; currentDepth++) {
            if (shouldStopCalculating(task)) break;

            boolean firstAtDepth = task.beginIteration(currentDepth);
            prepareIterationState(task, heuristics, currentDepth, firstAtDepth);

            RootSearchResult result = null;
            boolean usedFullWindow = false;
            boolean attemptedAspiration = false;

            if (lastIterScore != null && currentDepth >= 3 && !lastIterTablebaseExact) {
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
                lastIterTablebaseExact = false;
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
            lastIterTablebaseExact = sealed.isTablebaseExact();
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
        quiescenceSearcher.clearCache();
    }

    private double resolveScoreDifference(GameState gameState, long boardHash, boolean whiteToMove) {
        return quiescenceSearcher.resolveScoreDifference(gameState, boardHash, whiteToMove);
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
                return new MoveAndScore(currentBestMove, task.getBest().score, task.getBest().tablebaseExact);
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

    public void requestStop() {
        SearchTask task = activeSearch.get();
        requestStop(task);
    }

    private void requestStop(SearchTask task) {
        if (task != null) {
            task.requestStop();
        }

        keepCalculating = false;
        calculationRequests.clear();
    }

    public void stopCalculation() {
        SearchTask task = activeSearch.get();
        requestStop(task);

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

        promoteTablebaseMove(orderedMoves, simulatorEngine);

        // TB fast path at root → hit.score() is WHITE-perspective
        Optional<TablebaseHit> rootTablebase = resolveTablebaseHit(simulatorEngine, isWhitesTurn);
        if (rootTablebase.isPresent()) {
            TablebaseHit hit = rootTablebase.get();
            int candidateMove = hit.bestMove();
            if (candidateMove >= 0 && MoveContainerUtils.contains(orderedMoves, candidateMove)) {
                return RootSearchResult.completed(createCandidate(candidateMove, hit.score(), true));
            }
        }

        int baseFanout = Math.min(rootParallelLimit, Math.max(0, orderedMoves.size() - 1));
        ExecutorService pool = this.searchPool;
        int helperCapacity = 0;
        if (pool instanceof ThreadPoolExecutor executor) helperCapacity = executor.getMaximumPoolSize();
        else if (searchThreads > 0) helperCapacity = searchThreads;

        if (helperCapacity <= 0 && baseFanout > 0) {
            return getBestMove(simulatorEngine, isWhitesTurn, depth, deadline, alpha, beta, rng);
        }

        int helperFanoutCap = helperCapacity > 0
                ? Math.max(1, (int) Math.round(helperCapacity * rootFanoutRatio))
                : baseFanout;

        final int fanout = helperCapacity > 0 ? Math.min(baseFanout, helperFanoutCap) : baseFanout;
        final boolean logFanout = rootFanoutStatsEnabled && log.isInfoEnabled();

        maybeRotateRootMoves(orderedMoves, rng);

        int firstMove = orderedMoves.getInt(0);
        int bestMove;
        double bestScore;

        boolean aborted = false;
        boolean bestFromTablebase = false;

        if (abortRequested(deadline)) {
            return RootSearchResult.aborted(null);
        }

        RootMoveEvaluation firstEvaluation = evaluateRootMove(simulatorEngine, isWhitesTurn, depth, deadline,
                alpha, beta, firstMove, true);
        if (firstEvaluation.aborted()) {
            return RootSearchResult.aborted(null);
        }

        bestMove = firstMove;
        bestScore = firstEvaluation.score();
        bestFromTablebase = firstEvaluation.tablebaseExact();

        if (logFanout) {
            log.info("Root fanout diag start: task={}, depth={}, legalMoves={}, helperCapacity={}, baseFanout={}, fanout={}, firstMove={}",
                    task.getId(), depth, orderedMoves.size(), helperCapacity, baseFanout, fanout,
                    Move.convertIntToMove(firstMove));
        }

        if (isWhitesTurn) alpha = Math.max(alpha, bestScore);
        else beta = Math.min(beta, bestScore);
        if (alpha >= beta) {
            MoveAndScore candidate = createCandidate(bestMove, bestScore, bestFromTablebase);
            return RootSearchResult.completed(candidate);
        }

        final CompletionService<MoveAndScore> ecs = new ExecutorCompletionService<>(searchPool);
        final List<Future<MoveAndScore>> futures = new ArrayList<>();

        final AtomicReference<RootSearchState> stateRef = new AtomicReference<>(
                new RootSearchState(alpha, beta, new MoveAndScore(bestMove, bestScore, bestFromTablebase))
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
                    boolean workerTablebaseExact = false;
                    if (workerEngine.getGameState().isInStateCheckMate()) {
                        probe = isWhitesTurn ? (CHECKMATE - 1) : -(CHECKMATE - 1);
                    } else if (workerEngine.getGameState().isTerminal()) {
                        probe = evaluateStaticPosition(workerEngine.getGameState(), workerHash, !isWhitesTurn, depth);
                        if (isWhitesTurn) probe = -probe;
                    } else {
                        Optional<TablebaseResult> workerTablebase = resolveExactTablebaseResult(workerEngine);
                        if (workerTablebase.isPresent()) {
                            workerTablebaseExact = true;
                            probe = evaluateStaticPosition(workerEngine.getGameState(), workerHash, !isWhitesTurn, depth);
                            if (isWhitesTurn) probe = -probe;
                        } else {
                            probe = alphaBeta(workerEngine, depth - 1, pAlpha, pBeta, !isWhitesTurn, deadline, moveInt, 1, 0);
                            if (probe == EXIT_FLAG || abortRequested(deadline)) return null;
                        }
                    }

                    boolean needsFull = !workerTablebaseExact
                            && (isWhitesTurn ? (probe > snapshot.alpha()) : (probe < snapshot.beta()));
                    double finalScore = probe;
                    boolean finalTablebaseExact = workerTablebaseExact;

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
                                    updateRootState(stateRef, isWhitesTurn, moveInt, full, false, stopRef);
                                }
                            }
                        } finally {
                            fullResLock.unlock();
                        }
                    } else {
                        updateRootState(stateRef, isWhitesTurn, moveInt, finalScore, finalTablebaseExact, stopRef);
                    }

                    return new MoveAndScore(moveInt, finalScore, finalTablebaseExact);
                } finally {
                    evalCache.clear();
                    releaseWorkerSimulation(workerEngine, task.getRootSnapshot());
                    if (helperHeuristics.hasUpdates()) mergeThreadHeuristics(helperHeuristics);
                    else helperHeuristics.resetUpdates();
                }
            } finally {
                if (previousTask != null) threadSearchTask.set(previousTask);
                else threadSearchTask.remove();
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
                            bestFromTablebase = best.tablebaseExact;
                        }
                        completedParallel++;
                    }
                } catch (ExecutionException ex) {
                    log.warn("Parallel root worker failed", ex.getCause());
                }

                if (stopRef.get()) continue;

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
            bestFromTablebase = bestCandidate.tablebaseExact;
        }

        if (!stopRef.get()) {
            for (int i = nextMoveIndex; i < orderedMoves.size(); i++) {
                if (abortRequested(deadline)) {
                    aborted = true;
                    break;
                }

                int moveInt = orderedMoves.getInt(i);
                sequentialProcessed++;
                RootMoveEvaluation evaluation = evaluateRootMove(simulatorEngine, isWhitesTurn, depth, deadline,
                        alpha, beta, moveInt, false);
                if (evaluation.aborted()) {
                    aborted = true;
                    break;
                }

                double score = evaluation.score();
                boolean moveTablebaseExact = evaluation.tablebaseExact();

                if (isBetterScore(isWhitesTurn, score, bestScore)) {
                    bestScore = score;
                    bestMove = moveInt;
                    bestFromTablebase = moveTablebaseExact;
                }

                if (isWhitesTurn) alpha = Math.max(alpha, score);
                else beta = Math.min(beta, score);

                if (alpha >= beta) {
                    stopRef.set(true);
                    break;
                }
            }
        }

        if (logFanout) {
            int remainingCandidates = Math.max(0, (orderedMoves.size() - 1) - submittedParallel - sequentialProcessed);
            log.info("Root fanout diag end: task={}, depth={}, parallelSubmitted={}, parallelCompleted={}, sequentialFallback={}, remainingCandidates={}, aborted={}",
                    task.getId(), depth, submittedParallel, completedParallel, sequentialProcessed, remainingCandidates, aborted);
        }

        MoveAndScore updatedBest = createCandidate(bestMove, bestScore, bestFromTablebase);
        if (updatedBest != null) stateRef.set(new RootSearchState(alpha, beta, updatedBest));

        MoveAndScore candidate = bestMove != -1 ? createCandidate(bestMove, bestScore, bestFromTablebase) : null;
        if (abortRequested(deadline)) aborted = true;
        return aborted ? RootSearchResult.aborted(candidate) : RootSearchResult.completed(candidate);
    }


    private void updateRootState(AtomicReference<RootSearchState> stateRef,
                                 boolean isWhiteToMove,
                                 int move,
                                 double score,
                                 boolean tablebaseExact,
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
                if (currentBest == null || currentBest.move != move || Double.compare(currentBest.score, score) != 0
                        || currentBest.tablebaseExact != tablebaseExact) {
                    nextBest = new MoveAndScore(move, score, tablebaseExact);
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
            if (simulation.getGameState().isTerminal()) return false;
            IntArrayList legal = simulation.getAllLegalMoves();
            return MoveContainerUtils.contains(legal, mv);
        };

        TranspositionTableEntry rootEntry = transpositionTable.get(rootHash);
        int seedMove = -1;
        Double seedScoreStored = null;

        if (rootEntry != null
                && rootEntry.bestMove != -1
                && MoveHelper.isWhitesMove(rootEntry.bestMove) == simulation.whitesTurn()
                && isLegalNow.test(rootEntry.bestMove)) {
            seedMove = rootEntry.bestMove;
            if (rootEntry.nodeType == NodeType.EXACT) {
                seedScoreStored = rootEntry.score; // stored (mate-distance encoded)
            }
        }

        if (seedMove == -1 && currentBestMove != -1
                && MoveHelper.isWhitesMove(currentBestMove) == simulation.whitesTurn()
                && isLegalNow.test(currentBestMove)) {
            seedMove = currentBestMove;
        }

        if (seedMove == -1) {
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

        if (simulation.getGameState().isTerminal()) {
            this.calculatedLine = Collections.synchronizedList(new ArrayList<>());
            return;
        }

        double seedScore = (seedScoreStored != null)
                ? fromStoredMateScore(seedScoreStored, 0) // FIX: decode stored score at ply 0
                : 0.0;

        pv.add(new MoveAndScore(seedMove, seedScore));
        simulation.performMove(seedMove);
        movesPerformed++;

        long curHash = simulation.getBoardStateHash();

        while (true) {
            if (!seen.add(curHash)) break;
            if (simulation.getGameState().isTerminal()) break;

            TranspositionTableEntry e = transpositionTable.get(curHash);
            if (e == null || e.bestMove == -1 || e.nodeType != NodeType.EXACT) break;

            int mv = e.bestMove;
            if (MoveHelper.isWhitesMove(mv) != simulation.whitesTurn()) break;
            if (!isLegalNow.test(mv)) break;

            // FIX: convert stored TT score using current plyFromRoot (= pv.size())
            double decodedScore = fromStoredMateScore(e.score, pv.size());
            pv.add(new MoveAndScore(mv, decodedScore));

            try {
                simulation.performMove(mv);
                movesPerformed++;
                curHash = simulation.getBoardStateHash();
            } catch (IllegalStateException ise) {
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
        boolean bestFromTablebase = false;

        IntArrayList sortedMoves = sortMovesByEfficiency(simulatorEngine.getAllLegalMoves(), depth,
                simulatorEngine.getBoardStateHash(), -1, simulatorEngine);
        maybeRotateRootMoves(sortedMoves, rng);
        promoteTablebaseMove(sortedMoves, simulatorEngine);

        Optional<TablebaseProber.TablebaseHit> rootTablebase = resolveTablebaseHit(simulatorEngine, isWhitesTurn);
        if (rootTablebase.isPresent()) {
            TablebaseProber.TablebaseHit hit = rootTablebase.get();
            int candidateMove = hit.bestMove();
            if (candidateMove >= 0 && MoveContainerUtils.contains(sortedMoves, candidateMove)) {
                return RootSearchResult.completed(createCandidate(candidateMove, hit.score(), true));
            }
        }

        for (int idx = 0; idx < sortedMoves.size(); idx++) {
            int moveInt = sortedMoves.getInt(idx);
            if (abortRequested(deadline)) {
                aborted = true;
                break;
            }

            RootMoveEvaluation evaluation = evaluateRootMove(simulatorEngine, isWhitesTurn, depth, deadline,
                    alpha, beta, moveInt, idx == 0);
            if (evaluation.aborted()) {
                aborted = true;
                break;
            }

            double score = evaluation.score();
            boolean moveTablebaseExact = evaluation.tablebaseExact();

            if (isBetterScore(isWhitesTurn, score, bestScore)) {
                bestScore = score;
                bestMove = moveInt;
                bestFromTablebase = moveTablebaseExact;
            }
            if (isWhitesTurn) {
                alpha = Math.max(alpha, score);
            } else {
                beta = Math.min(beta, score);
            }
            if (alpha >= beta) {
                break;
            }
        }
        MoveAndScore candidate = bestMove != -1 ? createCandidate(bestMove, bestScore, bestFromTablebase) : null;
        return aborted ? RootSearchResult.aborted(candidate) : RootSearchResult.completed(candidate);
    }

    private RootMoveEvaluation evaluateRootMove(Engine simulatorEngine,
                                                boolean isWhitesTurn,
                                                int depth,
                                                long deadline,
                                                double alpha,
                                                double beta,
                                                int moveInt,
                                                boolean firstMove) {
        simulatorEngine.performMove(moveInt);
        try {
            if (abortRequested(deadline)) {
                return RootMoveEvaluation.abortedResult();
            }

            GameState gameState = simulatorEngine.getGameState();
            long childHash = simulatorEngine.getBoardStateHash();

            if (gameState.isInStateCheckMate()) {
                double score = isWhitesTurn ? (CHECKMATE - 1) : -(CHECKMATE - 1);
                return RootMoveEvaluation.of(score, false);
            }
            if (gameState.isTerminal()) {
                double score = evaluateStaticPosition(gameState, childHash, !isWhitesTurn, depth);
                if (isWhitesTurn) score = -score;
                return RootMoveEvaluation.of(score, false);
            }

            Optional<TablebaseResult> childTablebase = resolveExactTablebaseResult(simulatorEngine);
            if (childTablebase.isPresent()) {
                double score = evaluateStaticPosition(gameState, childHash, !isWhitesTurn, depth);
                if (isWhitesTurn) score = -score;
                return RootMoveEvaluation.of(score, true);
            }

            double searchAlpha = alpha;
            double searchBeta = beta;
            boolean usePvsWindow = !firstMove;
            if (usePvsWindow) {
                if (isWhitesTurn) {
                    if (!Double.isFinite(alpha)) {
                        usePvsWindow = false;
                    } else {
                        double candidate = Math.nextAfter(alpha, Double.POSITIVE_INFINITY);
                        if (!(candidate > alpha)) {
                            usePvsWindow = false;
                        } else if (Double.isFinite(beta) && candidate >= beta) {
                            usePvsWindow = false;
                        } else {
                            searchBeta = candidate;
                        }
                    }
                } else {
                    if (!Double.isFinite(beta)) {
                        usePvsWindow = false;
                    } else {
                        double candidate = Math.nextAfter(beta, Double.NEGATIVE_INFINITY);
                        if (!(candidate < beta)) {
                            usePvsWindow = false;
                        } else if (Double.isFinite(alpha) && candidate <= alpha) {
                            usePvsWindow = false;
                        } else {
                            searchAlpha = candidate;
                        }
                    }
                }
            }

            double probe = alphaBeta(simulatorEngine, depth - 1, searchAlpha, searchBeta,
                    !isWhitesTurn, deadline, moveInt, 1, 0);
            if (probe == EXIT_FLAG || abortRequested(deadline)) {
                return RootMoveEvaluation.abortedResult();
            }

            double resultScore = probe;
            if (usePvsWindow) {
                boolean failHigh = isWhitesTurn ? resultScore > alpha : resultScore < beta;
                if (failHigh) {
                    double full = alphaBeta(simulatorEngine, depth - 1, alpha, beta,
                            !isWhitesTurn, deadline, moveInt, 1, 0);
                    if (full == EXIT_FLAG || abortRequested(deadline)) {
                        return RootMoveEvaluation.abortedResult();
                    }
                    resultScore = full;
                }
            }

            return RootMoveEvaluation.of(resultScore, false);
        } finally {
            simulatorEngine.undoLastMove();
        }
    }

    private record RootMoveEvaluation(double score, boolean tablebaseExact, boolean aborted) {
        static RootMoveEvaluation abortedResult() {
            return new RootMoveEvaluation(Double.NaN, false, true);
        }

        static RootMoveEvaluation of(double score, boolean tablebaseExact) {
            return new RootMoveEvaluation(score, tablebaseExact, false);
        }
    }

    private MoveAndScore createCandidate(int move, double score, boolean tablebaseExact) {
        if (move == -1) return null;
        if (!Double.isFinite(score)) return null;
        return new MoveAndScore(move, score, tablebaseExact);
    }

    /**
     * *
     * 5rkr/pp2Rp2/1b1p1Pb1/3P2Q1/2n3P1/2p5/P4P2/4R1K1 w - - 1 0
     * *
     */
    private Optional<TablebaseProber.TablebaseHit> resolveTablebaseHit(Engine engine, boolean isWhite) {
        return tablebaseProber.resolveTablebaseHit(engine, isWhite);
    }



    private Optional<TablebaseResult> resolveExactTablebaseResult(Engine engine) {
        return tablebaseProber.resolveExactTablebaseResult(engine);
    }

    private void promoteTablebaseMove(IntArrayList moves, Engine engine) {
        tablebaseProber.promoteTablebaseMove(moves, engine);
    }

    private boolean isExactWdl(TablebaseResult result) {
        return tablebaseProber.isExactWdl(result);
    }

    private double clampTablebaseEval(double eval) {
        return tablebaseProber.clampTablebaseEval(eval);
    }

    private double alphaBeta(Engine simulatorEngine, int depth, double alpha, double beta,
                             boolean isWhite, long deadline, int prevMove, int plyFromRoot,
                             int extStreak) {
        nodesVisited++;
        if (abortRequested(deadline)) return EXIT_FLAG;

        if (plyFromRoot >= maxDepth + ABS_PLY_LIMIT_MARGIN) {
            double eval = evaluateBoard(simulatorEngine, isWhite, deadline); // returns mover-perspective
            if (eval == EXIT_FLAG) return EXIT_FLAG;
            if (!isWhite) eval = -eval; // convert to WHITE-perspective
            return eval;
        }

        long boardHash = simulatorEngine.getBoardStateHash();
        boolean inCheck = isSideInCheck(simulatorEngine, isWhite);

        // Mate / terminal (white-perspective)
        if (simulatorEngine.getGameState().isInStateCheckMate()) {
            double m = CHECKMATE - plyFromRoot;
            return isWhite ? -m : +m; // side to move is losing
        }
        if (simulatorEngine.getGameState().isTerminal()) {
            return evaluateStaticPosition(simulatorEngine.getGameState(), boardHash, isWhite, plyFromRoot); // WHITE-perspective
        }

        Optional<TablebaseProber.TablebaseHit> tablebaseHit = resolveTablebaseHit(simulatorEngine, isWhite);
        if (tablebaseHit.isPresent()) {
            TablebaseProber.TablebaseHit hit = tablebaseHit.get();
            int bestMove = hit.bestMove() >= 0 ? hit.bestMove() : -1;
            double storedScore = toStoredMateScore(hit.score(), plyFromRoot); // hit.score() already WHITE-perspective
            transpositionTable.put(boardHash,
                    new TranspositionTableEntry(storedScore, depth, NodeType.EXACT, bestMove), depth);
            return hit.score();
        }

        if (depth <= 0) {
            double eval = evaluateBoard(simulatorEngine, isWhite, deadline); // mover perspective
            if (eval == EXIT_FLAG) return EXIT_FLAG;
            if (!isWhite) eval = -eval; // to WHITE-perspective
            return eval;
        }

        // TT probe (stored as WHITE-perspective)
        TranspositionTableEntry entry = transpositionTable.get(boardHash);
        if (entry != null && entry.depth >= depth) {
            double ttScore = fromStoredMateScore(entry.score, plyFromRoot);
            if (entry.nodeType == NodeType.EXACT) return ttScore;
            if (entry.nodeType == NodeType.LOWERBOUND && ttScore > alpha) alpha = ttScore;
            else if (entry.nodeType == NodeType.UPPERBOUND && ttScore < beta) beta = ttScore;
            if (alpha >= beta) return ttScore;
        }

        // --- Node classification
        IntArrayList moves = simulatorEngine.getAllLegalMoves();
        int mobility = moves.size();
        BitBoard bitBoard = simulatorEngine.getBitBoard();

        double staticEvalWhite = resolveScoreDifference(simulatorEngine.getGameState(), boardHash,
                simulatorEngine.whitesTurn());
        boolean staticEvalFinite = Double.isFinite(staticEvalWhite);
        boolean isPvNode = Double.isFinite(alpha) && Double.isFinite(beta) && beta > alpha + 1.0;

        int alphaSideCp = lowerBoundForSide(alpha, beta, isWhite);
        int betaSideCp = upperBoundForSide(alpha, beta, isWhite);
        int staticEvalSideCp = staticEvalFinite
                ? toCentipawnScore(isWhite ? staticEvalWhite : -staticEvalWhite)
                : 0;

        // Static-null & razoring: already correctly gated by PV
        if (staticEvalFinite) {
            boolean snmpPruned = SearchPruning.tryStaticNullMovePrune(
                    depth, inCheck, isPvNode, staticEvalSideCp, alphaSideCp, betaSideCp, score -> {});
            if (snmpPruned) {
                return isWhite ? beta : alpha;
            }

            boolean razorPruned = SearchPruning.tryRazoring(
                    depth, inCheck, isPvNode, staticEvalSideCp, alphaSideCp, betaSideCp,
                    (a, b) -> runRazoringQuiescence(simulatorEngine, isWhite, a, b, deadline), score -> {});
            if (razorPruned) {
                return isWhite ? alpha : beta;
            }
        }

        final int NULL_MOVE_SENTINEL = -1;
        boolean previousMoveWasNull = prevMove == NULL_MOVE_SENTINEL;
        if (previousMoveWasNull && plyFromRoot == 0) previousMoveWasNull = false;

        // **CHANGE #1**: Disable null-move at PV nodes (and keep other guards)
        boolean allowNullMove = useNullMovePruning
                && !isPvNode
                && !inCheck
                && !simulatorEngine.isEndgame()
                && !previousMoveWasNull;

        if (allowNullMove) {
            double mateThreatScore = CHECKMATE - (plyFromRoot + 1);
            boolean betaSignalsMateThreat = isWhite && Double.isFinite(beta) && beta >= mateThreatScore;
            boolean alphaSignalsMateThreat = !isWhite && Double.isFinite(alpha) && alpha <= -mateThreatScore;
            if (betaSignalsMateThreat || alphaSignalsMateThreat) allowNullMove = false;
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
                boolean requiresVerification = Math.abs(nullScore) >= mateThreshold || swing >= swingThreshold;

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
            if (nullFailHigh) return isWhite ? beta : alpha;
        }

        if (!isPvNode && !inCheck && depth >= SearchPruning.probCutEnableDepth()) {
            final int[] moveArray = moves.toIntArray();
            boolean probCutPruned = SearchPruning.tryProbCut(
                    depth, inCheck, isPvNode,
                    alphaSideCp, betaSideCp,
                    () -> moveArray,
                    move -> MoveHelper.isCapture(move) || MoveHelper.isPawnPromotionMove(move),
                    move -> simulatorEngine.see(move, 0),
                    simulatorEngine::performMove,
                    simulatorEngine::undoLastMove,
                    (reducedDepth, a, b) -> runProbCutVerification(simulatorEngine, reducedDepth, a, b,
                            isWhite, deadline, plyFromRoot),
                    score -> {});
            if (probCutPruned) {
                if (positionChanged()) return EXIT_FLAG;
                return isWhite ? beta : alpha;
            }
        }

        double alphaOriginal = alpha;
        double betaOriginal = beta;

        // Split by side-to-move “max/min” helpers (unchanged aside from the null-move gating above)
        if (isWhite) {
            return maximizer(simulatorEngine, depth, alpha, beta, boardHash, alphaOriginal, betaOriginal, moves,
                    deadline, prevMove, plyFromRoot, extStreak, staticEvalWhite);
        } else {
            return minimizer(simulatorEngine, depth, alpha, beta, boardHash, alphaOriginal, betaOriginal, moves,
                    deadline, prevMove, plyFromRoot, extStreak, staticEvalWhite);
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

        int clampedDepth = Math.min(depth, LMR_MAX_DEPTH);
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
        if (remainingDepth >= 3) {
            return searchPruningParameters.fpMarginDepth3();
        }
        return 0;
    }

    private int extendedFutilityMargin(int depth) {
        if (depth <= 1) {
            return searchPruningParameters.fpMarginDepth1();
        }
        if (depth == 2) {
            return searchPruningParameters.fpMarginDepth2();
        }
        if (depth >= 3) {
            return searchPruningParameters.fpMarginDepth3();
        }
        return 0;
    }

    private static int toCentipawnScore(double value) {
        // clamp to mate bounds in cp space
        double cap = CHECKMATE;
        if (value >= cap) return (int) cap;
        if (value <= -cap) return (int) -cap;
        return (int) Math.round(value);
    }

    private static int toWindowBound(double value, boolean upper) {
        if (Double.isInfinite(value)) {
            return upper ? (int) CHECKMATE : (int) -CHECKMATE;
        }
        double clipped = Math.max(-CHECKMATE, Math.min(CHECKMATE, value));
        return (int) (upper ? Math.ceil(clipped) : Math.floor(clipped));
    }

    private static int lowerBoundForSide(double alpha, double beta, boolean isWhite) {
        double bound = isWhite ? alpha : -beta;
        return toWindowBound(bound, false);
    }

    private static int upperBoundForSide(double alpha, double beta, boolean isWhite) {
        double bound = isWhite ? beta : -alpha;
        return toWindowBound(bound, true);
    }

    private static double toWhiteLowerBound(int alphaSide, int betaSide, boolean isWhite) {
        return isWhite ? alphaSide : -betaSide;
    }

    private static double toWhiteUpperBound(int alphaSide, int betaSide, boolean isWhite) {
        return isWhite ? betaSide : -alphaSide;
    }

    private static int toSideScore(double whiteScore, boolean isWhite) {
        double sideScore = isWhite ? whiteScore : -whiteScore;
        return toCentipawnScore(sideScore);
    }

    private int runRazoringQuiescence(Engine simulatorEngine, boolean isWhite, int alphaSide, int betaSide, long deadline) {
        double alphaWhite = toWhiteLowerBound(alphaSide, betaSide, isWhite);
        double betaWhite = toWhiteUpperBound(alphaSide, betaSide, isWhite);
        double score = quiescenceSearch(simulatorEngine, isWhite, alphaWhite, betaWhite, deadline, 0);
        if (score == EXIT_FLAG) {
            return alphaSide;
        }
        return toSideScore(score, isWhite);
    }

    private int runProbCutVerification(Engine simulatorEngine, int reducedDepth, int alphaSide, int betaSide,
                                       boolean parentIsWhite, long deadline, int plyFromRoot) {
        double alphaWhite = toWhiteLowerBound(alphaSide, betaSide, parentIsWhite);
        double betaWhite = toWhiteUpperBound(alphaSide, betaSide, parentIsWhite);
        int lastMove = simulatorEngine.getLastMove();
        double eval = alphaBeta(simulatorEngine, reducedDepth, alphaWhite, betaWhite, !parentIsWhite,
                deadline, lastMove, plyFromRoot + 1, 0);
        if (eval == EXIT_FLAG || positionChanged()) {
            return alphaSide;
        }
        return toSideScore(eval, parentIsWhite);
    }

    private double maximizer(Engine simulatorEngine, int depth, double alpha, double beta,
                             long boardHash, double alphaOriginal, double betaOriginal,
                             IntArrayList moves, long deadline, int prevMove, int plyFromRoot,
                             int extStreak, double staticEvalWhite) {
        double maxEval = Double.NEGATIVE_INFINITY;
        int bestMoveAtThisNode = -1;
        boolean bestZeroing = false;
        boolean tablebaseBestExact = false;

        final boolean inCheckAtNode = isSideInCheck(simulatorEngine, true);
        final boolean isPvNodeLocal = beta - alpha > 1.0;
        final boolean staticEvalAvailable = Double.isFinite(staticEvalWhite);
        final int staticEvalCp = staticEvalAvailable ? toCentipawnScore(staticEvalWhite) : 0;
        final int betaCp = upperBoundForSide(alpha, beta, true);
        final int efpDepthLimit = Math.min(3, futilityMaxDepth);
        final boolean prevMoveTactical = prevMove >= 0
                && (MoveHelper.isCapture(prevMove) || MoveHelper.isPawnPromotionMove(prevMove));
        final boolean gaveCheckLastMove = inCheckAtNode;
        if (!isPvNodeLocal && !inCheckAtNode && depth <= efpDepthLimit && !prevMoveTactical && !gaveCheckLastMove
                && staticEvalAvailable && Double.isFinite(beta)) {
            int margin = extendedFutilityMargin(depth);
            if (margin > 0 && staticEvalCp - margin >= betaCp) {
                return beta;
            }
        }

        final Heuristics heuristics = threadHeuristics.get();
        final int[][] historyTable = heuristics.history;

        IntArrayList orderedMoves = sortMovesByEfficiency(moves, depth, boardHash, prevMove, simulatorEngine);
        final Map<Integer, Integer> seeCache = seeCacheThreadLocal.get();
        seeCache.clear();
        final SearchPruningParameters.Snapshot pruning = searchPruningParameters;
        final int hmpMinIndex = pruning.hmpMinIndex();
        final int hmpHistoryMax = pruning.hmpHistoryMax();
        final int lmpBase = pruning.lmpBase();
        final int lmpPerDepth = pruning.lmpPerDepth();
        final int lmpMaxDepth = Math.max(0, pruning.lmpMaxDepth());
        final int iidReduceDepth = pruning.iidReduceDepth();
        final int lmrProtectPlyMax = pruning.lmrProtectPlyMax();
        final int lmrProtectIndexMax = pruning.lmrProtectIndexMax();
        final int lmrCapGoodQuiet = pruning.lmrCapForGoodQuiet();
        final int maxCheckExtensionStreak = Math.max(0, pruning.maxCheckExtensionStreak());
        final int seePruneNearRootPly = Math.max(0, pruning.seePruneNearRootPly());
        final int lqpMaxDepth = Math.max(0, pruning.lqpMaxDepth());
        final int lqpMoveIndexThreshold = Math.max(0, pruning.lqpMoveIndexThreshold());
        final int lqpHistoryThreshold = pruning.lqpHistoryThreshold();
        final int lqpButterflyThreshold = pruning.lqpButterflyThreshold();
        // Protect root and immediate reply from late pruning
        final boolean allowLatePrune = plyFromRoot >= 2;

        int baseRemainingDepth = Math.max(0, depth - 1);
        boolean futilityEligible = !inCheckAtNode
                && plyFromRoot > 0
                && depth >= 1
                && depth <= futilityMaxDepth
                && Double.isFinite(alpha);
        boolean futilityPossible = futilityEligible
                && ((baseRemainingDepth <= 1 && pruning.fpMarginDepth1() > 0)
                || (baseRemainingDepth == 2 && pruning.fpMarginDepth2() > 0)
                || (baseRemainingDepth >= 3 && pruning.fpMarginDepth3() > 0));
        double nodeStaticEvalWhite = staticEvalWhite;
        if (futilityPossible && !Double.isFinite(nodeStaticEvalWhite)) {
            futilityPossible = false;
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

            if (allowLatePrune && !isPvNodeLocal && !inCheckAtNode && isQuiet && depth <= lqpMaxDepth
                    && lqpMoveIndexThreshold >= 0 && index > lqpMoveIndexThreshold
                    && lqpHistoryThreshold >= 0 && lqpButterflyThreshold >= 0) {
                int butterflyScore = heuristics.getButterflyScore(move);
                if (historyScore <= lqpHistoryThreshold && butterflyScore <= lqpButterflyThreshold) {
                    continue;
                }
            }

            if (allowLatePrune
                    && !isPvNodeLocal
                    && !inCheckAtNode
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

            // SEE pruning only for losing captures (keep checks, promotions, and all quiets)
            boolean seePruneCandidate = !inCheckAtNode && isCapture && !isPromotion;
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
            if (allowLatePrune && !isPvNodeLocal && !inCheckAtNode && !isTactical && depth <= lmpMaxDepth && index > lmpThreshold) {
                simulatorEngine.performMove(move);
                boolean givesCheckTmp = isSideInCheck(simulatorEngine, false);
                boolean attacksQueenTmp = attacksOpponentQueenNow(simulatorEngine, true);
                simulatorEngine.undoLastMove();
                if (!givesCheckTmp && !attacksQueenTmp) continue;
            }

            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();

            Optional<TablebaseResult> childTablebase = resolveExactTablebaseResult(simulatorEngine);
            boolean childExact = childTablebase.isPresent();

            double eval;
            if (childExact) {
                eval = evaluateStaticPosition(simulatorEngine.getGameState(), newBoardHash, true, plyFromRoot + 1);
            } else {
                boolean givesCheck = isSideInCheck(simulatorEngine, false);
                boolean attacksQueen = attacksOpponentQueenNow(simulatorEngine, true);
                boolean attacksKingZone = attacksOpponentKingZone(simulatorEngine, true);
                boolean opensKingFile = openedFileTowardKing(simulatorEngine.getBitBoard(), kingFileMask, pawnsOnFileBefore, affectsKingFilePawns);

                if (futilityPossible && isQuiet && !givesCheck && !attacksQueen && !attacksKingZone
                        && !opensKingFile && !seeWinsMaterial) {
                    int futilityMargin = futilityMarginForRemainingDepth(baseRemainingDepth);
                    if (futilityMargin > 0 && nodeStaticEvalWhite + futilityMargin <= alpha) {
                        simulatorEngine.undoLastMove();
                        continue;
                    }
                }

                int nextDepth = depth - 1;
                boolean forcing = givesCheck || attacksQueen;
                boolean allowExtend = forcing && extStreak < maxCheckExtensionStreak;
                if (allowExtend) nextDepth++;
                int nextExtStreak = allowExtend ? extStreak + 1 : 0;

                eval = Double.NEGATIVE_INFINITY;
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
            }

            simulatorEngine.undoLastMove();

            eval = adjustMateFromChild(eval);
            boolean zeroing = isZeroingMove(move);

            if (eval > maxEval) {
                maxEval = eval;
                bestMoveAtThisNode = move;
                bestZeroing = zeroing;
                tablebaseBestExact = childExact;
            } else if (bestMoveAtThisNode != -1
                    && shouldUseTablebaseTieBreak(eval, maxEval)
                    && preferCandidateByTablebase(simulatorEngine, move, eval, zeroing, bestMoveAtThisNode, bestZeroing)) {
                maxEval = eval;
                bestMoveAtThisNode = move;
                bestZeroing = zeroing;
                tablebaseBestExact = childExact;
            }

            alpha = Math.max(alpha, eval);
            if (beta <= alpha) {
                updateKillerMoves(depth, move);
                incrementHistory(move, depth);
                if (!isCapture && !isPromotion) {
                    incrementButterfly(move);
                }
                heuristics.recordCounterMove(prevMove, move);
                break;
            }
        }

        TranspositionTableEntry existingEntry = transpositionTable.get(boardHash);
        boolean shouldUpdate = existingEntry == null || existingEntry.depth < depth;

        double storedScore = toStoredMateScore(maxEval, plyFromRoot);
        if (shouldUpdate) {
            NodeType nodeType;
            if (tablebaseBestExact) {
                nodeType = NodeType.EXACT;
            } else if (maxEval <= alphaOriginal) {
                nodeType = NodeType.UPPERBOUND;
            } else if (maxEval >= betaOriginal) {
                nodeType = NodeType.LOWERBOUND;
            } else {
                nodeType = NodeType.EXACT;
            }
            transpositionTable.put(boardHash, new TranspositionTableEntry(storedScore, depth, nodeType, bestMoveAtThisNode), depth);
        }

        return maxEval;
    }


    private double minimizer(Engine simulatorEngine, int depth, double alpha, double beta,
                             long boardHash, double alphaOriginal, double betaOriginal,
                             IntArrayList moves, long deadline, int prevMove, int plyFromRoot,
                             int extStreak, double staticEvalWhite) {

        long start = log.isDebugEnabled() ? System.nanoTime() : 0L;
        double minEval = Double.POSITIVE_INFINITY;
        int bestMoveAtThisNode = -1;
        boolean bestZeroing = false;
        boolean tablebaseBestExact = false;

        final boolean inCheckAtNode = isSideInCheck(simulatorEngine, false);
        final boolean isPvNodeLocal = beta - alpha > 1.0;
        final boolean staticEvalAvailable = Double.isFinite(staticEvalWhite);
        final int staticEvalCpSide = staticEvalAvailable ? toCentipawnScore(-staticEvalWhite) : 0;
        final int betaCpSide = upperBoundForSide(alpha, beta, false);
        final int efpDepthLimit = Math.min(3, futilityMaxDepth);
        final boolean prevMoveTactical = prevMove >= 0
                && (MoveHelper.isCapture(prevMove) || MoveHelper.isPawnPromotionMove(prevMove));
        final boolean gaveCheckLastMove = inCheckAtNode;
        if (!isPvNodeLocal && !inCheckAtNode && depth <= efpDepthLimit && !prevMoveTactical && !gaveCheckLastMove
                && staticEvalAvailable && Double.isFinite(alpha)) {
            int margin = extendedFutilityMargin(depth);
            if (margin > 0 && staticEvalCpSide - margin >= betaCpSide) {
                return alpha;
            }
        }

        final Heuristics heuristics = threadHeuristics.get();
        final int[][] historyTable = heuristics.history;

        IntArrayList orderedMoves = sortMovesByEfficiency(moves, depth, boardHash, prevMove, simulatorEngine);
        final Map<Integer, Integer> seeCache = seeCacheThreadLocal.get();
        seeCache.clear();
        final SearchPruningParameters.Snapshot pruning = searchPruningParameters;
        final int hmpMinIndex = pruning.hmpMinIndex();
        final int hmpHistoryMax = pruning.hmpHistoryMax();
        final int lmpBase = pruning.lmpBase();
        final int lmpPerDepth = pruning.lmpPerDepth();
        final int lmpMaxDepth = Math.max(0, pruning.lmpMaxDepth());
        final int iidReduceDepth = pruning.iidReduceDepth();
        final int lmrProtectPlyMax = pruning.lmrProtectPlyMax();
        final int lmrProtectIndexMax = pruning.lmrProtectIndexMax();
        final int lmrCapGoodQuiet = pruning.lmrCapForGoodQuiet();
        final int maxCheckExtensionStreak = Math.max(0, pruning.maxCheckExtensionStreak());
        final int seePruneNearRootPly = Math.max(0, pruning.seePruneNearRootPly());
        final int lqpMaxDepth = Math.max(0, pruning.lqpMaxDepth());
        final int lqpMoveIndexThreshold = Math.max(0, pruning.lqpMoveIndexThreshold());
        final int lqpHistoryThreshold = pruning.lqpHistoryThreshold();
        final int lqpButterflyThreshold = pruning.lqpButterflyThreshold();
        // Protect root and immediate reply from late pruning
        final boolean allowLatePrune = plyFromRoot >= 2;

        int baseRemainingDepth = Math.max(0, depth - 1);
        boolean futilityEligible = !inCheckAtNode
                && plyFromRoot > 0
                && depth >= 1
                && depth <= futilityMaxDepth
                && Double.isFinite(beta);
        boolean futilityPossible = futilityEligible
                && ((baseRemainingDepth <= 1 && pruning.fpMarginDepth1() > 0)
                || (baseRemainingDepth == 2 && pruning.fpMarginDepth2() > 0)
                || (baseRemainingDepth >= 3 && pruning.fpMarginDepth3() > 0));
        double nodeStaticEvalWhite = staticEvalWhite;
        if (futilityPossible && !Double.isFinite(nodeStaticEvalWhite)) {
            futilityPossible = false;
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

            if (allowLatePrune && !isPvNodeLocal && !inCheckAtNode && isQuiet && depth <= lqpMaxDepth
                    && lqpMoveIndexThreshold >= 0 && index > lqpMoveIndexThreshold
                    && lqpHistoryThreshold >= 0 && lqpButterflyThreshold >= 0) {
                int butterflyScore = heuristics.getButterflyScore(move);
                if (historyScore <= lqpHistoryThreshold && butterflyScore <= lqpButterflyThreshold) {
                    continue;
                }
            }

            if (allowLatePrune
                    && !isPvNodeLocal
                    && !inCheckAtNode
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

            // SEE pruning only for losing captures (keep checks, promotions, and all quiets)
            boolean seePruneCandidate = !inCheckAtNode && isCapture && !isPromotion;
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
            if (allowLatePrune && !isPvNodeLocal && !inCheckAtNode && !isTactical && depth <= lmpMaxDepth && index > lmpThreshold) {
                simulatorEngine.performMove(move);
                boolean givesCheckTmp = isSideInCheck(simulatorEngine, true);
                boolean attacksQueenTmp = attacksOpponentQueenNow(simulatorEngine, false);
                simulatorEngine.undoLastMove();
                if (!givesCheckTmp && !attacksQueenTmp) continue;
            }

            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();

            Optional<TablebaseResult> childTablebase = resolveExactTablebaseResult(simulatorEngine);
            boolean childExact = childTablebase.isPresent();

            double eval;
            if (childExact) {
                eval = evaluateStaticPosition(simulatorEngine.getGameState(), newBoardHash, true, plyFromRoot + 1);
            } else {
                boolean givesCheck = isSideInCheck(simulatorEngine, true);
                boolean attacksQueen = attacksOpponentQueenNow(simulatorEngine, false);
                boolean attacksKingZone = attacksOpponentKingZone(simulatorEngine, false);
                boolean opensKingFile = openedFileTowardKing(simulatorEngine.getBitBoard(), kingFileMask, pawnsOnFileBefore, affectsKingFilePawns);

                if (futilityPossible && isQuiet && !givesCheck && !attacksQueen && !attacksKingZone
                        && !opensKingFile && !seeWinsMaterial) {
                    int futilityMargin = futilityMarginForRemainingDepth(baseRemainingDepth);
                    if (futilityMargin > 0 && nodeStaticEvalWhite - futilityMargin >= beta) {
                        simulatorEngine.undoLastMove();
                        continue;
                    }
                }

                int nextDepth = depth - 1;
                boolean forcing = givesCheck || attacksQueen;
                boolean allowExtend = forcing && extStreak < maxCheckExtensionStreak;
                if (allowExtend) nextDepth++;
                int nextExtStreak = allowExtend ? extStreak + 1 : 0;

                eval = Double.POSITIVE_INFINITY;
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
                tablebaseBestExact = childExact;
            } else if (bestMoveAtThisNode != -1
                    && shouldUseTablebaseTieBreak(eval, minEval)
                    && preferCandidateByTablebase(simulatorEngine, move, eval, zeroing, bestMoveAtThisNode, bestZeroing)) {
                minEval = eval;
                bestMoveAtThisNode = move;
                bestZeroing = zeroing;
                tablebaseBestExact = childExact;
            }

            beta = Math.min(beta, eval);
            if (alpha >= beta) {
                updateKillerMoves(depth, move);
                incrementHistory(move, depth);
                if (!isCapture && !isPromotion) {
                    incrementButterfly(move);
                }
                heuristics.recordCounterMove(prevMove, move);
                break;
            }
        }

        TranspositionTableEntry existingEntry = transpositionTable.get(boardHash);
        boolean shouldUpdate = existingEntry == null || existingEntry.depth < depth;

        double storedScore = toStoredMateScore(minEval, plyFromRoot);
        if (shouldUpdate) {
            NodeType nodeType;
            if (tablebaseBestExact) {
                nodeType = NodeType.EXACT;
            } else if (minEval >= betaOriginal) {
                nodeType = NodeType.LOWERBOUND;
            } else if (minEval <= alphaOriginal) {
                nodeType = NodeType.UPPERBOUND;
            } else {
                nodeType = NodeType.EXACT;
            }
            transpositionTable.put(boardHash, new TranspositionTableEntry(storedScore, depth, nodeType, bestMoveAtThisNode), depth);
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
        return moveOrderer.sortMovesByEfficiency(moves, currentDepth, boardHash, prevMove,
                simulatorEngine, threadHeuristics.get(), transpositionTable);
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

        GameState gameState = simulatorEngine.getGameState();
        Optional<TablebaseResult> tablebase = gameState.getLastTablebaseResult();
        if (tablebase.isPresent() && isExactWdl(tablebase.get())) {
            long boardHash = simulatorEngine.getBoardStateHash();
            return evaluateStaticPosition(gameState, boardHash, isWhitesTurn, depth);
        }

        // Never expand terminal nodes in qsearch: just evaluate
        if (gameState.isTerminal()) {
            long boardHash = simulatorEngine.getBoardStateHash();
            return evaluateStaticPosition(gameState, boardHash, isWhitesTurn, depth);
        }

        final boolean inCheck = isSideInCheck(simulatorEngine, isWhitesTurn);

        long boardHash = simulatorEngine.getBoardStateHash();
        double standPat = evaluateStaticPosition(gameState, boardHash, isWhitesTurn, depth);

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
                int lastMove = simulatorEngine.getLastMove();
                boolean lastMoveWasCapture = lastMove >= 0 && MoveHelper.isCapture(lastMove);
                int recaptureSquare = lastMoveWasCapture ? MoveHelper.deriveToIndex(lastMove) : -1;

                boolean allowDeltaPrune = true;
                for (int i = 0; i < moves.size(); i++) {
                    int move = moves.getInt(i);
                    if (!MoveHelper.isCapture(move)) {
                        continue;
                    }
                    boolean isRecapture = lastMoveWasCapture && MoveHelper.deriveToIndex(move) == recaptureSquare;
                    if (isRecapture || simulatorEngine.see(move, 0) >= 0) {
                        allowDeltaPrune = false;
                        break;
                    }
                }

                if (allowDeltaPrune) {
                    return alpha;
                }
            }
        }

        // Order moves (MVV-LVA, history, hash-move etc.)
        IntArrayList ordered = sortMovesByEfficiency(
                moves, 0, simulatorEngine.getBoardStateHash(), -1, simulatorEngine
        );

        for (int i = 0; i < ordered.size(); i++) {
            int m = ordered.getInt(i);

            boolean isCapture = MoveHelper.isCapture(m);
            boolean isPromotion = MoveHelper.isPawnPromotionMove(m);
            boolean isQuiet = !isCapture && !isPromotion;

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
            Optional<TablebaseResult> childTablebase = resolveExactTablebaseResult(simulatorEngine);
            double child;
            if (childTablebase.isPresent()) {
                long childHash = simulatorEngine.getBoardStateHash();
                child = evaluateStaticPosition(simulatorEngine.getGameState(), childHash, !isWhitesTurn, depth + 1);
            } else {
                child = quiescenceSearch(simulatorEngine, !isWhitesTurn, -beta, -alpha, deadline, depth + 1);
            }
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
        return quiescenceSearcher.evaluateStaticPosition(gameState, boardHash, isWhitesTurn, depthOrPly);
    }

    private IntArrayList getPossibleCapturesOrPromotions(Engine simulatorEngine) {
        return MoveContainerUtils.filterCapturesAndPromotions(simulatorEngine.getAllLegalMoves());
    }

    private boolean hasImmediateTacticalMoves(Engine simulatorEngine) {
        return simulatorEngine.hasAnyCaptureOrPromotion();
    }

    private double estimateMaxTacticalSwing(IntArrayList moves) {
        return quiescenceSearcher.estimateMaxTacticalSwing(moves);
    }

    private boolean isMateValue(double score) {
        return QuiescenceSearcher.isMateValue(score);
    }

    private double adjustMateFromChild(double score) {
        return QuiescenceSearcher.adjustMateFromChild(score);
    }

    private double toStoredMateScore(double score, int plyFromRoot) {
        return QuiescenceSearcher.toStoredMateScore(score, plyFromRoot);
    }

    private double fromStoredMateScore(double score, int plyFromRoot) {
        return QuiescenceSearcher.fromStoredMateScore(score, plyFromRoot);
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
        return tablebaseProber.shouldUseTablebaseTieBreak(candidateEval, bestEval);
    }

    private boolean preferCandidateByTablebase(Engine engine,
                                               int candidateMove,
                                               double candidateEval,
                                               boolean candidateZeroing,
                                               int bestMove,
                                               boolean bestZeroing) {
        return tablebaseProber.preferCandidateByTablebase(engine, candidateMove, candidateEval,
                candidateZeroing, bestMove, bestZeroing);
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

    private void incrementButterfly(int move) {
        if (move == -1 || MoveHelper.isCapture(move) || MoveHelper.isPawnPromotionMove(move)) {
            return;
        }
        threadHeuristics.get().addButterfly(move, 1);
    }

    private void clearHistoryTable() {
        heuristicsManager.clearHistory();
    }

    private int calculateMvvLvaScore(int move) {
        return MoveOrderer.calculateMvvLvaScore(move);
    }

    // Heuristics, WorkerInstrumentation, and LockMetrics have been extracted
    // to their own top-level files in this package.
}
