package julius.game.chessengine.ai;

import julius.game.chessengine.board.Move;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.utils.Score;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    // Late move reduction configuration
    private static final int LMR_MOVE_INDEX_THRESHOLD = 3; // Moves after this index may be reduced
    private static final int LMR_DEPTH_REDUCTION = 1; // How much to reduce the search depth
    private static final int LMR_MIN_DEPTH = 3; // Apply only when enough depth remains
    private static final int ASPIRATION_WINDOW = 50; // Initial aspiration window size

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

    private boolean useNullMovePruning = true;
    private boolean useLateMoveReductions = true;
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
            SearchPosition pos = new SearchPosition(mainEngine.cloneBitBoard());
            long boardStateHash = pos.hash();
            log.debug("boardStateBeforeCalculation {}, currentBoardState {}", beforeCalculationBoardState, currentBoardState);

            boolean isWhite = pos.whiteToMove();
            long deadline = System.nanoTime() + timeLimit * 1_000_000L;
            calculateBestMove(pos, boardStateHash, isWhite, deadline);
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

    private void calculateBestMove(SearchPosition pos, long boardHash, boolean isWhite, long deadline) {
        double bestScore = isWhite ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        int bestMove = mainEngine.getOpeningBook().getRandomMoveForBoardStateHash(boardHash);
        if (bestMove != -1) {
            currentBestMove = bestMove;
            fillCalculatedLineFromTT(pos);
            return;
        }

        double alpha = Double.NEGATIVE_INFINITY;
        double beta = Double.POSITIVE_INFINITY;

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (shouldStopCalculating(deadline)) {
                break;
            }

            MoveAndScore ms = getBestMove(pos, isWhite, depth, deadline, alpha, beta);
            if (ms == null) {
                break;
            }

            bestMove = ms.move;
            bestScore = ms.score;
            updateTranspositionTable(pos.hash(), ms, depth);

            alpha = ms.score - ASPIRATION_WINDOW;
            beta  = ms.score + ASPIRATION_WINDOW;
        }

        if (bestMove == -1) {
            MoveList ml = pos.pseudoMoves();
            if (ml.size() > 0) {
                bestMove = ml.getMove(0);
            }
        }
        currentBestMove = bestMove;
        fillCalculatedLineFromTT(pos);
    }

    private boolean shouldStopCalculating(long deadline) {
        return positionChanged() || timeLimitExceeded(deadline) || Thread.currentThread().isInterrupted();
    }

    private void fillCalculatedLineFromTT(SearchPosition pos) {
        long currentHash = pos.hash();
        List<MoveAndScore> newCalculatedLine = new LinkedList<>();
        Set<Long> seen = new HashSet<>();
        List<Integer> performed = new ArrayList<>();

        TranspositionTableEntry entry;
        while ((entry = transpositionTable.get(currentHash)) != null) {
            if (entry.bestMove == -1 || !seen.add(currentHash)) {
                break;
            }
            newCalculatedLine.add(new MoveAndScore(entry.bestMove, entry.score));
            pos.make(entry.bestMove);
            performed.add(entry.bestMove);
            currentHash = pos.hash();
        }

        for (int i = performed.size() - 1; i >= 0; i--) {
            pos.undo(performed.get(i));
        }

        this.calculatedLine = new ArrayList<>(newCalculatedLine);
    }

    private MoveAndScore getBestMove(SearchPosition pos, boolean isWhite, int depth, long deadline,
                                     double alpha, double beta) {
        int bestMove = -1;
        double bestScore = isWhite ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;

        long hash = pos.hash();
        ArrayList<Integer> ordered = sortMovesByEfficiency(pos.pseudoMoves(), depth, hash);

        for (int move : ordered) {
            if (Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
                break;
            }

            pos.make(move);
            if (pos.inCheck(isWhite)) {
                pos.undo(move);
                continue;
            }

            double score = alphaBeta(pos, depth - 1, alpha, beta, !isWhite, deadline);
            pos.undo(move);
            if (score == EXIT_FLAG) {
                return null;
            }

            if (isWhite ? score > bestScore : score < bestScore) {
                bestScore = score;
                bestMove = move;
            }
            if (isWhite) {
                alpha = Math.max(alpha, score);
            } else {
                beta = Math.min(beta, score);
            }
            if (alpha >= beta) {
                updateKillerMoves(depth, move);
                incrementHistory(move, depth);
                break;
            }
        }
        return bestMove != -1 ? new MoveAndScore(bestMove, bestScore) : null;
    }

    private double alphaBeta(SearchPosition pos, int depth, double alpha, double beta,
                             boolean isWhite, long deadline) {
        nodesVisited++;
        if (Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
            return EXIT_FLAG;
        }

        long key = pos.hash();
        TranspositionTableEntry tt = transpositionTable.get(key);
        if (tt != null && tt.depth >= depth) {
            if (tt.nodeType == NodeType.EXACT) return tt.score;
            if (tt.nodeType == NodeType.LOWERBOUND) alpha = Math.max(alpha, tt.score);
            if (tt.nodeType == NodeType.UPPERBOUND) beta  = Math.min(beta,  tt.score);
            if (alpha >= beta) return tt.score;
        }

        if (depth == 0) {
            double eval = evaluateBoard(pos, isWhite, deadline);
            return eval == EXIT_FLAG ? EXIT_FLAG : eval;
        }

        if (useNullMovePruning && depth >= 3 && !pos.inCheck(isWhite) && !pos.isEndgame()) {
            int ep = pos.doNullMove();
            nullMoveCount++;
            double nullScore = alphaBeta(pos, depth - 1 - 2, alpha, beta, !isWhite, deadline);
            pos.undoNullMove(ep);
            if (nullScore == EXIT_FLAG) return EXIT_FLAG;
            if (isWhite && nullScore >= beta) return beta;
            if (!isWhite && nullScore <= alpha) return alpha;
        }

        double best = isWhite ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        MoveList ml = pos.pseudoMoves();
        ArrayList<Integer> ordered = sortMovesByEfficiency(ml, depth, key);

        double alpha0 = alpha, beta0 = beta;
        int bestMove = -1;

        for (int idx = 0; idx < ordered.size(); idx++) {
            int move = ordered.get(idx);
            pos.make(move);
            if (pos.inCheck(isWhite)) {
                pos.undo(move);
                continue;
            }

            int newDepth = depth - 1;
            double score;
            boolean reduced = false;
            if (useLateMoveReductions && depth >= LMR_MIN_DEPTH && idx >= LMR_MOVE_INDEX_THRESHOLD) {
                score = alphaBeta(pos, newDepth - LMR_DEPTH_REDUCTION, alpha, beta, !isWhite, deadline);
                reduced = true;
            } else {
                score = alphaBeta(pos, newDepth, alpha, beta, !isWhite, deadline);
            }

            if (reduced && score != EXIT_FLAG &&
                    ((isWhite && score > alpha) || (!isWhite && score < beta))) {
                score = alphaBeta(pos, newDepth, alpha, beta, !isWhite, deadline);
            }

            pos.undo(move);
            if (score == EXIT_FLAG) return EXIT_FLAG;

            if (isWhite) {
                if (score > best) { best = score; bestMove = move; }
                alpha = Math.max(alpha, score);
            } else {
                if (score < best) { best = score; bestMove = move; }
                beta = Math.min(beta, score);
            }

            if (alpha >= beta) {
                updateKillerMoves(depth, move);
                incrementHistory(move, depth);
                break;
            }
        }

        if (bestMove == -1) {
            return pos.inCheck(isWhite) ? (isWhite ? -CHECKMATE + depth : CHECKMATE - depth) : DRAW;
        }

        NodeType type = (best <= alpha0) ? NodeType.UPPERBOUND : (best >= beta0) ? NodeType.LOWERBOUND : NodeType.EXACT;
        transpositionTable.put(key, new TranspositionTableEntry(best, depth, type, bestMove));
        return best;
    }

    private double evaluateBoard(SearchPosition pos, boolean isWhitesTurn, long deadline) {
        long boardHash = pos.hash();
        CaptureTranspositionTableEntry entry = captureTranspositionTable.get(boardHash);
        if (entry != null && entry.isWhite() == isWhitesTurn) {
            return entry.getScore();
        }

        double score = quiescenceSearch(pos, isWhitesTurn,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, deadline, 0);

        if (score != EXIT_FLAG) {
            captureTranspositionTable.put(boardHash, new CaptureTranspositionTableEntry(score, isWhitesTurn));
        }
        return score;
    }

    private double quiescenceSearch(SearchPosition pos, boolean isWhitesTurn,
                                    double alpha, double beta, long deadline, int depth) {
        if (Thread.currentThread().isInterrupted() || positionChanged() || System.nanoTime() > deadline) {
            return EXIT_FLAG;
        }

        boolean inCheck = pos.inCheck(isWhitesTurn);
        double standPat = staticEval(pos, isWhitesTurn, depth);

        if (!inCheck) {
            if (standPat >= beta) return beta;
            if (standPat > alpha) alpha = standPat;
        }

        MoveList moves = inCheck ? pos.pseudoMoves() : pos.capturesAndPromotions();
        ArrayList<Integer> ordered = sortMovesByEfficiency(moves, depth, pos.hash());
        if (ordered.isEmpty()) {
            return inCheck ? (isWhitesTurn ? -CHECKMATE + depth : CHECKMATE - depth) : DRAW;
        }

        for (int move : ordered) {
            pos.make(move);
            if (pos.inCheck(isWhitesTurn)) {
                pos.undo(move);
                continue;
            }
            double score = -quiescenceSearch(pos, !isWhitesTurn, -beta, -alpha, deadline, depth + 1);
            pos.undo(move);
            if (score == EXIT_FLAG) return EXIT_FLAG;
            if (score >= beta) return beta;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    private double staticEval(SearchPosition pos, boolean isWhite, int depth) {
        Score s = new Score();
        s.initializeScore(pos.view());
        double v = s.getScoreDifference();
        return isWhite ? v : -v;
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
