package julius.game.chessengine.ai;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.Move;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.engine.search.config.SearchConfig;
import julius.game.chessengine.engine.search.config.SearchLimits;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.PawnMoveTables;
import julius.game.chessengine.helper.RookHelper;
import julius.game.chessengine.engine.search.context.SearchContext;
import julius.game.chessengine.engine.search.context.WorkerContext;
import julius.game.chessengine.utils.Score;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import java.util.*;
import java.util.concurrent.*;

import static julius.game.chessengine.helper.BitHelper.FileMasks;
import static julius.game.chessengine.helper.KingHelper.KING_ATTACKS;
import static julius.game.chessengine.helper.KnightHelper.knightMoveTable;
import static julius.game.chessengine.utils.Score.*;

@Log4j2
@Component
public class AI {

    @Getter
    private final Engine mainEngine;

    private final SearchConfig searchConfig;

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
    private volatile SearchLimits searchLimits = SearchLimits.unlimited();

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

    public static final int MIN_HASH_SIZE_MB = SearchConfig.MIN_HASH_SIZE_MB;
    public static final int MAX_HASH_SIZE_MB = SearchConfig.MAX_HASH_SIZE_MB;
    public static final long INFINITE_TIME_LIMIT = Long.MAX_VALUE;

    /**
     * Thread pool for root-split parallel search (created only if searchThreads > 1).
     */
    private ExecutorService searchPool;

    /**
     * Limit how many root moves we fan out in parallel to avoid oversubscription.
     */
    private static final int ROOT_PARALLEL_LIMIT =
            Integer.getInteger("chessengine.rootParallelLimit", 24);

    public static final double EXIT_FLAG = Double.MAX_VALUE;

    private static final BishopHelper BISHOP_HELPER = BishopHelper.getInstance();
    private static final RookHelper ROOK_HELPER = RookHelper.getInstance();


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

    private final SearchContext searchContext;

    private ScheduledExecutorService scheduler;

    private final Object calculationLock = new Object();

    private volatile boolean keepCalculating = true;

    private volatile long currentBoardState = -1;
    private volatile long beforeCalculationBoardState = -2;

    private volatile int currentBestMove = -1;
    private volatile int previousBestMove = -1;
    private volatile long previousBestMoveHash = -1;
    private volatile boolean searchResultReady = false;

    private volatile long bestMoveForHash = -1;

    private volatile long searchStartTimeNanos = 0L;
    @Getter
    private volatile SearchDiagnostics lastDiagnostics = SearchDiagnostics.EMPTY;

    public List<MoveAndScore> getCalculatedLine() {
        return searchContext.getLastCompletedPrincipalVariation();
    }

    public long getNodesVisited() {
        return searchContext.getNodesVisited();
    }

    public long getNullMoveCount() {
        return searchContext.getNullMoveCount();
    }

    public AI(Engine mainEngine) {
        this(mainEngine, SearchConfig.defaults());
    }

    public AI(Engine mainEngine, SearchConfig config) {
        Objects.requireNonNull(mainEngine, "mainEngine");
        this.mainEngine = mainEngine;
        this.searchConfig = config != null ? config : SearchConfig.defaults();

        int initialThreads = Integer.getInteger("chessengine.searchThreads", searchConfig.getThreads());
        searchConfig.setThreads(initialThreads);

        log.info("### SearchThreads = {}, LazySmpThreads = {}", searchConfig.getThreads(), lazySmpThreads);

        int depthCapacity = searchConfig.effectiveMaxDepth();
        this.searchContext = new SearchContext(depthCapacity);

        rebuildSearchPool(searchConfig.getThreads());

        this.mainEngine.setOnPositionChanged(_ -> updateBoardStateHash());
    }

    public int getSearchThreads() {
        return searchConfig.getThreads();
    }

    public int getHashSizeMb() {
        return searchConfig.getHashSizeMb();
    }

    public int getMaxDepth() {
        return searchConfig.getMaxDepth();
    }

    public SearchConfig getSearchConfig() {
        return searchConfig;
    }

    public SearchLimits getSearchLimits() {
        return searchLimits;
    }

    private void updatePrincipalVariation(List<MoveAndScore> principalVariation) {
        searchContext.updatePrincipalVariation(principalVariation);
    }

    private void clearPrincipalVariation() {
        searchContext.clearPrincipalVariation();
    }

    private void resetCurrentPrincipalVariation() {
        searchContext.resetCurrentPrincipalVariation();
    }

    private void rebuildTranspositionTables() {
        int threads = searchConfig.getThreads();
        boolean concurrent = Math.max(threads, lazySmpThreads) > 1;
        long totalBytes = Math.max(1L, (long) searchConfig.getHashSizeMb() * 1024L * 1024L);

        double totalWeight = MAIN_TT_WEIGHT + CAPTURE_TT_WEIGHT;
        long mainBudget = Math.max(1L, (long) (totalBytes * (MAIN_TT_WEIGHT / totalWeight)));
        long captureBudget = Math.max(1L, totalBytes - mainBudget);

        int mainCapacity = SearchConfig.computeHashCapacity(mainBudget, MAIN_TT_ENTRY_BYTES,
                MIN_MAIN_TT_ENTRIES, MAX_MAIN_TT_ENTRIES);
        int captureCapacity = SearchConfig.computeHashCapacity(captureBudget, CAPTURE_TT_ENTRY_BYTES,
                MIN_CAPTURE_TT_ENTRIES, MAX_CAPTURE_TT_ENTRIES);

        this.transpositionTableCapacity = mainCapacity;
        this.captureTranspositionTableCapacity = captureCapacity;

        this.transpositionTable = concurrent
                ? new FixedSizeTranspositionTable<>(mainCapacity)
                : new PlainFixedSizeTranspositionTable<>(mainCapacity);

        this.captureTranspositionTable = concurrent
                ? new FixedSizeTranspositionTable<>(captureCapacity)
                : new PlainFixedSizeTranspositionTable<>(captureCapacity);
    }

    /**
     * Adjust the requested hash size (in megabytes) and rebuild the transposition tables.
     * Values outside the supported range are clamped between
     * {@value julius.game.chessengine.engine.search.config.SearchConfig#MIN_HASH_SIZE_MB} MB
     * and {@value julius.game.chessengine.engine.search.config.SearchConfig#MAX_HASH_SIZE_MB} MB.
     * The resulting table capacities are also limited to
     * {@value MIN_MAIN_TT_ENTRIES}/{@value MAX_MAIN_TT_ENTRIES} for the main table and
     * {@value MIN_CAPTURE_TT_ENTRIES}/{@value MAX_CAPTURE_TT_ENTRIES} for the capture table.
     */
    public synchronized void setHashSizeMb(int hashSizeMb) {
        if (!searchConfig.setHashSizeMb(hashSizeMb)) {
            return;
        }

        if (transpositionTable != null) {
            transpositionTable.clear();
        }
        if (captureTranspositionTable != null) {
            captureTranspositionTable.clear();
        }

        rebuildTranspositionTables();

        log.info("Hash size set to {} MB (main TT capacity: {}, capture TT capacity: {})",
                searchConfig.getHashSizeMb(), transpositionTableCapacity, captureTranspositionTableCapacity);
    }

    /**
     * Override the maximum depth for iterative deepening. The killer-move table is
     * grown on demand so deeper searches can proceed without being clamped by the
     * previous allocation. The requested depth is always respected (minimum 1).
     */
    public synchronized void setMaxDepth(int depth) {
        searchConfig.setMaxDepth(depth);
        int requestedDepth = searchConfig.getMaxDepth();
        searchContext.ensureCapacity(requestedDepth);
    }


    public void setSearchLimits(SearchLimits limits) {
        this.searchLimits = limits != null ? limits : SearchLimits.unlimited();
    }

    public void setTimeLimit(long timeLimitMillis) {
        if (timeLimitMillis <= 0L || timeLimitMillis >= INFINITE_TIME_LIMIT) {
            setSearchLimits(SearchLimits.unlimited());
            return;
        }
        setSearchLimits(SearchLimits.builder().moveTimeMillis(timeLimitMillis).build());
    }

    public long getTimeLimit() {
        SearchLimits limits = this.searchLimits;
        long moveTime = limits.getMoveTimeMillis();
        if (moveTime > 0L) {
            return moveTime;
        }
        long hard = limits.getHardDeadlineNanos();
        if (hard > 0L && hard < Long.MAX_VALUE) {
            return TimeUnit.NANOSECONDS.toMillis(hard);
        }
        long soft = limits.getSoftDeadlineNanos();
        if (soft > 0L && soft < Long.MAX_VALUE) {
            return TimeUnit.NANOSECONDS.toMillis(soft);
        }
        return INFINITE_TIME_LIMIT;
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
            return;
        }

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
    }

    private void searchWorkerLoop(int workerIndex) {
        long lastTaskId = -1L;
        while (keepCalculating && !Thread.currentThread().isInterrupted()) {
            SearchTask task;
            synchronized (calculationLock) {
                while (keepCalculating && !Thread.currentThread().isInterrupted()) {
                    task = activeSearch.get();
                    if (task != null && task.getId() != lastTaskId) break;
                    try {
                        calculationLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!keepCalculating || Thread.currentThread().isInterrupted()) return;
                task = activeSearch.get();
                if (task == null || task.getId() == lastTaskId) continue;
            }
            lastTaskId = task.getId();
            runSearchOnTask(task, workerIndex);
        }
    }

    private void runSearchOnTask(SearchTask task, int workerIndex) {
        Engine simulator;
        try {
            simulator = mainEngine.createSimulation();
        } catch (RuntimeException e) {
            log.error("Failed to create simulation for worker {}", workerIndex, e);
            task.workerDone();
            return;
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

    private void iterativeDeepening(SearchTask task, Engine simulatorEngine, SplittableRandom rng) {
        Double lastIterScore = null;
        WorkerContext worker = searchContext.worker();
        SearchContext.Heuristics heuristics = worker.heuristics();
        SearchInstrumentation instr = task.getInstrumentation();

        int depthLimit = searchConfig.getMaxDepth();
        for (int currentDepth = 1; currentDepth <= depthLimit; currentDepth++) {
            if (shouldStopCalculating(task.getDeadline())) break;

            boolean firstAtDepth = task.beginIteration(currentDepth);
            prepareIterationState(task, worker, currentDepth, firstAtDepth);

            MoveAndScore ms = null;

            double alpha, beta;
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
                        instr.recordAspirationFailLow();
                        window *= 2.0;
                        alpha = ms.score - window;
                        if (++retries > 3) {
                            alpha = Double.NEGATIVE_INFINITY;
                            beta = Double.POSITIVE_INFINITY;
                            instr.recordAspirationReset();
                        } else continue;
                    }
                    if (ms.score >= beta) {
                        instr.recordAspirationFailHigh();
                        window *= 2.0;
                        beta = ms.score + window;
                        if (++retries > 3) {
                            instr.recordAspirationReset();
                        } else continue;
                    }
                    break;
                }
            }

            if (ms == null) {
                ms = searchRootMoves(simulatorEngine, task, currentDepth, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, rng);
                if (ms == null) {
                    if (heuristics.hasUpdates()) {
                        mergeThreadHeuristics(worker);
                    }
                    break;
                }
            }

            lastIterScore = ms.score;
            task.publishBest(ms, currentDepth);
            instr.recordIterationComplete(currentDepth);
            if (heuristics.hasUpdates()) {
                mergeThreadHeuristics(worker);
            }
            if (task.isStopRequested()) break;
        }
    }

    private void prepareIterationState(SearchTask task, WorkerContext worker, int currentDepth, boolean firstAtDepth) {
        int maxDepth = searchConfig.getMaxDepth();
        if (firstAtDepth) {
            if (transpositionTable != null) {
                transpositionTable.advanceAge();
            }
            if (captureTranspositionTable != null) {
                captureTranspositionTable.advanceAge();
            }
        }
        searchContext.prepareIteration(task.getId(), worker, currentDepth, maxDepth, firstAtDepth);
    }

    private void mergeThreadHeuristics(WorkerContext worker) {
        searchContext.mergeWorkerHeuristics(worker);
    }

    private synchronized void rebuildSearchPool(int previousSearchThreads) {
        ExecutorService oldPool = this.searchPool;
        if (oldPool != null) {
            oldPool.shutdownNow();
        }

        int threads = searchConfig.getThreads();
        if (threads > 1) {
            this.searchPool = Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "AI-Search-" + System.identityHashCode(r));
                t.setDaemon(true);
                return t;
            });
        } else {
            this.searchPool = null;
        }

        boolean previousConcurrent = Math.max(previousSearchThreads, lazySmpThreads) > 1;
        boolean newConcurrent = Math.max(threads, lazySmpThreads) > 1;
        if (transpositionTable == null || previousConcurrent != newConcurrent) {
            rebuildTranspositionTables();
        }
    }

    public void setSearchThreads(int requestedThreads) {
        int previous = searchConfig.getThreads();
        if (!searchConfig.setThreads(requestedThreads)) {
            return;
        }

        stopCalculation();

        rebuildSearchPool(previous);
        log.info("Search thread count updated to {}", searchConfig.getThreads());
    }

    private WorkerContext prepareHelperHeuristics(SearchTask task, int depth) {
        WorkerContext worker = searchContext.worker();
        int maxDepth = searchConfig.getMaxDepth();
        searchContext.prepareHeuristics(task.getId(), worker, depth, maxDepth);
        return worker;
    }

    protected MoveAndScore searchRootMoves(Engine sim, SearchTask task, int depth, double alpha, double beta, SplittableRandom rng) {
        if (searchConfig.getThreads() > 1) {
            return getBestMoveParallel(sim, task, depth, task.getDeadline(), alpha, beta, rng);
        }
        return getBestMove(sim, task.isWhiteToMove(), depth, task.getDeadline(), alpha, beta, rng);
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
        searchContext.resetCounters();
        searchStartTimeNanos = 0L;
        clearPrincipalVariation();
        mainEngine.startNewGame();
        clearHistoryTable();
    }

    public long getSearchElapsedMillis() {
        long start = searchStartTimeNanos;
        if (start == 0L) {
            return 0L;
        }
        long elapsedNanos = System.nanoTime() - start;
        if (elapsedNanos <= 0L) {
            return 0L;
        }
        return TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
    }

    private void resetSearchCounters() {
        searchContext.resetCounters();
        searchStartTimeNanos = System.nanoTime();
    }

    public void stopCalculation() {
        keepCalculating = false;

        SearchTask task = activeSearch.get();
        if (task != null) task.requestStop();

        synchronized (calculationLock) {
            calculationLock.notifyAll();
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
        resetCurrentPrincipalVariation();
        currentBestMove = -1;
        bestMoveForHash = -1;
        previousBestMove = -1;
        previousBestMoveHash = -1;
        searchResultReady = false;
    }


    /**
     * Request the currently active search task to stop after its current iteration.
     * Unlike {@link #stopCalculation()}, this does not tear down the worker threads
     * or reset the cached best move, allowing callers to obtain the final result
     * once the workers finish gracefully.
     */
    public void requestStop() {
        SearchTask task = activeSearch.get();
        if (task != null) {
            task.requestStop();
        }
    }


    public void startAutoPlay(boolean aiIsWhite, boolean aiIsBlack) {

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow(); // Ensure previous scheduler is stopped
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();

        startCalculationThread();
        scheduler.scheduleAtFixedRate(() -> {
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
            synchronized (calculationLock) {
                calculationLock.notifyAll();
            } // <-- wake recalculation
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
        currentBoardState = mainEngine.getBoardStateHash();
        synchronized (calculationLock) {
            calculationLock.notifyAll();
        }
        currentBestMove = -1; // don’t re-play it
        bestMoveForHash = -1;
        previousBestMove = -1;
        previousBestMoveHash = -1;
        searchResultReady = false;
    }


    private void calculateLine() {
        long lastObservedHash = Long.MIN_VALUE;
        while (keepCalculating && !Thread.currentThread().isInterrupted()) {
            long targetHash;
            synchronized (calculationLock) {
                while (keepCalculating && !Thread.currentThread().isInterrupted()) {
                    targetHash = currentBoardState;
                    if (targetHash != lastObservedHash) break;
                    try {
                        calculationLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!keepCalculating || Thread.currentThread().isInterrupted()) return;
            }

            currentBoardState = mainEngine.getBoardStateHash();
            beforeCalculationBoardState = currentBoardState;
            performCalculation();
            lastObservedHash = currentBoardState;
        }
    }


    private long computeDeadlineNanos() {
        long start = System.nanoTime();
        return searchLimits.hardDeadlineNanos(start);
    }

    private void performCalculation() {
        try {
            Engine simulatorEngine = mainEngine.createSimulation();
            long boardStateHash = simulatorEngine.getBoardStateHash();
            boolean isWhite = simulatorEngine.whitesTurn();
            long deadline = computeDeadlineNanos();
            beforeCalculationBoardState = boardStateHash;

            int bookMove = mainEngine.getOpeningBook().getRandomMoveForBoardStateHash(boardStateHash);
            if (bookMove != -1) {
                currentBestMove = bookMove;
                bestMoveForHash = boardStateHash;
                previousBestMove = bookMove;
                previousBestMoveHash = boardStateHash;
                searchResultReady = true;
                updatePrincipalVariation(List.of(new MoveAndScore(bookMove, 0.0)));
                lastDiagnostics = SearchDiagnostics.EMPTY;
                return;
            }

            SearchInstrumentation instrumentation = SearchInstrumentation.enabled();
            lastDiagnostics = SearchDiagnostics.EMPTY;
            resetSearchCounters();
            SearchTask task = new SearchTask(searchIdGenerator.incrementAndGet(), boardStateHash, isWhite, deadline, lazySmpThreads, instrumentation);
            searchContext.beginSearch(task.getId(), searchConfig.getMaxDepth());
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
            resetCurrentPrincipalVariation();

            synchronized (calculationLock) {
                calculationLock.notifyAll();
            }

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
        int move = best.move();

        if (move != -1) {
            currentBestMove = move;
            bestMoveForHash = task.getBoardHash();
            previousBestMove = move;
            previousBestMoveHash = task.getBoardHash();
            searchResultReady = true;
            fillCalculatedLine(simulatorEngine);
            lastDiagnostics = task.getInstrumentation().snapshot(best.depth(), best.score());
            return;
        }

        if (previousBestMove != -1 && previousBestMoveHash == task.getBoardHash() &&
                isMoveStillLegal(simulatorEngine, previousBestMove)) {
            currentBestMove = previousBestMove;
            bestMoveForHash = task.getBoardHash();
            previousBestMoveHash = task.getBoardHash();
            searchResultReady = true;
            fillCalculatedLine(simulatorEngine);
            lastDiagnostics = task.getInstrumentation().snapshot(best.depth(), best.score());
            return;
        }

        currentBestMove = -1;
        bestMoveForHash = -1;
        previousBestMove = -1;
        previousBestMoveHash = -1;
        searchResultReady = false;
        clearPrincipalVariation();
        lastDiagnostics = task.getInstrumentation().snapshot(best.depth(), best.score());
    }

    private boolean isMoveStillLegal(Engine simulatorEngine, int move) {
        MoveList legalMoves = simulatorEngine.getAllLegalMoves();
        for (int i = 0; i < legalMoves.size(); i++) {
            if (legalMoves.getMove(i) == move) {
                return true;
            }
        }
        return false;
    }

    private void maybeRotateRootMoves(MoveList moves, SplittableRandom rng) {
        if (rng == null) return;
        int size = moves.size();
        if (size == 0) return;

        int bound = Math.min(size, 4);
        if (bound <= 1) return;

        int rotation = rng.nextInt(bound);
        if (rotation == 0) return;

        rotateMoveListLeft(moves, rotation);
    }

    private void rotateMoveListLeft(MoveList moves, int distance) {
        int size = moves.size();
        if (size <= 1) return;

        int shift = distance % size;
        if (shift < 0) shift += size;
        if (shift == 0) return;

        for (int r = 0; r < shift; r++) {
            int first = moves.getMove(0);
            for (int i = 0; i < size - 1; i++) {
                int next = moves.getMove(i + 1);
                moves.setMove(i, next);
            }
            moves.setMove(size - 1, first);
        }
    }

    private boolean abortRequested(long deadline) {
        if (Thread.currentThread().isInterrupted()) return true;
        if (System.nanoTime() > deadline) return true;

        SearchTask t = threadSearchTask.get();
        if (t == null) {
            // When no worker task is active (e.g. direct test invocations) there is no
            // ongoing monitored position, so ignore stale-board checks.
            return false;
        }

        if (positionChanged()) return true;
        return t.isStopRequested();
    }

    private SearchInstrumentation instrumentation() {
        SearchTask t = threadSearchTask.get();
        return t != null ? t.getInstrumentation() : SearchInstrumentation.disabled();
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
        MoveList legal = simulatorEngine.getAllLegalMoves();
        MoveList orderedMoves = sortMovesByEfficiency(legal, depth, simulatorEngine.getBoardStateHash(), -1, simulatorEngine);
        if (orderedMoves.size() == 0) return null;
        maybeRotateRootMoves(orderedMoves, rng);
        SearchInstrumentation instr = instrumentation();
        instr.recordRootMovesGenerated(orderedMoves.size());

        final AtomicReference<Double> alphaRef = new AtomicReference<>(alpha);
        final AtomicReference<Double> betaRef = new AtomicReference<>(beta);
        final java.util.concurrent.atomic.AtomicInteger bestMoveRef =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        final AtomicReference<Double> bestScoreRef = new AtomicReference<>(isWhitesTurn ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
        final AtomicReference<MoveAndScore> fallbackMateRef = new AtomicReference<>();
        final Set<Integer> refutedRootMoves = ConcurrentHashMap.newKeySet();
        final AtomicBoolean stopRef = new AtomicBoolean(false);
        final AtomicBoolean mateWinFound = new AtomicBoolean(false);

        double bestScore = isWhitesTurn ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        int bestMove = -1;

        if (abortRequested(deadline)) return null;

        int firstMove = orderedMoves.getMove(0);
        instr.recordRootMoveExplored();
        simulatorEngine.performMove(firstMove);
        long firstChildHash = simulatorEngine.getBoardStateHash();
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
        applyRootScore(isWhitesTurn, firstMove, firstScore, firstChildHash, depth - 1,
                alphaRef, betaRef, bestMoveRef, bestScoreRef, fallbackMateRef,
                refutedRootMoves, mateWinFound, stopRef);
        simulatorEngine.undoLastMove();

        if (mateWinFound.get()) {
            return new MoveAndScore(bestMoveRef.get(), bestScoreRef.get());
        }

        bestMove = bestMoveRef.get();
        bestScore = bestScoreRef.get();
        alpha = alphaRef.get();
        beta = betaRef.get();

        if (orderedMoves.size() == 1) {
            MoveAndScore fallback = fallbackMateRef.get();
            return bestMove != -1 ? new MoveAndScore(bestMove, bestScore) : fallback;
        }

        final int fanout = Math.min(Math.min(ROOT_PARALLEL_LIMIT, searchConfig.getThreads() * 2), orderedMoves.size() - 1);
        final CompletionService<MoveAndScore> ecs = new ExecutorCompletionService<>(searchPool);
        final List<Future<MoveAndScore>> futures = new ArrayList<>(Math.max(fanout, 0));
        final java.util.concurrent.locks.ReentrantLock fullResLock = new java.util.concurrent.locks.ReentrantLock();

        for (int i = 1; i <= fanout; i++) {
            final int moveInt = orderedMoves.getMove(i);
            futures.add(ecs.submit(() -> {
                if (stopRef.get() || abortRequested(deadline)) return null;
                WorkerContext helperWorker = prepareHelperHeuristics(task, depth);
                SearchContext.Heuristics helperHeuristics = helperWorker.heuristics();
                try {
                    Engine e = simulatorEngine.createSimulation();
                    e.performMove(moveInt);
                    instr.recordRootMoveExplored();

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

                    double finalScore = probe;
                    boolean needsFull = isWhitesTurn ? (probe > alphaRef.get() + 0.05) : (probe < betaRef.get() - 0.05);

                    if (needsFull && !stopRef.get()) {
                        fullResLock.lock();
                        try {
                            if (!stopRef.get() && !abortRequested(deadline)) {
                                double aNow = alphaRef.get();
                                double bNow = betaRef.get();
                                double full = alphaBeta(e, depth - 1, aNow, bNow, !isWhitesTurn, deadline, moveInt, 1, 0);
                                if (full == EXIT_FLAG) return null;
                                finalScore = full;
                            }
                        } finally {
                            fullResLock.unlock();
                        }
                    }

                    long childHash = e.getBoardStateHash();
                    applyRootScore(isWhitesTurn, moveInt, finalScore, childHash, depth - 1,
                            alphaRef, betaRef, bestMoveRef, bestScoreRef, fallbackMateRef,
                            refutedRootMoves, mateWinFound, stopRef);
                    return new MoveAndScore(moveInt, finalScore);
                } finally {
                    if (helperHeuristics.hasUpdates()) {
                        mergeThreadHeuristics(helperWorker);
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
                if (f.get() == null) continue;

                if (mateWinFound.get()) break;

                if (alphaRef.get() >= betaRef.get()) {
                    stopRef.set(true);
                    break;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warn("Parallel root YBWC error", ex);
        } finally {
            for (Future<MoveAndScore> f : futures) {
                if (!f.isDone()) f.cancel(true);
            }
        }

        alpha = alphaRef.get();
        beta = betaRef.get();
        bestMove = bestMoveRef.get();
        bestScore = bestScoreRef.get();

        if (mateWinFound.get()) {
            return new MoveAndScore(bestMoveRef.get(), bestScoreRef.get());
        }

        if (!stopRef.get()) {
            for (int idx = fanout + 1; idx < orderedMoves.size(); idx++) {
                if (abortRequested(deadline)) {
                    break;
                }
                int moveInt = orderedMoves.getMove(idx);
                if (refutedRootMoves.contains(moveInt)) continue;

                instr.recordRootMoveExplored();
                simulatorEngine.performMove(moveInt);
                long childHash = simulatorEngine.getBoardStateHash();

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

                applyRootScore(isWhitesTurn, moveInt, score, childHash, depth - 1,
                        alphaRef, betaRef, bestMoveRef, bestScoreRef, fallbackMateRef,
                        refutedRootMoves, mateWinFound, stopRef);
                simulatorEngine.undoLastMove();

                alpha = alphaRef.get();
                beta = betaRef.get();
                bestMove = bestMoveRef.get();
                bestScore = bestScoreRef.get();

                if (mateWinFound.get()) {
                    return new MoveAndScore(bestMoveRef.get(), bestScoreRef.get());
                }

                if (alpha >= beta) {
                    instr.recordRootBetaCutoff();
                    stopRef.set(true);
                    break;
                }
            }
        }

        if (bestMove != -1) {
            return new MoveAndScore(bestMove, bestScore);
        }
        MoveAndScore fallback = fallbackMateRef.get();
        return fallback != null ? fallback : null;
    }

    private void applyRootScore(boolean isWhitesTurn,
                                 int move,
                                 double score,
                                 long childHash,
                                 int depthRemaining,
                                 AtomicReference<Double> alphaRef,
                                 AtomicReference<Double> betaRef,
                                 java.util.concurrent.atomic.AtomicInteger bestMoveRef,
                                 AtomicReference<Double> bestScoreRef,
                                 AtomicReference<MoveAndScore> fallbackMateRef,
                                 Set<Integer> refutedRootMoves,
                                 AtomicBoolean mateWinFound,
                                 AtomicBoolean stopRef) {
        if (isWinningMateForUs(isWhitesTurn, score)) {
            publishExactMateToTT(childHash, score, depthRemaining);
            mateWinFound.set(true);
            bestMoveRef.set(move);
            bestScoreRef.set(score);
            if (isWhitesTurn) {
                alphaRef.updateAndGet(current -> Math.max(current, score));
            } else {
                betaRef.updateAndGet(current -> Math.min(current, score));
            }
            stopRef.set(true);
            return;
        }

        if (isLosingMateForUs(isWhitesTurn, score)) {
            publishExactMateToTT(childHash, score, depthRemaining);
            refutedRootMoves.add(move);
            updateFallbackMate(fallbackMateRef, isWhitesTurn, move, score);
            return;
        }

        Double currentBest = bestScoreRef.get();
        if (isBetterScore(isWhitesTurn, score, currentBest)) {
            bestScoreRef.set(score);
            bestMoveRef.set(move);
        }

        if (isWhitesTurn) {
            alphaRef.updateAndGet(current -> Math.max(current, score));
        } else {
            betaRef.updateAndGet(current -> Math.min(current, score));
        }

        if (alphaRef.get() >= betaRef.get()) {
            stopRef.set(true);
        }
    }

    private void updateFallbackMate(AtomicReference<MoveAndScore> fallbackRef,
                                    boolean isWhitesTurn,
                                    int move,
                                    double score) {
        MoveAndScore candidate = null;
        while (true) {
            MoveAndScore current = fallbackRef.get();
            if (current != null && !isBetterScore(isWhitesTurn, score, current.getScore())) {
                return;
            }
            if (candidate == null) {
                candidate = new MoveAndScore(move, score);
            }
            if (fallbackRef.compareAndSet(current, candidate)) {
                return;
            }
        }
    }

    private boolean isWinningMateForUs(boolean isWhitesTurn, double score) {
        double threshold = CHECKMATE - 50;
        return isWhitesTurn ? score >= threshold : score <= -threshold;
    }

    private boolean isLosingMateForUs(boolean isWhitesTurn, double score) {
        double threshold = CHECKMATE - 50;
        return isWhitesTurn ? score <= -threshold : score >= threshold;
    }

    private void publishExactMateToTT(long childHash, double score, int depthRemaining) {
        if (transpositionTable == null) {
            return;
        }
        int depthForEntry = Math.max(searchConfig.getMaxDepth(), Math.max(0, depthRemaining) + 6);
        TranspositionTableEntry existing = transpositionTable.get(childHash);
        if (existing != null
                && existing.nodeType == NodeType.EXACT
                && existing.depth >= depthForEntry
                && existing.score == score) {
            return;
        }
        transpositionTable.put(childHash,
                new TranspositionTableEntry(score, depthForEntry, NodeType.EXACT, -1), depthForEntry);
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
            MoveList legal = simulation.getAllLegalMoves();
            for (int i = 0; i < legal.size(); i++) {
                if (legal.getMove(i) == mv) return true;
            }
            return false;
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
            MoveList legal = simulation.getAllLegalMoves();
            if (legal.size() > 0) {
                int mv = legal.getMove(0);
                if (MoveHelper.isWhitesMove(mv) == simulation.whitesTurn()) {
                    seedMove = mv;
                }
            }
        }

        // If still nothing, no PV can be constructed
        if (seedMove == -1) {
            clearPrincipalVariation();
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

        updatePrincipalVariation(pv);
    }


    private MoveAndScore getBestMove(Engine simulatorEngine, boolean isWhitesTurn, int depth, long deadline,
                                     double alpha, double beta, SplittableRandom rng) {
        int bestMove = -1;
        double bestScore = isWhitesTurn ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;

        MoveList sortedMoves = sortMovesByEfficiency(simulatorEngine.getAllLegalMoves(), depth,
                simulatorEngine.getBoardStateHash(), -1, simulatorEngine);
        maybeRotateRootMoves(sortedMoves, rng);
        SearchInstrumentation instr = instrumentation();
        instr.recordRootMovesGenerated(sortedMoves.size());

        for (int idx = 0; idx < sortedMoves.size(); idx++) {
            int moveInt = sortedMoves.getMove(idx);
            if (abortRequested(deadline)) break;

            instr.recordRootMoveExplored();
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
            if (alpha >= beta) {
                instr.recordRootBetaCutoff();
                break;
            }
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
        searchContext.incrementNodesVisited();
        SearchInstrumentation instr = instrumentation();
        instr.recordVisitedPly(plyFromRoot);

        if (abortRequested(deadline)) return EXIT_FLAG;

        if (plyFromRoot >= searchConfig.getMaxDepth() + ABS_PLY_LIMIT_MARGIN) {
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
            double drawScore = evaluateStaticPosition(simulatorEngine.getGameState(), isWhite, plyFromRoot);
            return isWhite ? drawScore : -drawScore;
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
        instr.recordTranspositionLookup();
        TranspositionTableEntry entry = transpositionTable.get(boardHash);
        if (entry != null) {
            boolean usable = entry.depth >= depth;
            if (usable) {
                if (entry.nodeType == NodeType.EXACT) {
                    instr.recordTranspositionHit(entry.nodeType, true);
                    return entry.score;
                }
                if (entry.nodeType == NodeType.LOWERBOUND && entry.score > alpha) alpha = entry.score;
                else if (entry.nodeType == NodeType.UPPERBOUND && entry.score < beta) beta = entry.score;
                boolean cutoff = alpha >= beta;
                instr.recordTranspositionHit(entry.nodeType, cutoff);
                if (cutoff) return entry.score;
            } else {
                instr.recordTranspositionHit(entry.nodeType, false);
            }
        }

        // -------- Safer Null-move pruning (same as before, but use depthHere) --------
        MoveList moves = simulatorEngine.getAllLegalMoves();
        int mobility = moves.size();
        BitBoard bitBoard = simulatorEngine.getBitBoard();
        boolean allowNullMove = searchConfig.isNullMovePruningEnabled()
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
            searchContext.incrementNullMoveCount();
            instr.recordNullMoveAttempt();
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
                    instr.recordNullMoveVerification();
                    double verificationScore = alphaBeta(simulatorEngine, depth - 1, alpha, beta, isWhite, deadline, prevMove, plyFromRoot, 0);
                    if (verificationScore == EXIT_FLAG) return EXIT_FLAG;
                    if (Math.abs(verificationScore) < mateThreshold) {
                        nullFailHigh = isWhite ? verificationScore >= beta : verificationScore <= alpha;
                    } else {
                        nullFailHigh = false;
                    }
                    if (!nullFailHigh) {
                        instr.recordNullMoveVerificationFail();
                    }
                }
            }

            if (nullFailHigh) {
                instr.recordNullMovePrune();
                return isWhite ? beta : alpha;
            }
        }
        // ---------------------------------------------------------------------------

        double alphaOriginal = alpha;
        double betaOriginal = beta;

        if (isWhite) {
            return maximizer(simulatorEngine, depth, alpha, beta, boardHash, alphaOriginal, moves, deadline, prevMove, plyFromRoot, extStreak);
        } else {
            return minimizer(simulatorEngine, depth, alpha, beta, boardHash, betaOriginal, moves, deadline, prevMove, plyFromRoot, extStreak);
        }
    }


    private boolean isSideInCheck(Engine engine, boolean isWhite) {
        GameStateEnum state = engine.getGameState().getState();
        if (isWhite) {
            if (state == GameStateEnum.WHITE_IN_CHECK || state == GameStateEnum.BLACK_WON) {
                return true;
            }
        } else {
            if (state == GameStateEnum.BLACK_IN_CHECK || state == GameStateEnum.WHITE_WON) {
                return true;
            }
        }
        return engine.getBitBoard().isInCheck(isWhite);
    }

    private boolean attacksOpponentQueenNow(Engine e, boolean moverIsWhite) {
        BitBoard bb = e.getBitBoard();
        long enemyQueen = moverIsWhite ? bb.getBlackQueens() : bb.getWhiteQueens();
        if (enemyQueen == 0) return false;
        long myAttacks = bb.getAttackBitboard(moverIsWhite);
        return (myAttacks & enemyQueen) != 0L;
    }

    private boolean attacksOpponentRookNow(Engine e, boolean moverIsWhite) {
        BitBoard bb = e.getBitBoard();
        long enemyRooks = moverIsWhite ? bb.getBlackRooks() : bb.getWhiteRooks();
        if (enemyRooks == 0L) {
            return false;
        }
        long myAttacks = bb.getAttackBitboard(moverIsWhite);
        return (myAttacks & enemyRooks) != 0L;
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

    private static double getReductionEstimate(int depth, int mobility, int nonPawnMaterial) {
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

    /**
     * LMR reduction: larger for deeper plies and later moves; tuned to be safe.
     */
    private static final int LMR_HISTORY_MAX = 4000;

    // Tuned to give you ~1–3 plies reduction for late, quiet moves at depth 6–12.
    private int lmrReduction(int depth, int moveIndex, int historyScore) {
        if (depth <= 2) return 0;

        // Base from depth and lateness
        double d = Math.log1p(depth);             // 1.. ~2.6
        double m = Math.log1p(moveIndex + 1);     // 0.. ~5.0 for very late moves
        double base = 0.35 * d * m;               // gentle scale

        // History cuts reduction (good history => reduce less)
        int h = Math.max(0, Math.min(historyScore, LMR_HISTORY_MAX));
        double hist = (LMR_HISTORY_MAX == 0) ? 0.0 : (double) h / LMR_HISTORY_MAX;
        double penalty = 0.8 * hist;              // up to -0.8 ply

        int r = (int) Math.floor(base - penalty);

        if (r < 1) r = 1;
        if (r > depth - 1) r = depth - 1;
        return r;
    }

    private double computeStandPatMargin(BitBoard board, int depthRemaining, int nextDepth) {
        long whiteNonPawns = board.getWhitePieces() & ~board.getWhitePawns();
        long blackNonPawns = board.getBlackPieces() & ~board.getBlackPawns();
        int nonPawnCount = Long.bitCount(whiteNonPawns) + Long.bitCount(blackNonPawns);

        double materialFactor = Math.min(1.0, nonPawnCount / 16.0);
        int depthForMargin = Math.max(1, Math.min(4, Math.max(depthRemaining, nextDepth)));

        double base = 60.0;
        double depthBonus = depthForMargin * 35.0;
        double materialBonus = materialFactor * 70.0;
        return base + depthBonus + materialBonus;
    }

    private double maximizer(Engine simulatorEngine, int depth, double alpha, double beta,
                             long boardHash, double alphaOriginal,
                             MoveList moves, long deadline, int prevMove, int plyFromRoot,
                             int extStreak) {

        double maxEval = Double.NEGATIVE_INFINITY;
        int bestMoveAtThisNode = -1;
        SearchInstrumentation instr = instrumentation();

        final boolean inCheckAtNode = isSideInCheck(simulatorEngine, true);
        final WorkerContext worker = searchContext.worker();
        final SearchContext.Heuristics heuristics = worker.heuristics();
        final int[][] historyTable = heuristics.history;

        MoveList orderedMoves = sortMovesByEfficiency(moves, depth, boardHash, prevMove, simulatorEngine);
        final Map<Integer, Integer> seeCache = worker.seeCache();
        seeCache.clear();
        for (int index = 0; index < orderedMoves.size(); index++) {
            if (abortRequested(deadline)) {
                return EXIT_FLAG;
            }
            int move = orderedMoves.getMove(index);

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
            boolean lmpPrecomputed = false;
            boolean lmpGivesCheck = false;
            boolean lmpAttacksQueen = false;
            boolean lmpAttacksKingZone = false;
            int lmpThreshold = 8 + depth * 2;
            if (!inCheckAtNode && !isTactical && depth <= 3 && index > lmpThreshold) {
                simulatorEngine.performMove(move);
                lmpGivesCheck = isSideInCheck(simulatorEngine, false);
                lmpAttacksQueen = attacksOpponentQueenNow(simulatorEngine, true);
                lmpAttacksKingZone = attacksOpponentKingZone(simulatorEngine, true);
                lmpPrecomputed = true;
                boolean skipQuiet = isQuiet
                        && !lmpGivesCheck
                        && !lmpAttacksQueen
                        && !lmpAttacksKingZone
                        && !seeWinsMaterial;
                simulatorEngine.undoLastMove();
                if (skipQuiet) {
                    instr.recordLateMovePrune();
                    continue;
                }
            }

            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();

            boolean givesCheck = lmpPrecomputed ? lmpGivesCheck : isSideInCheck(simulatorEngine, false);
            boolean attacksQueen = lmpPrecomputed ? lmpAttacksQueen : attacksOpponentQueenNow(simulatorEngine, true);
            boolean attacksHeavyPiece = attacksQueen || attacksOpponentRookNow(simulatorEngine, true);
            boolean attacksKingZone = lmpPrecomputed ? lmpAttacksKingZone : attacksOpponentKingZone(simulatorEngine, true);
            boolean opensKingFile = openedFileTowardKing(simulatorEngine.getBitBoard(), kingFileMask, pawnsOnFileBefore, affectsKingFilePawns);

            int nextDepth = depth - 1;
            boolean forcing = givesCheck || attacksQueen;
            boolean allowExtend = forcing && extStreak < MAX_CHECK_EXTENSIONS_IN_A_ROW;
            if (allowExtend) nextDepth++;
            int nextExtStreak = allowExtend ? extStreak + 1 : 0;

            boolean allowStandPatPrune = !inCheckAtNode
                    && isQuiet
                    && depth <= 2
                    && nextDepth <= 2
                    && !givesCheck
                    && !attacksHeavyPiece
                    && !seeWinsMaterial;

            if (allowStandPatPrune) {
                double staticEval = evaluateStaticPosition(simulatorEngine.getGameState(), false, depth);
                staticEval = -staticEval;
                double margin = computeStandPatMargin(simulatorEngine.getBitBoard(), depth, nextDepth);
                if (staticEval + margin <= alpha) {
                    simulatorEngine.undoLastMove();
                    instr.recordFutilityPrune();
                    continue;
                }
            }

            double eval;
            instr.recordTranspositionLookup();
            TranspositionTableEntry entry = transpositionTable.get(newBoardHash);
            boolean ttExactHit = entry != null
                    && entry.nodeType == NodeType.EXACT
                    && entry.depth >= nextDepth;

            if (ttExactHit) {
                instr.recordTranspositionHit(entry.nodeType, false);
                eval = entry.score;
            } else {
                if (entry != null) {
                    instr.recordTranspositionHit(entry.nodeType, false);
                }
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
                double pBeta = usePvs ? (alpha + 1) : beta;

                int reduction = 0;
                if (canReduce) {
                    reduction = lmrReduction(nextDepth, index, historyScore);
                    if (reduction <= 0) canReduce = false;
                    else instr.recordLateMoveReduction(reduction);
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

            simulatorEngine.undoLastMove();

            if (eval > maxEval) {
                maxEval = eval;
                bestMoveAtThisNode = move;
            }

            alpha = Math.max(alpha, eval);
            if (beta <= alpha) {
                updateKillerMoves(depth, move);
                incrementHistory(prevMove, move, depth);
                heuristics.recordCounterMove(prevMove, move);
                instr.recordBetaCutoff();
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
                             long boardHash, double betaOriginal,
                             MoveList moves, long deadline, int prevMove, int plyFromRoot,
                             int extStreak) {

        double minEval = Double.POSITIVE_INFINITY;
        int bestMoveAtThisNode = -1;
        SearchInstrumentation instr = instrumentation();

        final boolean inCheckAtNode = isSideInCheck(simulatorEngine, false);
        final WorkerContext worker = searchContext.worker();
        final SearchContext.Heuristics heuristics = worker.heuristics();
        final int[][] historyTable = heuristics.history;

        MoveList orderedMoves = sortMovesByEfficiency(moves, depth, boardHash, prevMove, simulatorEngine);
        final Map<Integer, Integer> seeCache = worker.seeCache();
        seeCache.clear();
        for (int index = 0; index < orderedMoves.size(); index++) {
            if (Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
                return EXIT_FLAG;
            }

            int move = orderedMoves.getMove(index);

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
            boolean lmpPrecomputed = false;
            boolean lmpGivesCheck = false;
            boolean lmpAttacksQueen = false;
            boolean lmpAttacksKingZone = false;
            int lmpThreshold = 8 + depth * 2;
            if (!inCheckAtNode && !isTactical && depth <= 3 && index > lmpThreshold) {
                simulatorEngine.performMove(move);
                lmpGivesCheck = isSideInCheck(simulatorEngine, true);
                lmpAttacksQueen = attacksOpponentQueenNow(simulatorEngine, false);
                lmpAttacksKingZone = attacksOpponentKingZone(simulatorEngine, false);
                lmpPrecomputed = true;
                boolean skipQuiet = isQuiet
                        && !lmpGivesCheck
                        && !lmpAttacksQueen
                        && !lmpAttacksKingZone
                        && !seeWinsMaterial;
                simulatorEngine.undoLastMove();
                if (skipQuiet) {
                    instr.recordLateMovePrune();
                    continue;
                }
            }

            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();

            boolean givesCheck = lmpPrecomputed ? lmpGivesCheck : isSideInCheck(simulatorEngine, true);
            boolean attacksQueen = lmpPrecomputed ? lmpAttacksQueen : attacksOpponentQueenNow(simulatorEngine, false);
            boolean attacksHeavyPiece = attacksQueen || attacksOpponentRookNow(simulatorEngine, false);
            boolean attacksKingZone = lmpPrecomputed ? lmpAttacksKingZone : attacksOpponentKingZone(simulatorEngine, false);
            boolean opensKingFile = openedFileTowardKing(simulatorEngine.getBitBoard(), kingFileMask, pawnsOnFileBefore, affectsKingFilePawns);

            int nextDepth = depth - 1;
            boolean forcing = givesCheck || attacksQueen;
            boolean allowExtend = forcing && extStreak < MAX_CHECK_EXTENSIONS_IN_A_ROW;
            if (allowExtend) nextDepth++;
            int nextExtStreak = allowExtend ? extStreak + 1 : 0;

            boolean allowStandPatPrune = !inCheckAtNode
                    && isQuiet
                    && depth <= 2
                    && nextDepth <= 2
                    && !givesCheck
                    && !attacksHeavyPiece
                    && !seeWinsMaterial;

            if (allowStandPatPrune) {
                double staticEval = evaluateStaticPosition(simulatorEngine.getGameState(), true, depth);
                double margin = computeStandPatMargin(simulatorEngine.getBitBoard(), depth, nextDepth);
                if (staticEval - margin >= beta) {
                    simulatorEngine.undoLastMove();
                    instr.recordFutilityPrune();
                    continue;
                }
            }

            double eval;
            instr.recordTranspositionLookup();
            TranspositionTableEntry entry = transpositionTable.get(newBoardHash);
            boolean ttExactHit = entry != null
                    && entry.nodeType == NodeType.EXACT
                    && entry.depth >= nextDepth;

            if (ttExactHit) {
                instr.recordTranspositionHit(entry.nodeType, false);
                eval = entry.score;
            } else {
                if (entry != null) {
                    instr.recordTranspositionHit(entry.nodeType, false);
                }
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

                int reduction = 0;
                if (canReduce) {
                    reduction = lmrReduction(nextDepth, index, historyScore);
                    if (reduction <= 0) canReduce = false;
                    else instr.recordLateMoveReduction(reduction);
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

            simulatorEngine.undoLastMove();

            if (eval < minEval) {
                minEval = eval;
                bestMoveAtThisNode = move;
            }

            beta = Math.min(beta, eval);
            if (alpha >= beta) {
                updateKillerMoves(depth, move);
                incrementHistory(prevMove, move, depth);
                heuristics.recordCounterMove(prevMove, move);
                instr.recordBetaCutoff();
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
    MoveList sortMovesByEfficiency(MoveList moves, int currentDepth, long boardHash, int prevMove,
                                   Engine simulatorEngine) {
        WorkerContext worker = searchContext.worker();
        final int[] seeKeys = worker.seeKeys();
        final int[] seeVals = worker.seeVals();
        final int[] seeGenerations = worker.seeGenerations();
        final int[] generationHolder = worker.seeGenerationCounter();
        int generation = generationHolder[0] + 1;
        if (generation == 0) {
            Arrays.fill(seeGenerations, 0);
            generation = 1;
        }
        generationHolder[0] = generation;
        final int size = moves.size();
        final Map<Integer, Integer> seeCache = worker.seeCache();
        seeCache.clear();

        if (size == 0) {
            return moves;
        }

        final int[] moveBuffer = worker.moveBuffer();
        final int[] scoreBuffer = worker.scoreBuffer();
        final long[] sortKeys = worker.sortKeyBuffer();

        final SearchContext.Heuristics heuristics = worker.heuristics();
        final int[][] killerMoves = heuristics.killers;
        final int[][] historyTable = heuristics.history;
        final int[][] counterMove = heuristics.counter;

        final int depthIndex = Math.max(0, Math.min(currentDepth, killerMoves.length - 1));

        // Category encoding (higher is earlier):
        // 8: TT move, 7: promotions, 6: good captures, 5: checking quiets,
        // 4: equal captures, 3: killer[0], 2: killer[1], 1: quiets (history), 0: bad captures
        final int CAT_TT = 8, CAT_PROMO = 7, CAT_CAP_GOOD = 6, CAT_CHECK_QUIET = 5,
                CAT_CAP_EQUAL = 4, CAT_KILLER0 = 3, CAT_KILLER1 = 2, CAT_QUIET = 1, CAT_CAP_BAD = 0;

        // Lightweight bonuses (local so no class changes):
        final int PROMOTION_ORDER_BONUS = 900;   // strong push for promotions
        final int KILLER0_BONUS = 50;            // distinguish first vs second killer
        final int KILLER1_BONUS = 30;
        final int CHECK_CAPTURE_BONUS = 180;
        final int CHECK_QUIET_BONUS = 600;
        final int[] CHECK_PRIORITY_BY_PIECE = {
                0,   // unused (0)
                120, // pawn checks
                200, // knight checks
                260, // bishop checks
                340, // rook checks
                420, // queen checks
                160  // king checks
        };

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
        final int CONTINUATION_HISTORY_DIVISOR = 8;

        final BitBoard boardSnapshot = simulatorEngine.getBitBoard();

        for (int i = 0; i < size; i++) {
            final int moveInt = moves.getMove(i);

            // Track TT move position for the "pin to front" trick
            if (moveInt == ttMove) {
                ttIndex = i;
            }

            // Compute base features
            final boolean isCapture = MoveHelper.isCapture(moveInt);
            final boolean isPromotion = MoveHelper.isPawnPromotionMove(moveInt);
            final int from = moveInt & 0x3F;
            final int to = (moveInt >>> 6) & 0x3F;
            final boolean givesCheck = moveGivesCheck(boardSnapshot, moveInt);

            int seeValue = 0;
            boolean hasSee = false;
            if (isCapture) {
                int slot = (int) (Integer.toUnsignedLong(moveInt) * 2654435761L);
                slot &= SearchContext.SEE_CACHE_MASK;
                if (slot == 0) {
                    slot = 1;
                }
                if (seeGenerations[slot] == generation && seeKeys[slot] == moveInt) {
                    seeValue = seeVals[slot];
                } else {
                    seeValue = simulatorEngine.see(moveInt);
                    seeKeys[slot] = moveInt;
                    seeVals[slot] = seeValue;
                    seeGenerations[slot] = generation;
                }
                hasSee = true;
            }
            int category;
            int score;
            int checkPriorityBonus = 0;
            if (givesCheck) {
                int checkingPieceBits = MoveHelper.derivePromotionPieceTypeBits(moveInt);
                if (checkingPieceBits == 0) {
                    checkingPieceBits = MoveHelper.derivePieceTypeBits(moveInt);
                    if (checkingPieceBits == 6 && MoveHelper.isCastlingMove(moveInt)) {
                        checkingPieceBits = 4; // castling checks are delivered by the rook
                    }
                }
                if (checkingPieceBits < 0 || checkingPieceBits >= CHECK_PRIORITY_BY_PIECE.length) {
                    checkingPieceBits = 0;
                }
                checkPriorityBonus = CHECK_PRIORITY_BY_PIECE[checkingPieceBits];
            }

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
                if (givesCheck) {
                    int baseCheckBonus = isCapture ? CHECK_CAPTURE_BONUS : CHECK_QUIET_BONUS;
                    score += baseCheckBonus + checkPriorityBonus;
                }
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
                if (givesCheck) {
                    score += CHECK_CAPTURE_BONUS + checkPriorityBonus;
                }
            } else if (givesCheck) {
                category = CAT_CHECK_QUIET;
                score = historyTable[from][to] + CHECK_QUIET_BONUS + checkPriorityBonus;
                if (prevTo >= 0) {
                    int continuationScore = heuristics.continuation[prevTo][to];
                    score += continuationScore / CONTINUATION_HISTORY_DIVISOR;
                }
                if (moveInt == cm) {
                    score += COUNTER_MOVE_BONUS / 2;
                }
            } else if (moveInt == k0) {
                category = CAT_KILLER0;
                score = historyTable[from][to] + KILLER0_BONUS;
            } else if (moveInt == k1) {
                category = CAT_KILLER1;
                score = historyTable[from][to] + KILLER1_BONUS;
            } else {
                // Quiet with history
                category = CAT_QUIET;
                score = historyTable[from][to]; // butterfly history
                if (prevTo >= 0) {
                    int continuationScore = heuristics.continuation[prevTo][to];
                    score += continuationScore / CONTINUATION_HISTORY_DIVISOR;
                }
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
        long ttSortKey;
        int pinnedTtMove = ttMove;
        int sortStart = 0;
        if (ttIndex >= 0) {
            ttSortKey = sortKeys[ttIndex];
            pinnedTtMove = moveBuffer[ttIndex];
            if (ttIndex > 0) {
                System.arraycopy(sortKeys, 0, sortKeys, 1, ttIndex);
            }
            sortKeys[0] = ttSortKey;
            sortStart = 1;
        }

        Arrays.sort(sortKeys, sortStart, size); // ascending by key

        // Build result in descending order (bigger category/score first)
        int outIndex = 0;
        if (ttIndex != -1) {
            moveBuffer[outIndex++] = pinnedTtMove;
            for (int i = size - 1; i >= 1; i--) {
                moveBuffer[outIndex++] = (int) (sortKeys[i] & 0xFFFFFFFFL);
            }
        } else {
            for (int i = size - 1; i >= 0; i--) {
                moveBuffer[outIndex++] = (int) (sortKeys[i] & 0xFFFFFFFFL);
            }
        }

        for (int i = 0; i < size; i++) {
            moves.setMove(i, moveBuffer[i]);
        }

        return moves;
    }

    private boolean moveGivesCheck(BitBoard board, int move) {
        long enemyKing = MoveHelper.isWhitesMove(move) ? board.getBlackKing() : board.getWhiteKing();
        if (enemyKing == 0L) {
            return false;
        }
        int kingSquare = Long.numberOfTrailingZeros(enemyKing);
        int from = MoveHelper.deriveFromIndex(move);
        int to = MoveHelper.deriveToIndex(move);
        int pieceBits = MoveHelper.derivePieceTypeBits(move);
        int promotionBits = MoveHelper.derivePromotionPieceTypeBits(move);
        if (promotionBits != 0) {
            pieceBits = promotionBits;
        }

        boolean whiteMove = MoveHelper.isWhitesMove(move);
        long occupancy = board.getAllPieces();
        long fromMask = 1L << from;
        long toMask = 1L << to;
        occupancy &= ~fromMask;
        occupancy &= ~toMask;
        occupancy |= toMask;

        if (MoveHelper.isEnPassantMove(move)) {
            int capturedPawnSquare = whiteMove ? (to - 8) : (to + 8);
            if (capturedPawnSquare >= 0 && capturedPawnSquare < 64) {
                occupancy &= ~(1L << capturedPawnSquare);
            }
        }

        int rookTo = -1;
        if (MoveHelper.isCastlingMove(move)) {
            boolean kingSide = to > from;
            int rookFrom;
            if (whiteMove) {
                if (kingSide) {
                    rookFrom = 7;
                    rookTo = 5;
                } else {
                    rookFrom = 0;
                    rookTo = 3;
                }
            } else {
                if (kingSide) {
                    rookFrom = 63;
                    rookTo = 61;
                } else {
                    rookFrom = 56;
                    rookTo = 59;
                }
            }
            occupancy &= ~(1L << rookFrom);
            occupancy |= 1L << rookTo;
        }

        if (pieceAttacksSquare(pieceBits, to, occupancy, whiteMove, kingSquare)) {
            return true;
        }

        if (rookTo != -1) {
            long rookAttacks = ROOK_HELPER.calculateRookMoves(rookTo, occupancy);
            long kingMask = 1L << kingSquare;
            if ((rookAttacks & kingMask) != 0) {
                return true;
            }
        }

        return false;
    }

    private boolean pieceAttacksSquare(int pieceBits, int square, long occupancy,
                                       boolean whiteMove, int targetSquare) {
        long targetMask = 1L << targetSquare;
        return switch (pieceBits) {
            case 1 -> (PawnMoveTables.PAWN_ATTACKS[whiteMove ? 0 : 1][square] & targetMask) != 0;
            case 2 -> (knightMoveTable[square] & targetMask) != 0;
            case 3 -> (BISHOP_HELPER.calculateBishopMoves(square, occupancy) & targetMask) != 0;
            case 4 -> (ROOK_HELPER.calculateRookMoves(square, occupancy) & targetMask) != 0;
            case 5 -> {
                long attacks = BISHOP_HELPER.calculateBishopMoves(square, occupancy)
                        | ROOK_HELPER.calculateRookMoves(square, occupancy);
                yield (attacks & targetMask) != 0;
            }
            case 6 -> (KING_ATTACKS[square] & targetMask) != 0;
            default -> false;
        };
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
        if (abortRequested(deadline)) return AI.EXIT_FLAG;

        boolean inCheck = isSideInCheck(simulatorEngine, isWhitesTurn);
        SearchInstrumentation instr = instrumentation();
        instr.recordQuiescenceNode(depth);

        double standPat = evaluateStaticPosition(simulatorEngine.getGameState(), isWhitesTurn, depth);

        if (!inCheck) {
            if (standPat >= beta) {
                instr.recordQuiescenceStandPatCut();
                return beta;
            }
            if (alpha < standPat) alpha = standPat;

            // Slightly stronger delta guard
            final int BIG_DELTA = 900; // ≈ queen, was 1000
            if (standPat + BIG_DELTA <= alpha) {
                instr.recordQuiescenceDeltaPrune();
                return alpha;
            }
        }

        MoveList moves = inCheck ? simulatorEngine.getAllLegalMoves()
                : getPossibleCapturesOrPromotions(simulatorEngine);

        MoveList ordered = sortMovesByEfficiency(moves, 0,
                simulatorEngine.getBoardStateHash(), -1, simulatorEngine);

        for (int i = 0; i < ordered.size(); i++) {
            int m = ordered.getMove(i);
            boolean isCapture = MoveHelper.isCapture(m);
            boolean isPromotion = MoveHelper.isPawnPromotionMove(m);
            boolean isQuiet = !isCapture && !isPromotion;

            // Skip quiet stuff in qsearch unless in check (evasions)
            if (!inCheck && isQuiet) continue;

            // Skip non-capture promotions when not in check; too noisy here
            if (!inCheck && isPromotion && !isCapture) continue;

            // SEE prune losing captures/quiets unless they give check
            if (!inCheck && !isPromotion || isQuiet) {
                int see = simulatorEngine.see(m);
                if (see < 0) {
                    simulatorEngine.performMove(m);
                    boolean givesCheck = isSideInCheck(simulatorEngine, !isWhitesTurn);
                    simulatorEngine.undoLastMove();
                    if (!givesCheck) {
                        instr.recordQuiescenceSeePrune();
                        continue;
                    }
                }
            }

            simulatorEngine.performMove(m);
            instr.recordQuiescenceCaptureSearched();

            double child = quiescenceSearch(simulatorEngine, !isWhitesTurn, -beta, -alpha, deadline, depth + 1);
            simulatorEngine.undoLastMove();
            if (child == EXIT_FLAG) return EXIT_FLAG;

            double score = -child;
            if (score >= beta) return beta;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }


    private double evaluateStaticPosition(GameState gameState, boolean isWhitesTurn, int depthOrPly) {
        instrumentation().recordStaticEvalCall();

        if (gameState.isInStateCheckMate()) {
            return -(CHECKMATE - depthOrPly);
        }
        if (gameState.isInStateDraw()) {
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

        return isWhitesTurn ? scoreDifference : -scoreDifference;
    }

    private MoveList getPossibleCapturesOrPromotions(Engine simulatorEngine) {
        MoveList allLegalMoves = simulatorEngine.getAllLegalMoves();
        MoveList capturesAndPromotions = new MoveList();
        for (int i = 0; i < allLegalMoves.size(); i++) {
            int m = allLegalMoves.getMove(i);
            if (MoveHelper.isCapture(m) || MoveHelper.isPawnPromotionMove(m)) {
                capturesAndPromotions.add(m);
            }
        }

        return capturesAndPromotions;
    }

    private synchronized boolean positionChanged() {
        return currentBoardState != beforeCalculationBoardState;
    }

    /**
     * Checks if the current score is better than the best score based on the player's color.
     */
    private boolean isBetterScore(boolean isWhite, double score, double bestScore) {
        return isWhite ? score > bestScore : score < bestScore;
    }

    public void updateBoardStateHash() {
        currentBoardState = mainEngine.getBoardStateHash();
        synchronized (calculationLock) {
            calculationLock.notifyAll();
        }
    }

    private void updateKillerMoves(int depth, int move) {
        searchContext.worker().heuristics().recordKiller(depth, move);
    }

    private void incrementHistory(int prevMove, int move, int depth) {
        SearchContext.Heuristics heuristics = searchContext.worker().heuristics();
        heuristics.addHistory(move, depth);
        heuristics.addContinuation(prevMove, move, depth);
    }

    private void clearHistoryTable() {
        searchContext.clearHistoryTables();
    }

    int[][] snapshotKillerMoves() {
        return searchContext.snapshotKillers();
    }

    private int calculateMvvLvaScore(int move) {
        if (!MoveHelper.isCapture(move)) {
            return 0; // Not a capture move
        }
        int victimValue = Score.getPieceValue(MoveHelper.deriveCapturedPieceTypeBits(move));
        int attackerValue = Score.getPieceValue(MoveHelper.derivePieceTypeBits(move));
        return victimValue - attackerValue;
    }


}
