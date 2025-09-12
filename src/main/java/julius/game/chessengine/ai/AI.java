package julius.game.chessengine.ai;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static julius.game.chessengine.utils.Score.*;
import static julius.game.chessengine.utils.Score.DRAW;

@Log4j2
@Component
public class AI {

    @Getter
    private final Engine mainEngine;

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

    /** Number of threads used for searching. Configurable via the system property
     * {@code chessengine.searchThreads}. Defaults to single-threaded search. */
    private static final int SEARCH_THREADS = Integer.getInteger("chessengine.searchThreads", 1);

    /**
     * Fixed-size transposition table. Uses a non-atomic implementation when running
     * with a single search thread to avoid the overhead of atomic operations.
     */
    private static final TranspositionTable<TranspositionTableEntry> transpositionTable =
            SEARCH_THREADS == 1
                    ? new PlainFixedSizeTranspositionTable<>(TRANSPOSITION_TABLE_MAX_ENTRIES, TranspositionTableEntry.class)
                    : new FixedSizeTranspositionTable<>(TRANSPOSITION_TABLE_MAX_ENTRIES);

    /**
     * Separate table for capture searches using the same fixed-size structure.
     */
    private static final TranspositionTable<CaptureTranspositionTableEntry> captureTranspositionTable =
            SEARCH_THREADS == 1
                    ? new PlainFixedSizeTranspositionTable<>(CAPTURE_TRANSPOSITION_TABLE_MAX_ENTRIES, CaptureTranspositionTableEntry.class)
                    : new FixedSizeTranspositionTable<>(CAPTURE_TRANSPOSITION_TABLE_MAX_ENTRIES);

    private final int[][] killerMoves; // 2D array for killer moves, initialized in the constructor
    private final int numKillerMoves = 2;

    /**
     * History heuristic table. Indexed by from and to square (0-63), it stores a
     * score indicating how often a quiet move has caused a beta cutoff. Higher
     * scores mean the move should be ordered earlier in the move list.
     */
    private final int[][] historyTable; // [from][to]

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

    private int currentBestMove = -1;

    @Getter
    private List<MoveAndScore> calculatedLine = Collections.synchronizedList(new ArrayList<>());

    // Game configuration parameters

    private final int maxDepth = 18; // Adjust the level of depth according to your requirements

    @Getter
    @Setter
    private long timeLimit; // milliseconds

    private boolean useNullMovePruning = false;

    @Getter
    private long nodesVisited = 0;
    @Getter
    private long nullMoveCount = 0;


    public AI(Engine mainEngine) {
        this.mainEngine = mainEngine;
        this.timeLimit = 50;

        // Initialize the array for killer moves
        this.killerMoves = new int[maxDepth][numKillerMoves];
        for (int i = 0; i < maxDepth; i++) {
            for (int j = 0; j < numKillerMoves; j++) {
                killerMoves[i][j] = -1; // Initialize with an invalid move
            }
        }

        // Initialize history table with zeros
        this.historyTable = new int[64][64];
    }

    public Integer getCurrentBestMoveInt() {
        return currentBestMove;
    }


    public void resetCounters() {
        nodesVisited = 0;
        nullMoveCount = 0;
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
                if (currentBestMove != -1) {
                    performMove();
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
    }

    public void performMove() {
        if (currentBestMove == -1) {
            // The calculation thread has not yet produced a move; skip until one is available.
            log.debug("No current best move available. Waiting for calculation to finish.");
            return;
        }

        // FIX: readability (logical != instead of !A == B)
        if (MoveHelper.isWhitesMove(currentBestMove) != mainEngine.whitesTurn()) {
            // If the current best move is not valid for the current turn, log an error and return.
            log.debug("Current best move {} is not valid for the current turn.", Move.convertIntToMove(currentBestMove));
            return; // Return the current state without making a move
        }
        mainEngine.performMove(currentBestMove);
        currentBoardState = mainEngine.getBoardStateHash();
        synchronized (calculationLock) {
            calculationLock.notifyAll();
        }
        // Reset the best move so that a stale move isn't played again before
        // the calculation thread provides a new one for the updated position.
        currentBestMove = -1;
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
        int bestMove = mainEngine.getOpeningBook().getRandomMoveForBoardStateHash(boardStateHash); // -1 if none

        // Use last iteration's score as the center of an aspiration window
        double lastIterScore = 0.0;
        boolean haveLast = false;

        try {
            for (int currentDepth = 1; currentDepth <= maxDepth; currentDepth++) {
                if (shouldStopCalculating(deadline)) break;

                double alpha, beta;
                if (haveLast) {
                    double window = 50; // ~0.5 pawn
                    alpha = lastIterScore - window;
                    beta  = lastIterScore + window;

                    MoveAndScore rs = null; // <-- ensure definite assignment
                    while (true) {
                        if (shouldStopCalculating(deadline)) break;
                        rs = getBestMove(simulatorEngine, isWhite, currentDepth, deadline, alpha, beta);
                        if (rs == null) break;

                        if (rs.score <= alpha) {          // fail-low → widen down
                            window *= 2.0;
                            alpha = lastIterScore - window;
                        } else if (rs.score >= beta) {    // fail-high → widen up
                            window *= 2.0;
                            beta  = lastIterScore + window;
                        } else {
                            // inside window
                            break;
                        }
                    }

                    // If we aborted due to time/position change, stop this depth immediately
                    if (shouldStopCalculating(deadline)) break;

                    if (rs != null && isNewBestMove(rs, bestScore, isWhite)) {
                        bestScore = rs.score;
                        bestMove  = rs.move;
                        updateTranspositionTable(boardStateHash, rs, currentDepth);
                    }
                } else {
                    // First iteration: full window
                    MoveAndScore rs = getBestMove(simulatorEngine, isWhite, currentDepth, deadline,
                            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
                    if (rs != null && isNewBestMove(rs, bestScore, isWhite)) {
                        bestScore = rs.score;
                        bestMove  = rs.move;
                        updateTranspositionTable(boardStateHash, rs, currentDepth);
                    }
                }

                if (bestMove != -1) {
                    lastIterScore = bestScore;
                    haveLast = true;
                }
            }
        } finally {
            if (bestMove == -1) {
                MoveList legalMoves = simulatorEngine.getAllLegalMoves();
                if (legalMoves.size() > 0) bestMove = legalMoves.getMove(0);
            }
            currentBestMove = bestMove;
            fillCalculatedLine(simulatorEngine);
        }
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

        /*if (log.isInfoEnabled()) {
            String line = pv.stream().map(ms -> Move.convertIntToMove(ms.move).toString()).collect(Collectors.joining(", "));
            log.info("Move Line: {}", line);
        }*/
    }



    private MoveAndScore getBestMove(Engine simulatorEngine, boolean isWhitesTurn, int depth, long deadline,
                                     double alpha, double beta) {
        int bestMove = -1; // Use an integer to represent the best move
        double bestScore = isWhitesTurn ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;

        ArrayList<Integer> sortedMoves = sortMovesByEfficiency(simulatorEngine.getAllLegalMoves(), depth,
                simulatorEngine.getBoardStateHash());

        for (int moveInt : sortedMoves) {

            // Exit early if the search was cancelled or the position changed
            if (Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
                break;
            }

            simulatorEngine.performMove(moveInt); // Perform move using its integer representation
            double score;

            if (simulatorEngine.getGameState().isInStateCheckMate()) {
                score = isWhitesTurn ? (CHECKMATE - depth) : -(CHECKMATE - depth);
            } else if (simulatorEngine.getGameState().isInStateDraw()) {
                // FIX: keep draw policy consistent (use same static evaluation as elsewhere)
                score = evaluateStaticPosition(simulatorEngine.getGameState(), !isWhitesTurn, depth);
            } else {
                score = alphaBeta(simulatorEngine, depth - 1, alpha, beta, !isWhitesTurn, deadline);
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
    private double alphaBeta(Engine simulatorEngine, int depth, double alpha, double beta, boolean isWhite, long deadline) {
        nodesVisited++;
        if (Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
            return EXIT_FLAG;
        }

        long boardHash = simulatorEngine.getBoardStateHash();

        if (simulatorEngine.getGameState().isInStateDraw()) {
            return evaluateStaticPosition(simulatorEngine.getGameState(), isWhite, depth);
        }

        // FIX: allow negative depths to terminate safely
        if (depth <= 0 || simulatorEngine.getGameState().isGameOver()) {
            if (simulatorEngine.getGameState().isInStateCheckMate()) {
                return -CHECKMATE + depth;
            }
            return evaluateBoard(simulatorEngine, isWhite, deadline);
        }

        TranspositionTableEntry entry = transpositionTable.get(boardHash);

        // Use TT entry if it is at least as deep as our current depth
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

        // === NULL-MOVE PRUNING: use lean search-only path (no move-gen / no GameState churn) ===
        if (useNullMovePruning && depth >= 3 && !isSideInCheck(simulatorEngine, isWhite) && !simulatorEngine.isEndgame()) {
            int savedEp = simulatorEngine.doNullMoveForSearch(); // O(1) flip side + clear EP
            nullMoveCount++;
            double nullScore = alphaBeta(simulatorEngine, depth - 1 - 2, alpha, beta, !isWhite, deadline);
            simulatorEngine.undoNullMoveForSearch(savedEp); // O(1) restore

            if (nullScore == EXIT_FLAG) { // propagate timeout
                return EXIT_FLAG;
            }

            // Fail-high/low pruning based on side to move
            if (isWhite && nullScore >= beta) {
                return beta;
            } else if (!isWhite && nullScore <= alpha) {
                return alpha;
            }
        }
        // === END NULL-MOVE BLOCK ===

        double alphaOriginal = alpha; // Store the original alpha value
        double betaOriginal = beta;   // Store the original beta value

        MoveList moves = simulatorEngine.getAllLegalMoves();

        if (isWhite) {
            return maximizer(simulatorEngine, depth, alpha, beta, isWhite, boardHash, alphaOriginal, moves, deadline);
        } else {
            return minimizer(simulatorEngine, depth, alpha, beta, isWhite, boardHash, betaOriginal, moves, deadline);
        }
    }

    private boolean isSideInCheck(Engine engine, boolean isWhite) {
        GameStateEnum state = engine.getGameState().getState();
        return (isWhite && state == GameStateEnum.WHITE_IN_CHECK) || (!isWhite && state == GameStateEnum.BLACK_IN_CHECK);
    }

    private double maximizer(Engine simulatorEngine, int depth, double alpha, double beta, boolean isWhite,
                             long boardHash, double alphaOriginal, MoveList moves, long deadline) {

        double maxEval = Double.NEGATIVE_INFINITY;
        int bestMoveAtThisNode = -1;

        ArrayList<Integer> orderedMoves = sortMovesByEfficiency(moves, depth, boardHash);
        for (int index = 0; index < orderedMoves.size(); index++) {
            if (Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
                return EXIT_FLAG;
            }

            int move = orderedMoves.get(index);
            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();

            double eval = Double.NEGATIVE_INFINITY; // <-- init to satisfy definite assignment (safe for maximizer)
            int nextDepth = depth - 1;

            // --- Transposition table handling for CHILD ---
            TranspositionTableEntry entry = transpositionTable.get(newBoardHash);

            boolean usePvs = index > 0 && alpha != Double.NEGATIVE_INFINITY && beta != Double.POSITIVE_INFINITY;
            double pAlpha = alpha;
            double pBeta  = beta;
            if (usePvs) pBeta = alpha + 1;

            boolean skipSearch = false;

            if (entry != null && entry.depth >= nextDepth) {
                if (entry.nodeType == NodeType.EXACT) {
                    eval = entry.score;
                    skipSearch = true;
                } else if (entry.nodeType == NodeType.LOWERBOUND) {
                    pAlpha = Math.max(pAlpha, entry.score);
                    if (pAlpha >= pBeta) {
                        eval = entry.score; // cutoff via bound
                        skipSearch = true;
                    }
                } else if (entry.nodeType == NodeType.UPPERBOUND) {
                    pBeta = Math.min(pBeta, entry.score);
                    if (pAlpha >= pBeta) {
                        eval = entry.score; // cutoff via bound
                        skipSearch = true;
                    }
                }
            }

            if (!skipSearch) {
                // -------- LMR (don’t reduce captures, promos, checks, killers) --------
                boolean isCapture  = MoveHelper.isCapture(move);
                boolean isPromo    = MoveHelper.isPawnPromotionMove(move);
                boolean isQuiet    = !(isCapture || isPromo);
                boolean isKiller   = isKillerMove(depth, move);
                boolean givesCheck = isSideInCheck(simulatorEngine, simulatorEngine.whitesTurn()); // after move

                int reduction = 0;
                if (nextDepth >= 2 && index >= 4 && isQuiet && !isKiller && !givesCheck) {
                    reduction = 1;
                    if (nextDepth >= 5 && index >= 12) reduction++;
                }
                int searchDepth = Math.max(0, nextDepth - reduction); // allow 0!

                eval = alphaBeta(simulatorEngine, searchDepth, pAlpha, pBeta, !isWhite, deadline);
                if (eval == EXIT_FLAG || positionChanged()) { simulatorEngine.undoLastMove(); return EXIT_FLAG; }

                // Re-search conditions (LMR and PVS)
                boolean improved = eval > alpha;
                if (reduction > 0 && improved) {
                    eval = alphaBeta(simulatorEngine, nextDepth, pAlpha, pBeta, !isWhite, deadline);
                    if (eval == EXIT_FLAG || positionChanged()) { simulatorEngine.undoLastMove(); return EXIT_FLAG; }
                }
                if (usePvs && eval > alpha && eval < beta) {
                    eval = alphaBeta(simulatorEngine, nextDepth, alpha, beta, !isWhite, deadline);
                    if (eval == EXIT_FLAG || positionChanged()) { simulatorEngine.undoLastMove(); return EXIT_FLAG; }
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
                incrementHistory(move, depth);
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

    private double minimizer(Engine simulatorEngine, int depth, double alpha, double beta, boolean isWhite,
                             long boardHash, double betaOriginal, MoveList moves, long deadline) {

        double minEval = Double.POSITIVE_INFINITY;
        int bestMoveAtThisNode = -1;

        ArrayList<Integer> orderedMoves = sortMovesByEfficiency(moves, depth, boardHash);
        for (int index = 0; index < orderedMoves.size(); index++) {
            if (Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
                return EXIT_FLAG;
            }

            int move = orderedMoves.get(index);
            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();

            double eval = Double.POSITIVE_INFINITY; // <-- init (safe for minimizer)
            int nextDepth = depth - 1;

            // --- Transposition table handling for CHILD ---
            TranspositionTableEntry entry = transpositionTable.get(newBoardHash);

            boolean usePvs = index > 0 && alpha != Double.NEGATIVE_INFINITY && beta != Double.POSITIVE_INFINITY;
            double pAlpha = alpha;
            double pBeta  = beta;
            if (usePvs) pAlpha = beta - 1;

            boolean skipSearch = false;

            if (entry != null && entry.depth >= nextDepth) {
                if (entry.nodeType == NodeType.EXACT) {
                    eval = entry.score;
                    skipSearch = true;
                } else if (entry.nodeType == NodeType.LOWERBOUND) {
                    pAlpha = Math.max(pAlpha, entry.score);
                    if (pAlpha >= pBeta) {
                        eval = entry.score; // cutoff via bound
                        skipSearch = true;
                    }
                } else if (entry.nodeType == NodeType.UPPERBOUND) {
                    pBeta = Math.min(pBeta, entry.score);
                    if (pAlpha >= pBeta) {
                        eval = entry.score; // cutoff via bound
                        skipSearch = true;
                    }
                }
            }

            if (!skipSearch) {
                // -------- LMR --------
                boolean isCapture  = MoveHelper.isCapture(move);
                boolean isPromo    = MoveHelper.isPawnPromotionMove(move);
                boolean isQuiet    = !(isCapture || isPromo);
                boolean isKiller   = isKillerMove(depth, move);
                boolean givesCheck = isSideInCheck(simulatorEngine, simulatorEngine.whitesTurn()); // after move

                int reduction = 0;
                if (nextDepth >= 2 && index >= 4 && isQuiet && !isKiller && !givesCheck) {
                    reduction = 1;
                    if (nextDepth >= 5 && index >= 12) reduction++;
                }
                int searchDepth = Math.max(0, nextDepth - reduction); // allow 0!

                eval = alphaBeta(simulatorEngine, searchDepth, pAlpha, pBeta, !isWhite, deadline);
                if (eval == EXIT_FLAG || positionChanged()) { simulatorEngine.undoLastMove(); return EXIT_FLAG; }

                boolean improved = eval < beta;
                if (reduction > 0 && improved) {
                    eval = alphaBeta(simulatorEngine, nextDepth, pAlpha, pBeta, !isWhite, deadline);
                    if (eval == EXIT_FLAG || positionChanged()) { simulatorEngine.undoLastMove(); return EXIT_FLAG; }
                }
                if (usePvs && eval > alpha && eval < beta) {
                    eval = alphaBeta(simulatorEngine, nextDepth, alpha, beta, !isWhite, deadline);
                    if (eval == EXIT_FLAG || positionChanged()) { simulatorEngine.undoLastMove(); return EXIT_FLAG; }
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
                incrementHistory(move, depth);
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


    ArrayList<Integer> sortMovesByEfficiency(MoveList moves, int currentDepth, long boardHash) {
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
                final int to   = (moveInt >>> 6) & 0x3F;
                category = CAT_QUIET;
                score = historyTable[from][to]; // butterfly history
            }

            // Persist (kept for compatibility with your buffers)
            moveBuffer[i] = moveInt;
            scoreBuffer[i] = score;

            // Compose a sortable 64-bit key:
            // [8 bits category][24 bits score clamped to unsigned][32 bits move id]
            int s = score;
            if (s < 0) s = 0; else if (s > 0x00FFFFFF) s = 0x00FFFFFF; // clamp to 24 bits
            long key = (((long) category) << 56) | (((long) s) << 32) | (moveInt & 0xFFFFFFFFL);
            sortBuffer[i] = key;
        }

        // Keep TT move hard-pinned at the front (index 0), sort the remainder by key
        int sortStart = 0;
        if (ttIndex > 0) {
            long ttCombined = sortBuffer[ttIndex];
            System.arraycopy(sortBuffer, 0, sortBuffer, 1, ttIndex);
            sortBuffer[0] = ttCombined;
            sortStart = 1;
        }

        Arrays.sort(sortBuffer, sortStart, size); // ascending by key

        // Build result in descending order (bigger category/score first)
        ArrayList<Integer> sortedMoveList = new ArrayList<>(size);
        if (ttIndex != -1) {
            // TT already at index 0
            sortedMoveList.add((int) (sortBuffer[0] & 0xFFFFFFFFL));
            for (int i = size - 1; i >= 1; i--) {
                sortedMoveList.add((int) (sortBuffer[i] & 0xFFFFFFFFL));
            }
        } else {
            for (int i = size - 1; i >= 0; i--) {
                sortedMoveList.add((int) (sortBuffer[i] & 0xFFFFFFFFL));
            }
        }

        return sortedMoveList;
    }


    public double evaluateBoard(Engine simulatorEngine, boolean isWhitesTurn, long deadline) {
        if (simulatorEngine.getGameState().isInStateCheckMate()) {
            return CHECKMATE;
        }

        if (simulatorEngine.getGameState().isInStateDraw()) {
            double scoreDiff = simulatorEngine.getGameState().getScore().getScoreDifference();
            if ((isWhitesTurn && scoreDiff > 0) || (!isWhitesTurn && scoreDiff < 0)) {
                return DRAW - 1; // discourage draws when ahead
            } else if ((isWhitesTurn && scoreDiff < 0) || (!isWhitesTurn && scoreDiff > 0)) {
                return DRAW + 1; // prefer draws when behind
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

        // FIX: don't cache timeouts in the capture TT (qsearch TT)
        if (score != EXIT_FLAG) {
            captureTranspositionTable.put(boardStateHash, new CaptureTranspositionTableEntry(score, isWhitesTurn));
        }

        return score;
    }

    private double quiescenceSearch(Engine simulatorEngine, boolean isWhitesTurn,
                                    double alpha, double beta, long deadline, int depth) {
        // FIX: early stop conditions (mirror main search)
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
            // Keeps it conservative to avoid tactical blindness
            final int BIG_DELTA = 1000; // ~queen
            if (standPat + BIG_DELTA < alpha) {
                return alpha;
            }
        }

        // Generate moves: evasions if in check, else captures/promotions
        MoveList moves = inCheck ? simulatorEngine.getAllLegalMoves() : getPossibleCapturesOrPromotions(simulatorEngine);

        // Order them (captures first via MVV-LVA/promotion bonus, killers/history still help)
        ArrayList<Integer> ordered = sortMovesByEfficiency(moves, 0, simulatorEngine.getBoardStateHash());

        for (int m : ordered) {
            simulatorEngine.performMove(m);
            // FIX: propagate timeout BEFORE negation
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

    private double evaluateStaticPosition(GameState gameState, boolean isWhitesTurn, int depth) {

        if (gameState.isInStateCheckMate()) {
            if (log.isDebugEnabled()) {
                log.debug("Checkmate found");
            }
            return CHECKMATE - depth; // -depth to allow faster checkmates
        }
        if (gameState.isInStateDraw()) {
            if (log.isDebugEnabled()) {
                log.debug("DRAW");
            }
            double scoreDiff = gameState.getScore().getScoreDifference();
            if ((isWhitesTurn && scoreDiff > 0) || (!isWhitesTurn && scoreDiff < 0)) {
                return DRAW - 1; // avoid draws when ahead
            } else if ((isWhitesTurn && scoreDiff < 0) || (!isWhitesTurn && scoreDiff > 0)) {
                return DRAW + 1; // accept draws when behind
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

    private void updateTranspositionTable(long boardStateHash, MoveAndScore moveAndScore, int currentDepth) {
        TranspositionTableEntry existingEntry = transpositionTable.get(boardStateHash);
        if (existingEntry == null || existingEntry.depth < currentDepth) {
            transpositionTable.put(boardStateHash, new TranspositionTableEntry(moveAndScore.score, currentDepth, NodeType.EXACT, moveAndScore.move));
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

    private boolean isKillerMove(int depth, int move) {
        // FIX: clamp depth index so killers still work near boundaries
        int depthIndex = Math.max(0, Math.min(depth, killerMoves.length - 1));
        for (int killerMove : killerMoves[depthIndex]) {
            if (killerMove == move) {
                return true;
            }
        }
        return false;
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
