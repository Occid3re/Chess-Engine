package julius.game.chessengine.ai;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.Move;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.utils.Score;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

import static julius.game.chessengine.helper.BitHelper.FileMasks;
import static julius.game.chessengine.helper.KingHelper.KING_ATTACKS;
import static julius.game.chessengine.utils.Score.*;

@Log4j2
@Component
public class AI {

    @Getter
    private final Engine mainEngine;

    /**
     * Number of threads used for searching. Defaults to single-threaded search but
     * can be adjusted at runtime via the UCI "Threads" option.
     */
    @Getter
    @Setter
    private int searchThreads = Integer.getInteger("chessengine.searchThreads", 1);

    /**
     * Requested size of the transposition table in megabytes. The current
     * implementation does not dynamically resize the table, but the value is
     * tracked so that future improvements can honour it.
     */
    @Getter
    @Setter
    private int hashSizeMb = 16;

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
     * Maximum number of entries kept in the main transposition table.
     * This prevents unbounded memory growth during long search sessions.
     */
    private static final int TRANSPOSITION_TABLE_MAX_ENTRIES = 1_000_000;

    /**
     * Maximum number of entries kept in the capture transposition table.
     */
    private static final int CAPTURE_TRANSPOSITION_TABLE_MAX_ENTRIES = 500_000;

    /**
     * Fixed-size transposition table. Uses a non-atomic implementation when running
     * with a single search thread to avoid the overhead of atomic operations.
     */
    private final TranspositionTable<TranspositionTableEntry> transpositionTable;

    /**
     * Separate table for capture searches using the same fixed-size structure.
     */
    private final TranspositionTable<CaptureTranspositionTableEntry> captureTranspositionTable;

    private final int[][] killerMoves; // 2D array for killer moves, initialized in the constructor
    private final int numKillerMoves = 2;

    /**
     * History heuristic table. Indexed by from and to square (0-63), it stores a
     * score indicating how often a quiet move has caused a beta cutoff. Higher
     * scores mean the move should be ordered earlier in the move list.
     */
    private final int[][] historyTable; // [from][to]
    private final int[][] counterMove; // [prevFrom][prevTo] -> reply move

    // Buffers used for move ordering. Reused across calls to avoid repeated
    // allocations when ordering moves.
    private static final int MAX_MOVE_LIST_SIZE = 218; // maximum legal moves
    private final int[] moveBuffer = new int[MAX_MOVE_LIST_SIZE];
    private final int[] scoreBuffer = new int[MAX_MOVE_LIST_SIZE];
    private final long[] sortBuffer = new long[MAX_MOVE_LIST_SIZE];

    private ScheduledExecutorService scheduler;
    private Thread calculationThread;

    private final Object calculationLock = new Object();

    private volatile boolean keepCalculating = true;

    private volatile long currentBoardState = -1;
    private volatile long beforeCalculationBoardState = -2;

    private volatile int currentBestMove = -1;

    private volatile long bestMoveForHash = -1;

    @Getter
    private List<MoveAndScore> calculatedLine = Collections.synchronizedList(new ArrayList<>());

    // Game configuration parameters

    @Getter
    private int maxDepth = 18; // Adjust the level of depth according to your requirements

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

    /**
     * Ply hint for distance-to-mate normalization (set per ID iteration).
     */
    private int rootDepthForMateScoring = 0;

    public AI(Engine mainEngine) {
        this.mainEngine = mainEngine;
        this.timeLimit = 50;

        // Initialize killer moves etc...
        this.killerMoves = new int[maxDepth][numKillerMoves];
        for (int i = 0; i < maxDepth; i++) {
            for (int j = 0; j < numKillerMoves; j++) {
                killerMoves[i][j] = -1;
            }
        }
        this.historyTable = new int[64][64];
        this.counterMove = new int[64][64];
        for (int f = 0; f < 64; f++) Arrays.fill(counterMove[f], -1);

        // Initialize transposition tables based on the configured thread count
        this.transpositionTable = searchThreads == 1
                ? new PlainFixedSizeTranspositionTable<>(TRANSPOSITION_TABLE_MAX_ENTRIES, TranspositionTableEntry.class)
                : new FixedSizeTranspositionTable<>(TRANSPOSITION_TABLE_MAX_ENTRIES);
        this.captureTranspositionTable = searchThreads == 1
                ? new PlainFixedSizeTranspositionTable<>(CAPTURE_TRANSPOSITION_TABLE_MAX_ENTRIES, CaptureTranspositionTableEntry.class)
                : new FixedSizeTranspositionTable<>(CAPTURE_TRANSPOSITION_TABLE_MAX_ENTRIES);

        // create a fixed-size pool only when useful
        this.searchPool = searchThreads > 1
                ? Executors.newFixedThreadPool(searchThreads, r -> {
            Thread t = new Thread(r, "AI-Search-" + System.identityHashCode(r));
            t.setDaemon(true);
            return t;
        })
                : null;

        this.mainEngine.setOnPositionChanged(h -> updateBoardStateHash());
    }

    /**
     * Override the maximum depth for iterative deepening. Depth values greater than
     * the preallocated tables are clamped.
     */
    public void setMaxDepth(int depth) {
        this.maxDepth = Math.max(1, Math.min(depth, killerMoves.length));
    }


    public Integer getCurrentBestMoveInt() {
        return currentBestMove;
    }

    private void startCalculationThread() {
        keepCalculating = true;
        calculationThread = new Thread(this::calculateLine);
        calculationThread.setName("Simulator");
        calculationThread.start();
    }

    public void reset() {
        stopCalculation();
        currentBestMove = -1;
        currentBoardState = -1;
        beforeCalculationBoardState = -2;
        calculatedLine = Collections.synchronizedList(new ArrayList<>());
        mainEngine.startNewGame();
        clearHistoryTable();
    }

    public void stopCalculation() {
        keepCalculating = false;
        if (calculationThread != null && calculationThread.isAlive()) {
            calculationThread.interrupt();
            try {
                calculationThread.join(); // Wait for the thread to finish
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Preserve interrupt status
                log.error("Thread interruption error", e);
            }
        }
        calculatedLine = Collections.synchronizedList(new ArrayList<>());
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
                if (currentBestMove != -1 && bestMoveForHash == mainEngine.getBoardStateHash()) {
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
            synchronized (calculationLock) {
                calculationLock.notifyAll();
            } // <-- wake recalculation
            return;
        }

        if (MoveHelper.isWhitesMove(currentBestMove) != mainEngine.whitesTurn()) {
            log.info("Best move {} not for side to move", Move.convertIntToMove(currentBestMove));
            currentBestMove = -1;                           // (optional) also drop here
            return;
        }

        mainEngine.performMove(currentBestMove);
        currentBoardState = mainEngine.getBoardStateHash();
        synchronized (calculationLock) {
            calculationLock.notifyAll();
        }
        currentBestMove = -1; // don’t re-play it
    }


    private void calculateLine() {
        log.debug("keepCalculating: {}, interrupted: {}", keepCalculating, Thread.currentThread().isInterrupted());
        while (keepCalculating && !Thread.currentThread().isInterrupted()) {
            synchronized (calculationLock) {
                while (!positionChanged() && keepCalculating && !Thread.currentThread().isInterrupted()) {
                    try {
                        calculationLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            if (!keepCalculating || Thread.currentThread().isInterrupted()) {
                break;
            }
            do {
                currentBoardState = mainEngine.getBoardStateHash();
                beforeCalculationBoardState = currentBoardState;
                performCalculation();
            } while (!positionChanged() && keepCalculating && !Thread.currentThread().isInterrupted());
        }
    }

    private void performCalculation() {
        log.debug(" --- TranspositionTable[{}] --- ", transpositionTable.size());
        decayHistoryTable();
        try {
            Engine simulatorEngine = mainEngine.createSimulation();
            long boardStateHash = simulatorEngine.getBoardStateHash();
            log.debug("boardStateBeforeCalculation {}, currentBoardState {}", beforeCalculationBoardState, currentBoardState);

            // Perform calculation only if the board state has actually changed
            boolean isWhite = simulatorEngine.whitesTurn();
            long deadline = System.nanoTime() + timeLimit * 1_000_000;
            calculateBestMove(simulatorEngine, boardStateHash, isWhite, deadline);
        } catch (IllegalStateException e) {
            log.warn("Illegal board state during search: {}", e.getMessage());
            MoveList legalMoves = mainEngine.getAllLegalMoves();
            if (legalMoves.size() > 0) {
                currentBestMove = legalMoves.getMove(0);
            } else {
                currentBestMove = -1;
            }
        } catch (Exception e) {
            log.error("Unexpected error during calculation", e);
        }

    }


    private void calculateBestMove(Engine simulatorEngine, long boardStateHash, boolean isWhite, long deadline) {
        double bestScore = isWhite ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        int bestMove = mainEngine.getOpeningBook().getRandomMoveForBoardStateHash(boardStateHash); // if none: -1
        if (bestMove != -1) {
            currentBestMove = bestMove;
            return;
        }

        // For aspiration windows we center around the previous iteration’s score.
        // Start neutral; quickly stabilizes after depth 2.
        Double lastIterScore = null;

        try {
            for (int currentDepth = 1; currentDepth <= maxDepth; currentDepth++) {
                if (shouldStopCalculating(deadline)) break;

                // Set ply hint for consistent mate distance normalization
                rootDepthForMateScoring = currentDepth;

                MoveAndScore moveAndScore = null;

                // --- Aspiration window around lastIterScore (if known) ---
                double alpha = Double.NEGATIVE_INFINITY, beta = Double.POSITIVE_INFINITY;
                if (lastIterScore != null && currentDepth >= 3) {
                    double window = 50.0;            // ≈ 0.50 pawn; tune 25–100
                    alpha = lastIterScore - window;
                    beta = lastIterScore + window;

                    int retries = 0;
                    while (true) {
                        if (searchThreads > 1) {
                            moveAndScore = getBestMoveParallel(simulatorEngine, isWhite, currentDepth, deadline, alpha, beta);
                        } else {
                            moveAndScore = getBestMove(simulatorEngine, isWhite, currentDepth, deadline, alpha, beta);
                        }
                        if (moveAndScore == null) break; // timeout/stop

                        // Fail-low/high? Widen and re-search this same depth.
                        if (moveAndScore.score <= alpha) {
                            // fail-low → widen downwards
                            window *= 2.0;
                            alpha = moveAndScore.score - window;
                            retries++;
                            if (retries > 3) {
                                alpha = Double.NEGATIVE_INFINITY;
                                beta = Double.POSITIVE_INFINITY;
                            } else continue;
                        } else if (moveAndScore.score >= beta) {
                            // fail-high → widen upwards
                            window *= 2.0;
                            beta = moveAndScore.score + window;
                            retries++;
                            if (retries > 3) {
                                alpha = Double.NEGATIVE_INFINITY;
                                beta = Double.POSITIVE_INFINITY;
                            } else continue;
                        }
                        break; // in-window → accept
                    }
                }

                if (moveAndScore == null) {
                    // First iterations or after giving up on aspiration → full window
                    if (searchThreads > 1) {
                        moveAndScore = getBestMoveParallel(simulatorEngine, isWhite, currentDepth, deadline,
                                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
                    } else {
                        moveAndScore = getBestMove(simulatorEngine, isWhite, currentDepth, deadline,
                                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
                    }
                    if (moveAndScore == null) break; // timeout/stop
                }

                if (isNewBestMove(moveAndScore, bestScore, isWhite)) {
                    bestScore = moveAndScore.score;
                    bestMove = moveAndScore.move;
                    updateTranspositionTable(boardStateHash, moveAndScore, currentDepth);
                }

                lastIterScore = moveAndScore.score; // center next window here
            }
        } finally {
            if (!positionChanged() && keepCalculating && !Thread.currentThread().isInterrupted()) {
                if (bestMove == -1) {
                    MoveList legalMoves = simulatorEngine.getAllLegalMoves();
                    if (legalMoves.size() > 0) bestMove = legalMoves.getMove(0);
                }
                bestMoveForHash = boardStateHash;
                currentBestMove = bestMove;
                fillCalculatedLine(simulatorEngine);
            } else {
                currentBestMove = -1;
            }
        }
    }

    private MoveAndScore getBestMoveParallel(Engine simulatorEngine,
                                             boolean isWhitesTurn,
                                             int depth,
                                             long deadline,
                                             double alpha,
                                             double beta) {
        // Fallback: pool missing or time nearly gone
        if (searchPool == null || Thread.currentThread().isInterrupted() || timeLimitExceeded(deadline)) {
            return getBestMove(simulatorEngine, isWhitesTurn, depth, deadline, alpha, beta);
        }

        // Order root moves once
        MoveList legal = simulatorEngine.getAllLegalMoves();
        ArrayList<Integer> orderedMoves = sortMovesByEfficiency(legal, depth, simulatorEngine.getBoardStateHash(), -1);
        if (orderedMoves.isEmpty()) return null;

        // === 1) Search first move FULL WINDOW to seed the bounds (YBWC) ===
        int firstMove = orderedMoves.get(0);
        int bestMove = -1;
        double bestScore = isWhitesTurn ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;

        if (Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
            return null;
        }

        simulatorEngine.performMove(firstMove);
        double firstScore;
        if (simulatorEngine.getGameState().isInStateCheckMate()) {
            // prefer fastest mate invariantly at root
            firstScore = isWhitesTurn ? (CHECKMATE - 1) : -(CHECKMATE - 1);
        } else if (simulatorEngine.getGameState().isInStateDraw()) {
            firstScore = evaluateStaticPosition(simulatorEngine.getGameState(), !isWhitesTurn, depth);
        } else {
            firstScore = alphaBeta(simulatorEngine, depth - 1, alpha, beta, !isWhitesTurn, deadline, firstMove);
            if (firstScore == EXIT_FLAG || positionChanged()) {
                simulatorEngine.undoLastMove();
                return null;
            }
        }
        simulatorEngine.undoLastMove();

        bestMove = firstMove;
        bestScore = firstScore;

        // Seed shared window after first child (like your sequential version)
        if (isWhitesTurn) {
            alpha = Math.max(alpha, firstScore);
        } else {
            beta = Math.min(beta, firstScore);
        }
        if (alpha >= beta) {
            return new MoveAndScore(bestMove, bestScore);
        }

        // === 2) Submit siblings as NULL-WINDOW probes (YBWC) ===
        final int fanout = Math.min(ROOT_PARALLEL_LIMIT, orderedMoves.size() - 1);
        if (fanout <= 0) {
            return new MoveAndScore(bestMove, bestScore);
        }

        final CompletionService<MoveAndScore> ecs = new ExecutorCompletionService<>(searchPool);
        final List<Future<MoveAndScore>> futures = new ArrayList<>(fanout);

        // Shared, dynamic bounds + re-search serialization
        final java.util.concurrent.atomic.AtomicReference<Double> alphaRef = new java.util.concurrent.atomic.AtomicReference<>(alpha);
        final java.util.concurrent.atomic.AtomicReference<Double> betaRef = new java.util.concurrent.atomic.AtomicReference<>(beta);
        final java.util.concurrent.atomic.AtomicInteger bestMoveRef = new java.util.concurrent.atomic.AtomicInteger(bestMove);
        final java.util.concurrent.atomic.AtomicReference<Double> bestScoreRef = new java.util.concurrent.atomic.AtomicReference<>(bestScore);
        final java.util.concurrent.atomic.AtomicBoolean stopRef = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.locks.ReentrantLock fullResLock = new java.util.concurrent.locks.ReentrantLock();

        for (int i = 1; i <= fanout; i++) {
            final int moveInt = orderedMoves.get(i);
            futures.add(ecs.submit(() -> {
                // Bail early
                if (stopRef.get() || Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
                    return null;
                }

                // Clone engine at root position, then play this move
                Engine e = simulatorEngine.createSimulation();
                e.performMove(moveInt);

                // Probe with NULL window
                double currentAlpha = alphaRef.get();
                double currentBeta = betaRef.get();

                double pAlpha, pBeta;
                if (isWhitesTurn) {
                    pAlpha = currentAlpha;
                    pBeta = currentAlpha + 1;
                } else {
                    // For minimizer, mirror PVS: probe with [β-1, β]
                    pAlpha = currentBeta - 1;
                    pBeta = currentBeta;
                }

                double probe;
                if (e.getGameState().isInStateCheckMate()) {
                    probe = isWhitesTurn ? (CHECKMATE - 1) : -(CHECKMATE - 1);
                } else if (e.getGameState().isInStateDraw()) {
                    probe = evaluateStaticPosition(e.getGameState(), !isWhitesTurn, depth);
                } else {
                    probe = alphaBeta(e, depth - 1, pAlpha, pBeta, !isWhitesTurn, deadline, moveInt);
                    if (probe == EXIT_FLAG) return null;
                }

                // Decide if we need a FULL re-search (fail-high for max / fail-low for min)
                boolean needsFull;
                if (isWhitesTurn) {
                    needsFull = probe > alphaRef.get();
                } else {
                    needsFull = probe < betaRef.get();
                }

                double finalScore = probe;

                if (needsFull && !stopRef.get()) {
                    // Serialize full re-search as per YBWC
                    fullResLock.lock();
                    try {
                        if (!stopRef.get() && !Thread.currentThread().isInterrupted() && !positionChanged() && System.nanoTime() <= deadline) {
                            // Refresh the current window before the real search
                            double aNow = alphaRef.get();
                            double bNow = betaRef.get();

                            double full = alphaBeta(e, depth - 1, aNow, bNow, !isWhitesTurn, deadline, moveInt);
                            if (full != EXIT_FLAG) {
                                finalScore = full;
                                // Tighten bounds + update best
                                if (isWhitesTurn) {
                                    if (full > aNow) {
                                        alphaRef.set(full);
                                    }
                                } else {
                                    if (full < bNow) {
                                        betaRef.set(full);
                                    }
                                }
                                // Update global best move/score (orientation aware)
                                Double curBest = bestScoreRef.get();
                                if (isBetterScore(isWhitesTurn, full, curBest)) {
                                    bestScoreRef.set(full);
                                    bestMoveRef.set(moveInt);
                                }
                                // If window collapsed, signal stop
                                if (alphaRef.get() >= betaRef.get()) {
                                    stopRef.set(true);
                                }
                            }
                        }
                    } finally {
                        fullResLock.unlock();
                    }
                } else {
                    // No re-search: we can still update "best" if it beats current best
                    Double curBest = bestScoreRef.get();
                    if (isBetterScore(isWhitesTurn, finalScore, curBest)) {
                        bestScoreRef.set(finalScore);
                        bestMoveRef.set(moveInt);
                        // Also tighten bounds in the non-research direction (rare but harmless)
                        if (isWhitesTurn && finalScore > alphaRef.get()) alphaRef.set(finalScore);
                        if (!isWhitesTurn && finalScore < betaRef.get()) betaRef.set(finalScore);
                        if (alphaRef.get() >= betaRef.get()) stopRef.set(true);
                    }
                }

                return new MoveAndScore(moveInt, finalScore);
            }));
        }

        // Collect results, react to tightening window
        int completed = 0;
        try {
            while (completed < fanout) {
                if (stopRef.get() || Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
                    break;
                }
                Future<MoveAndScore> f = ecs.take(); // next finished task
                completed++;
                MoveAndScore res = f.get();
                if (res == null) continue;

                // Pull tightened bounds updated by tasks
                alpha = alphaRef.get();
                beta = betaRef.get();

                // Best move/score already kept by tasks, but keep local mirrors for return
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
            // Try to cancel whatever is left
            for (Future<MoveAndScore> f : futures) {
                if (!f.isDone()) f.cancel(true);
            }
        }

        return bestMove != -1 ? new MoveAndScore(bestMove, bestScore) : null;
    }


    private boolean shouldStopCalculating(long deadline) {
        return positionChanged() || timeLimitExceeded(deadline) || Thread.currentThread().isInterrupted();
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
            this.calculatedLine = new ArrayList<>();
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

        this.calculatedLine = new ArrayList<>(pv);
    }


    private MoveAndScore getBestMove(Engine simulatorEngine, boolean isWhitesTurn, int depth, long deadline,
                                     double alpha, double beta) {
        int bestMove = -1; // Use an integer to represent the best move
        double bestScore = isWhitesTurn ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;

        ArrayList<Integer> sortedMoves = sortMovesByEfficiency(simulatorEngine.getAllLegalMoves(), depth,
                simulatorEngine.getBoardStateHash(), -1);

        for (int moveInt : sortedMoves) {

            // Exit early if the search was cancelled or the position changed
            if (Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
                break;
            }

            simulatorEngine.performMove(moveInt); // Perform move using its integer representation
            double score;

            if (simulatorEngine.getGameState().isInStateCheckMate()) {
                // prefer fastest mate invariantly at root
                score = isWhitesTurn ? (CHECKMATE - 1) : -(CHECKMATE - 1);
            } else if (simulatorEngine.getGameState().isInStateDraw()) {
                // keep draw policy consistent
                score = evaluateStaticPosition(simulatorEngine.getGameState(), !isWhitesTurn, depth);
            } else {
                score = alphaBeta(simulatorEngine, depth - 1, alpha, beta, !isWhitesTurn, deadline, moveInt);
                // Check for time limit exceeded after alphaBeta call
                if (score == EXIT_FLAG || positionChanged()) {
                    simulatorEngine.undoLastMove(); // Undo move using its integer representation
                    break;
                }
            }

            simulatorEngine.undoLastMove(); // Undo move using its integer representation

            // Check if the current move leads to a better score
            if (isBetterScore(isWhitesTurn, score, bestScore)) {
                bestScore = score;
                bestMove = moveInt; // Store the best move as an integer
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

        return bestMove != -1 ? new MoveAndScore(bestMove, bestScore) : null; // Return the best move and score
    }

    /**
     * *
     * 5rkr/pp2Rp2/1b1p1Pb1/3P2Q1/2n3P1/2p5/P4P2/4R1K1 w - - 1 0
     * *
     */
    // AI.java
    private double alphaBeta(Engine simulatorEngine, int depth, double alpha, double beta,
                             boolean isWhite, long deadline, int prevMove) {
        nodesVisited++;

        // stop conditions
        if (Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
            return EXIT_FLAG;
        }

        // ply-from-root for distance-to-mate normalization
        final int plyFromRoot = Math.max(0, rootDepthForMateScoring - Math.max(0, depth));

        // Terminal states first, with distance-to-mate
        if (simulatorEngine.getGameState().isInStateCheckMate()) {
            // Side to move is checkmated here
            double m = CHECKMATE - plyFromRoot;
            return isWhite ? -m : +m; // white maximizes; this node is bad for side-to-move
        }
        if (simulatorEngine.getGameState().isInStateDraw()) {
            return evaluateStaticPosition(simulatorEngine.getGameState(), isWhite, plyFromRoot);
        }

        long boardHash = simulatorEngine.getBoardStateHash();

        if (depth == 0) {
            // Quiescence returns white-oriented score; flip for black-to-move
            double eval = evaluateBoard(simulatorEngine, isWhite, deadline);
            if (eval == EXIT_FLAG) { // propagate qsearch timeout
                return EXIT_FLAG;
            }
            if (!isWhite) {
                eval = -eval;
            }
            return eval;
        }

        // Transposition table lookup
        TranspositionTableEntry entry = transpositionTable.get(boardHash);
        if (entry != null && entry.depth >= depth) {
            if (entry.nodeType == NodeType.EXACT) {
                return entry.score;
            }
            if (entry.nodeType == NodeType.LOWERBOUND && entry.score > alpha) {
                alpha = entry.score;
            } else if (entry.nodeType == NodeType.UPPERBOUND && entry.score < beta) {
                beta = entry.score;
            }
            if (alpha >= beta) {
                return entry.score;
            }
        }

        // -------- Safer Null-move pruning --------
        // Workflow: perform a reduced-depth null search, verify apparent fail-highs
        // with a second (depth - 1) search, and only prune when both agree. This
        // dramatically reduces the risk of zugzwangs or other tactical misses.
        boolean inCheck = isSideInCheck(simulatorEngine, isWhite);
        int mobility = simulatorEngine.getAllLegalMoves().size(); // guard against zugzwang-like nodes
        boolean allowNullMove = useNullMovePruning
                && depth >= 4
                && !inCheck
                && !simulatorEngine.isEndgame()
                && prevMove != -1     // avoid consecutive null moves
                && mobility >= 8;     // mobility guard

        if (allowNullMove) {
            int savedEp = simulatorEngine.doNullMoveForSearch(); // O(1) flip side + clear EP
            nullMoveCount++;
            double nullScore = alphaBeta(simulatorEngine, depth - 1 - 2, alpha, beta, !isWhite, deadline, -1);
            simulatorEngine.undoNullMoveForSearch(savedEp); // O(1) restore

            if (nullScore == EXIT_FLAG) { // propagate timeout
                return EXIT_FLAG;
            }

            boolean nullFailHigh = isWhite ? nullScore >= beta : nullScore <= alpha;
            if (nullFailHigh) {
                double verificationScore = alphaBeta(simulatorEngine, depth - 1, alpha, beta, isWhite, deadline, prevMove);
                if (verificationScore == EXIT_FLAG) {
                    return EXIT_FLAG;
                }
                nullFailHigh = isWhite ? verificationScore >= beta : verificationScore <= alpha;
            }

            if (nullFailHigh) {
                return isWhite ? beta : alpha;
            }
        }
        // -----------------------------------------

        double alphaOriginal = alpha; // Store the original alpha value
        double betaOriginal = beta;   // Store the original beta value

        MoveList moves = simulatorEngine.getAllLegalMoves();

        if (isWhite) {
            return maximizer(simulatorEngine, depth, alpha, beta, isWhite, boardHash, alphaOriginal, moves, deadline, prevMove);
        } else {
            return minimizer(simulatorEngine, depth, alpha, beta, isWhite, boardHash, betaOriginal, moves, deadline, prevMove);
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
        long myAttacks = bb.generateAttackBitboard(moverIsWhite);
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
        long myAttacks = bb.generateAttackBitboard(moverIsWhite);
        return (myAttacks & kingZone) != 0L;
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
        double base = Math.log(1 + depth) * Math.log(2 + moveIndex);
        int history = Math.max(0, historyScore);
        double normalized = Math.min(1.0, history / (double) HISTORY_REDUCTION_MAX);
        double historyWeight = 1.0 - 0.5 * normalized; // strong history shrinks reductions
        double scaled = base * historyWeight / 1.5;
        int r = (int) Math.floor(scaled);
        if (r < 0) {
            r = 0;
        }
        if (r > depth - 1) {
            r = depth - 1;
        }
        return r;
    }

    private double maximizer(Engine simulatorEngine, int depth, double alpha, double beta,
                             boolean isWhite, long boardHash, double alphaOriginal,
                             MoveList moves, long deadline, int prevMove) {

        long start = log.isDebugEnabled() ? System.nanoTime() : 0L;
        double maxEval = Double.NEGATIVE_INFINITY;
        int bestMoveAtThisNode = -1;

        final boolean inCheckAtNode = isSideInCheck(simulatorEngine, isWhite);

        ArrayList<Integer> orderedMoves = sortMovesByEfficiency(moves, depth, boardHash, prevMove);
        for (int index = 0; index < orderedMoves.size(); index++) {
            if (Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
                return EXIT_FLAG;
            }

            int move = orderedMoves.get(index);

            int from = MoveHelper.deriveFromIndex(move);
            int to = MoveHelper.deriveToIndex(move);
            int movingPieceBits = MoveHelper.derivePieceTypeBits(move);
            int capturedPieceBits = MoveHelper.deriveCapturedPieceTypeBits(move);
            int historyScore = historyTable[from][to];
            int seeGain = simulatorEngine.see(move);
            boolean seeWinsMaterial = seeGain > 0;

            boolean isCapture = MoveHelper.isCapture(move);
            boolean isPromotion = MoveHelper.isPawnPromotionMove(move);

            BitBoard boardBefore = simulatorEngine.getBitBoard();
            long enemyKingBB = isWhite ? boardBefore.getBlackKing() : boardBefore.getWhiteKing();
            int enemyKingSquare = enemyKingBB != 0L ? Long.numberOfTrailingZeros(enemyKingBB) : -1;
            int enemyKingFile = enemyKingSquare >= 0 ? (enemyKingSquare & 7) : -1;
            long kingFileMask = enemyKingFile >= 0 ? FileMasks[enemyKingFile] : 0L;
            boolean touchesKingFile = enemyKingSquare >= 0
                    && (((from & 7) == enemyKingFile) || ((to & 7) == enemyKingFile));
            boolean affectsKingFilePawns = touchesKingFile
                    && (movingPieceBits == 1 || capturedPieceBits == 1);
            int pawnsOnFileBefore = (enemyKingSquare >= 0 && affectsKingFilePawns)
                    ? countPawnsOnFile(boardBefore, kingFileMask)
                    : 0;

            // Skip obviously losing captures based on SEE, unless the move gives check or is a promotion
            if (!inCheckAtNode && isCapture && !isPromotion) {
                // only prune clearly losing captures; keep equal trades
                if (seeGain < 0) {
                    // but allow if it gives check (very tactical)
                    simulatorEngine.performMove(move);
                    boolean givesCheck = isSideInCheck(simulatorEngine, !isWhite);
                    simulatorEngine.undoLastMove();
                    if (!givesCheck) continue;
                }
            }
            boolean isTactical = isCapture || isPromotion;
            int lmpThreshold = 8 + depth * 2;
            if (!inCheckAtNode && !isTactical && depth <= 3 && index > lmpThreshold) {
                // But don't prune moves that give check or create an immediate queen threat
                simulatorEngine.performMove(move);
                boolean givesCheck = isSideInCheck(simulatorEngine, !isWhite);
                boolean attacksQueen = attacksOpponentQueenNow(simulatorEngine, isWhite);
                simulatorEngine.undoLastMove();
                if (!givesCheck && !attacksQueen) continue;
            }

            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();

            double eval;
            TranspositionTableEntry entry = transpositionTable.get(newBoardHash);

            if (entry != null && entry.depth >= depth) {
                eval = entry.score;
            } else {
                // Compute extension/gating signals AFTER making the move
                boolean givesCheck = isSideInCheck(simulatorEngine, !isWhite);
                boolean attacksQueen = attacksOpponentQueenNow(simulatorEngine, isWhite);
                boolean attacksKingZone = attacksOpponentKingZone(simulatorEngine, isWhite);
                boolean opensKingFile = openedFileTowardKing(
                        simulatorEngine.getBitBoard(), kingFileMask, pawnsOnFileBefore, affectsKingFilePawns
                );

                // depth for child
                int nextDepth = depth - 1;
                if (givesCheck || attacksQueen) nextDepth = Math.min(nextDepth + 1, depth);

                // Decide if we can reduce this move
                boolean canReduce = !inCheckAtNode
                        && !isTactical
                        && !givesCheck // checks are too forcing to reduce
                        && !attacksQueen
                        && !attacksKingZone // direct king pressure keeps full depth
                        && !opensKingFile // opening lines to the king needs verification
                        && !seeWinsMaterial // SEE winning moves deserve the full depth
                        && nextDepth >= 2
                        && index >= 3;

                // PVS windows as you had them
                boolean usePvs = index > 0 && alpha != Double.NEGATIVE_INFINITY && beta != Double.POSITIVE_INFINITY;
                double pAlpha = alpha;
                double pBeta = usePvs ? (alpha + 1) : beta;

                int reduction = 0;
                if (canReduce) {
                    reduction = lmrReduction(nextDepth, index, historyScore);
                    if (reduction <= 0) {
                        canReduce = false;
                    }
                }

                if (canReduce) {
                    int reduced = Math.max(1, nextDepth - reduction);
                    eval = alphaBeta(simulatorEngine, reduced, pAlpha, pBeta, !isWhite, deadline, move);
                    if (eval == EXIT_FLAG || positionChanged()) {
                        simulatorEngine.undoLastMove();
                        return EXIT_FLAG;
                    }

                    boolean promising = eval > alpha;
                    if (promising) {
                        eval = alphaBeta(simulatorEngine, nextDepth, usePvs ? alpha : pAlpha, usePvs ? beta : pBeta, !isWhite, deadline, move);
                        if (eval == EXIT_FLAG || positionChanged()) {
                            simulatorEngine.undoLastMove();
                            return EXIT_FLAG;
                        }
                    }
                } else {
                    // No reduction for checks/queen-threats/tacticals
                    eval = alphaBeta(simulatorEngine, nextDepth, pAlpha, pBeta, !isWhite, deadline, move);
                    if (eval == EXIT_FLAG || positionChanged()) {
                        simulatorEngine.undoLastMove();
                        return EXIT_FLAG;
                    }
                    if (usePvs && eval > alpha && eval < beta) {
                        eval = alphaBeta(simulatorEngine, nextDepth, alpha, beta, !isWhite, deadline, move);
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
                if (prevMove >= 0) {
                    int pf = prevMove & 0x3F, pt = (prevMove >>> 6) & 0x3F;
                    counterMove[pf][pt] = move;
                }
                if (log.isDebugEnabled()) log.debug(" Maxi New Killer Move is {}", Move.convertIntToMove(move));
                break;
            }
        }

        TranspositionTableEntry existingEntry = transpositionTable.get(boardHash);
        boolean shouldUpdate = existingEntry == null || existingEntry.depth < depth;

        if (maxEval <= alphaOriginal && shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(maxEval, depth, NodeType.UPPERBOUND, bestMoveAtThisNode));
        } else if (maxEval >= beta && shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(maxEval, depth, NodeType.LOWERBOUND, bestMoveAtThisNode));
        } else if (shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(maxEval, depth, NodeType.EXACT, bestMoveAtThisNode));
        }

        return maxEval;
    }


    private double minimizer(Engine simulatorEngine, int depth, double alpha, double beta,
                             boolean isWhite, long boardHash, double betaOriginal,
                             MoveList moves, long deadline, int prevMove) {

        long start = log.isDebugEnabled() ? System.nanoTime() : 0L;
        double minEval = Double.POSITIVE_INFINITY;
        int bestMoveAtThisNode = -1;

        final boolean inCheckAtNode = isSideInCheck(simulatorEngine, isWhite);

        ArrayList<Integer> orderedMoves = sortMovesByEfficiency(moves, depth, boardHash, prevMove);
        for (int index = 0; index < orderedMoves.size(); index++) {
            if (Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
                return EXIT_FLAG;
            }

            int move = orderedMoves.get(index);

            int from = MoveHelper.deriveFromIndex(move);
            int to = MoveHelper.deriveToIndex(move);
            int movingPieceBits = MoveHelper.derivePieceTypeBits(move);
            int capturedPieceBits = MoveHelper.deriveCapturedPieceTypeBits(move);
            int historyScore = historyTable[from][to];
            int seeGain = simulatorEngine.see(move);
            boolean seeWinsMaterial = seeGain > 0;

            boolean isCapture = MoveHelper.isCapture(move);
            boolean isPromotion = MoveHelper.isPawnPromotionMove(move);
            boolean isTactical = isCapture || isPromotion;

            BitBoard boardBefore = simulatorEngine.getBitBoard();
            long enemyKingBB = isWhite ? boardBefore.getBlackKing() : boardBefore.getWhiteKing();
            int enemyKingSquare = enemyKingBB != 0L ? Long.numberOfTrailingZeros(enemyKingBB) : -1;
            int enemyKingFile = enemyKingSquare >= 0 ? (enemyKingSquare & 7) : -1;
            long kingFileMask = enemyKingFile >= 0 ? FileMasks[enemyKingFile] : 0L;
            boolean touchesKingFile = enemyKingSquare >= 0
                    && (((from & 7) == enemyKingFile) || ((to & 7) == enemyKingFile));
            boolean affectsKingFilePawns = touchesKingFile
                    && (movingPieceBits == 1 || capturedPieceBits == 1);
            int pawnsOnFileBefore = (enemyKingSquare >= 0 && affectsKingFilePawns)
                    ? countPawnsOnFile(boardBefore, kingFileMask)
                    : 0;

            if (!inCheckAtNode && isCapture && !isPromotion) {
                // only prune clearly losing captures; keep equal trades
                if (seeGain < 0) {
                    // but allow if it gives check (very tactical)
                    simulatorEngine.performMove(move);
                    boolean givesCheck = isSideInCheck(simulatorEngine, !isWhite);
                    simulatorEngine.undoLastMove();
                    if (!givesCheck) continue;
                }
            }

            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();

            double eval;
            TranspositionTableEntry entry = transpositionTable.get(newBoardHash);

            if (entry != null && entry.depth >= depth) {
                eval = entry.score;
            } else {
                // Compute extension/gating signals AFTER making the move
                boolean givesCheck = isSideInCheck(simulatorEngine, !isWhite);
                boolean attacksQueen = attacksOpponentQueenNow(simulatorEngine, isWhite);
                boolean attacksKingZone = attacksOpponentKingZone(simulatorEngine, isWhite);
                boolean opensKingFile = openedFileTowardKing(
                        simulatorEngine.getBitBoard(), kingFileMask, pawnsOnFileBefore, affectsKingFilePawns
                );

                int nextDepth = depth - 1;
                if (givesCheck || attacksQueen) nextDepth = Math.min(nextDepth + 1, depth);

                boolean canReduce = !inCheckAtNode
                        && !isTactical
                        && !givesCheck // keep full depth on checks
                        && !attacksQueen
                        && !attacksKingZone // king pressure stays unreduced
                        && !opensKingFile // freshly opened king file needs full depth
                        && !seeWinsMaterial // SEE-positive moves shouldn't be reduced
                        && nextDepth >= 2
                        && index >= 3;

                boolean usePvs = index > 0 && alpha != Double.NEGATIVE_INFINITY && beta != Double.POSITIVE_INFINITY;
                double pAlpha = usePvs ? (beta - 1) : alpha;
                double pBeta = beta;

                int reduction = 0;
                if (canReduce) {
                    reduction = lmrReduction(nextDepth, index, historyScore);
                    if (reduction <= 0) {
                        canReduce = false;
                    }
                }

                if (canReduce) {
                    int reduced = Math.max(1, nextDepth - reduction);

                    eval = alphaBeta(simulatorEngine, reduced, pAlpha, pBeta, !isWhite, deadline, move);
                    if (eval == EXIT_FLAG || positionChanged()) {
                        simulatorEngine.undoLastMove();
                        return EXIT_FLAG;
                    }

                    boolean promising = eval < beta;
                    if (promising) {
                        eval = alphaBeta(simulatorEngine, nextDepth, usePvs ? alpha : pAlpha, usePvs ? beta : pBeta, !isWhite, deadline, move);
                        if (eval == EXIT_FLAG || positionChanged()) {
                            simulatorEngine.undoLastMove();
                            return EXIT_FLAG;
                        }
                    }
                } else {
                    eval = alphaBeta(simulatorEngine, nextDepth, pAlpha, pBeta, !isWhite, deadline, move);
                    if (eval == EXIT_FLAG || positionChanged()) {
                        simulatorEngine.undoLastMove();
                        return EXIT_FLAG;
                    }

                    if (usePvs && eval > alpha && eval < beta) {
                        eval = alphaBeta(simulatorEngine, nextDepth, alpha, beta, !isWhite, deadline, move);
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
                if (prevMove >= 0) {
                    int pf = prevMove & 0x3F, pt = (prevMove >>> 6) & 0x3F;
                    counterMove[pf][pt] = move;
                }
                if (log.isDebugEnabled()) log.debug("Mini New Killer Move is {}", Move.convertIntToMove(move));
                break;
            }
        }

        TranspositionTableEntry existingEntry = transpositionTable.get(boardHash);
        boolean shouldUpdate = existingEntry == null || existingEntry.depth < depth;

        if (minEval >= betaOriginal && shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(minEval, depth, NodeType.LOWERBOUND, bestMoveAtThisNode));
        } else if (minEval <= alpha && shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(minEval, depth, NodeType.UPPERBOUND, bestMoveAtThisNode));
        } else if (shouldUpdate) {
            transpositionTable.put(boardHash, new TranspositionTableEntry(minEval, depth, NodeType.EXACT, bestMoveAtThisNode));
        }

        return minEval;
    }


    ArrayList<Integer> sortMovesByEfficiency(MoveList moves, int currentDepth, long boardHash, int prevMove) {
        final int size = moves.size();
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


        final long[] sortKeys = new long[size];

        for (int i = 0; i < size; i++) {
            final int moveInt = moves.getMove(i);

            // Track TT move position for the "pin to front" trick
            if (moveInt == ttMove) {
                ttIndex = i;
            }

            // Compute base features
            final boolean isCapture = MoveHelper.isCapture(moveInt);
            final boolean isPromotion = MoveHelper.isPawnPromotionMove(moveInt);

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
                score = base + PROMOTION_ORDER_BONUS;
            } else if (isCapture) {
                // MVV-LVA for captures; classify as good/equal/bad without SEE
                final int mvvLva = calculateMvvLvaScore(moveInt); // victim - attacker (can be negative)
                if (mvvLva > 0) {
                    category = CAT_CAP_GOOD;
                } else if (mvvLva == 0) {
                    category = CAT_CAP_EQUAL;
                } else {
                    category = CAT_CAP_BAD;
                }
                // Scale captures so bigger victims / smaller attackers bubble up
                score = (mvvLva * 16); // small scale keeps room for tie-breaks
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
        ArrayList<Integer> sortedMoveList = new ArrayList<>(size);
        if (ttIndex != -1) {
            // TT already at index 0
            sortedMoveList.add((int) (sortKeys[0] & 0xFFFFFFFFL));
            for (int i = size - 1; i >= 1; i--) {
                sortedMoveList.add((int) (sortKeys[i] & 0xFFFFFFFFL));
            }
        } else {
            for (int i = size - 1; i >= 0; i--) {
                sortedMoveList.add((int) (sortKeys[i] & 0xFFFFFFFFL));
            }
        }

        return sortedMoveList;
    }


    public double evaluateBoard(Engine simulatorEngine, boolean isWhitesTurn, long deadline) {
        if (simulatorEngine.getGameState().isInStateCheckMate()) {
            // Side to move has no legal moves and is in check → losing for side to move
            return -CHECKMATE; // alphaBeta handles mate distance; this path is rarely hit
        }

        if (simulatorEngine.getGameState().isInStateDraw()) {
            double scoreDiff = simulatorEngine.getGameState().getScore().getScoreDifference();
            // stronger bias than ±1 to steer away from draws when ahead
            final double DRAW_BIAS = 20.0;
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
            captureTranspositionTable.put(boardStateHash, new CaptureTranspositionTableEntry(score, isWhitesTurn));
        }

        return score;
    }

    private double quiescenceSearch(Engine simulatorEngine, boolean isWhitesTurn,
                                    double alpha, double beta, long deadline, int depth) {
        // early stop
        if (Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
            if (log.isDebugEnabled()) log.debug("qsearch stop (timeout/interrupt/positionChanged)");
            return AI.EXIT_FLAG; // Timeout / cancelled
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
        MoveList moves = inCheck ? simulatorEngine.getAllLegalMoves() : getPossibleCapturesOrPromotions(simulatorEngine);

        // Order them (captures first via MVV-LVA/promotion bonus, killers/history still help)
        ArrayList<Integer> ordered = sortMovesByEfficiency(moves, 0, simulatorEngine.getBoardStateHash(), -1);

        for (int m : ordered) {
            // --- SEE pruning: drop clearly losing captures (keeps promotions) ---
            if (!inCheck && MoveHelper.isCapture(m) && !MoveHelper.isPawnPromotionMove(m)) {
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
            // stronger bias than ±1 to steer decisively
            final double DRAW_BIAS = 20.0;
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


    private boolean isNewBestMove(MoveAndScore moveAndScore, double currentBestScore, boolean isWhite) {
        double score = moveAndScore.score;
        return moveAndScore.move != -1 && (isWhite ? score > currentBestScore : score < currentBestScore);
    }

    private boolean timeLimitExceeded(long deadline) {
        return System.nanoTime() > deadline;
    }

    private void updateTranspositionTable(long hash, MoveAndScore ms, int depth) {
        if (depth < 4) return; // throttle shallow writes; keep reads
        TranspositionTableEntry e = transpositionTable.get(hash);
        if (e == null || e.depth < depth) {
            transpositionTable.put(hash,
                    new TranspositionTableEntry(ms.score, depth, NodeType.EXACT, ms.move));
        }
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
        // Ensure depth is within bounds of the killerMoves array
        int depthIndex = Math.max(0, Math.min(depth, killerMoves.length - 1));
        int numKillerMoves = killerMoves[depthIndex].length; // Get the number of killer moves for this depth

        // Check if the move is already in the killer moves array
        for (int i = 0; i < numKillerMoves; i++) {
            if (killerMoves[depthIndex][i] == move) {
                return; // If move is already a killer move, no need to update
            }
        }

        // Shift existing killer moves down and insert the new move at the beginning
        for (int i = numKillerMoves - 1; i > 0; i--) {
            killerMoves[depthIndex][i] = killerMoves[depthIndex][i - 1];
        }
        killerMoves[depthIndex][0] = move; // Insert new killer move at the top
    }

    private void incrementHistory(int move, int depth) {
        if (MoveHelper.isCapture(move)) {
            return; // history heuristic tracks only quiet moves
        }
        int from = move & 0x3F;
        int to = (move >> 6) & 0x3F;
        historyTable[from][to] += depth * depth;
    }

    private void decayHistoryTable() {
        for (int i = 0; i < 64; i++) {
            for (int j = 0; j < 64; j++) {
                historyTable[i][j] >>= 1; // divide by 2
            }
        }
    }

    private void clearHistoryTable() {
        for (int i = 0; i < 64; i++) {
            Arrays.fill(historyTable[i], 0);
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
}
