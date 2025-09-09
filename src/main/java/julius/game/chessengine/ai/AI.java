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

    /**
     * Transposition table with LRU eviction policy. The map is synchronized to
     * allow concurrent access from the search threads while keeping the memory
     * footprint bounded.
     */
    private static final Map<Long, TranspositionTableEntry> transpositionTable =
            Collections.synchronizedMap(new LinkedHashMap<Long, TranspositionTableEntry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, TranspositionTableEntry> eldest) {
                    return size() > TRANSPOSITION_TABLE_MAX_ENTRIES;
                }
            });

    /**
     * Separate table for capture searches. Also bounded with LRU eviction to
     * avoid unbounded growth.
     */
    private static final Map<Long, CaptureTranspositionTableEntry> captureTranspositionTable =
            Collections.synchronizedMap(new LinkedHashMap<Long, CaptureTranspositionTableEntry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, CaptureTranspositionTableEntry> eldest) {
                    return size() > CAPTURE_TRANSPOSITION_TABLE_MAX_ENTRIES;
                }
            });

    private final int[][] killerMoves; // 2D array for killer moves, initialized in the constructor
    private final int numKillerMoves = 2;

    /**
     * History heuristic table. Indexed by from and to square (0-63), it stores a
     * score indicating how often a quiet move has caused a beta cutoff. Higher
     * scores mean the move should be ordered earlier in the move list.
     */
    private final int[][] historyTable; // [from][to]

    // Late move reduction configuration
    private static final int LMR_MOVE_INDEX_THRESHOLD = 3; // Moves after this index may be reduced
    private static final int LMR_DEPTH_REDUCTION = 1; // How much to reduce the search depth
    private static final int LMR_MIN_DEPTH = 3; // Apply only when enough depth remains

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

    private int depthThreshold = 1;
    private long lastDepthThresholdAdjustmentTime = 0;
    private final int maxDepth = 18; // Adjust the level of depth according to your requirements

    @Getter
    @Setter
    private long timeLimit; // milliseconds

    private boolean useNullMovePruning = true;
    private boolean useLateMoveReductions = true;
    private long nodesVisited = 0;
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

    public void setUseNullMovePruning(boolean useNullMovePruning) {
        this.useNullMovePruning = useNullMovePruning;
    }

    public void setUseLateMoveReductions(boolean useLateMoveReductions) {
        this.useLateMoveReductions = useLateMoveReductions;
    }

    public long getNodesVisited() {
        return nodesVisited;
    }

    public long getNullMoveCount() {
        return nullMoveCount;
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
        depthThreshold = 1;
        lastDepthThresholdAdjustmentTime = 0;
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
                performMove();
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
    }

    public void performMove() {
        log.debug("DepthThreshold = {}", depthThreshold);
        if (currentBestMove == -1) {
            // Check if the timeLimit has elapsed since the last depth adjustment
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastDepthThresholdAdjustmentTime > timeLimit) {
                // Decrease the depthThreshold if it hasn't been decreased recently
                if (depthThreshold > 1) {
                    depthThreshold--;
                    lastDepthThresholdAdjustmentTime = currentTime; // Update the time of the last adjustment
                }
                // Log an error if no valid current best move is available.
                log.error("No current best move available. Unable to perform a move.");
            }
            log.error("boardStateBeforeCalculation {}, currentBoardHash {}", beforeCalculationBoardState, currentBoardState);
            log.error("WhitesTurn = {}, isEndgame = {}", mainEngine.whitesTurn(), mainEngine.isEndgame());
            log.error("Gamestate = " + mainEngine.getGameState());
            return; // Return the current state without making a move
        }

        if (!MoveHelper.isWhitesMove(currentBestMove) == mainEngine.whitesTurn()) {
            // If the current best move is not valid for the current turn, log an error and return.
            log.debug("Current best move {} is not valid for the current turn.", Move.convertIntToMove(currentBestMove));
            return; // Return the current state without making a move
        }
        log.info("Perform Move");
        mainEngine.performMove(currentBestMove);
        currentBoardState = mainEngine.getBoardStateHash();
        synchronized (calculationLock) {
            calculationLock.notifyAll();
        }
        //currentBestMove = -1; // Reset currentBestMove after performing it
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
            currentBoardState = mainEngine.getBoardStateHash();
            beforeCalculationBoardState = currentBoardState;
            performCalculation();
        }
    }

    private void performCalculation() {
        log.debug(" --- TranspositionTable[{}] --- ", transpositionTable.size());
        decayHistoryTable();
        Engine simulatorEngine = mainEngine.createSimulation();
        long boardStateHash = simulatorEngine.getBoardStateHash();
        log.debug("boardStateBeforeCalculation {}, currentBoardState {}", beforeCalculationBoardState, currentBoardState);

        // Perform calculation only if the board state has actually changed
        boolean isWhite = simulatorEngine.whitesTurn();
        long startTime = System.currentTimeMillis();
        calculateBestMove(simulatorEngine, boardStateHash, isWhite, startTime);

    }


    private void calculateBestMove(Engine simulatorEngine, long boardStateHash, boolean isWhite, long startTime) {
        double bestScore = isWhite ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        int bestMove = mainEngine.getOpeningBook().getRandomMoveForBoardStateHash(boardStateHash); // if none found returns -1
        if (bestMove != -1) {
            currentBestMove = bestMove;
            return;
        }

        try {
            for (int currentDepth = depthThreshold; currentDepth <= maxDepth; currentDepth++) {
                if (shouldStopCalculating(startTime)) {
                    break;
                }

                MoveAndScore moveAndScore = getBestMove(simulatorEngine, isWhite, currentDepth, startTime, timeLimit);
                if (moveAndScore != null && isNewBestMove(moveAndScore, bestScore, isWhite)) {
                    bestScore = moveAndScore.score;
                    bestMove = moveAndScore.move;
                    updateTranspositionTable(boardStateHash, moveAndScore, currentDepth);
                }
            }
        } finally {
            if (bestMove != -1) {
                currentBestMove = bestMove;
            } else {
                depthThreshold--;
            }
            fillCalculatedLine(simulatorEngine); // Ensure this is always called at the end
        }
    }

    private boolean shouldStopCalculating(long startTime) {
        return positionChanged() || timeLimitExceeded(startTime) || Thread.interrupted();
    }

    private void fillCalculatedLine(Engine simulation) {
        long currentBoardHash = simulation.getBoardStateHash();
        List<MoveAndScore> newCalculatedLine = new LinkedList<>();
        Set<Long> seenBoardHashes = new HashSet<>();
        int movesPerformed = 0; // Counter for the number of moves performed

        while (transpositionTable.containsKey(currentBoardHash)) {
            TranspositionTableEntry entry = transpositionTable.get(currentBoardHash);
            if (entry.bestMove == -1 || !seenBoardHashes.add(currentBoardHash)) {
                // Exit if no best move is found or repetition is detected
                break;
            }

            log.debug("[{}] hash exists and move: {}", currentBoardHash, entry);
            newCalculatedLine.add(new MoveAndScore(entry.bestMove, entry.score));

            // Perform the move and increment the counter
            simulation.performMove(entry.bestMove);
            movesPerformed++;
            currentBoardHash = simulation.getBoardStateHash();
        }

        // Undo the moves in reverse order
        for (int i = 0; i < movesPerformed; i++) {
            simulation.undoLastMove();
        }

        this.calculatedLine = new ArrayList<>(newCalculatedLine);

        log.debug("Move Line: {}", newCalculatedLine.stream()
                .map(m -> Move.convertIntToMove(m.move).toString())
                .collect(Collectors.joining(", ")));
        log.debug("");
    }


    private MoveAndScore getBestMove(Engine simulatorEngine, boolean isWhitesTurn, int depth, long startTime, long timeLimit) {
        double alpha = Double.NEGATIVE_INFINITY;
        double beta = Double.POSITIVE_INFINITY;
        int bestMove = -1; // Use an integer to represent the best move
        double bestScore = isWhitesTurn ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;

        ArrayList<Integer> sortedMoves = sortMovesByEfficiency(simulatorEngine.getAllLegalMoves(), isWhitesTurn, depth);

        for (int moveInt : sortedMoves) {

            // Time check at the beginning of each loop iteration
            if (System.currentTimeMillis() - startTime > timeLimit) {
                break;
            }

            simulatorEngine.performMove(moveInt); // Perform move using its integer representation
            double score;

            if (simulatorEngine.getGameState().isInStateCheckMate()) {
                score = isWhitesTurn ? (CHECKMATE - depth) : -(CHECKMATE - depth);
            } else if (simulatorEngine.getGameState().isInStateDraw()) {
                score = 0;
            } else {
                score = alphaBeta(simulatorEngine, depth - 1, alpha, beta, !isWhitesTurn, startTime, timeLimit);
                // Check for time limit exceeded after alphaBeta call
                if (score == EXIT_FLAG || positionChanged()) {
                    log.info("best Position changed");
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
        }

        return bestMove != -1 ? new MoveAndScore(bestMove, bestScore) : null; // Return the best move and score
    }

    /**
     * *
     * 5rkr/pp2Rp2/1b1p1Pb1/3P2Q1/2n3P1/2p5/P4P2/4R1K1 w - - 1 0
     * *
     */
    private double alphaBeta(Engine simulatorEngine, int depth, double alpha, double beta, boolean isWhite, long startTime, long timeLimit) {
        log.debug(" ------------------------- {} ------------------------- ", depth);
        nodesVisited++;
        // Check for time limit exceeded
        if (System.currentTimeMillis() - startTime > timeLimit) {
            return EXIT_FLAG;
        }

        long boardHash = simulatorEngine.getBoardStateHash();

        if (simulatorEngine.getGameState().isInStateDraw()) {
            return 0;
        }

        if (depth == 0 || simulatorEngine.getGameState().isGameOver()) {
            double eval = evaluateBoard(simulatorEngine, isWhite, startTime, timeLimit);
            log.trace("eval {}, alpha {}, beta {}, depth: {}, startTime {}, timeLimit {}, isWhite {}", eval, alpha, beta, depth, System.currentTimeMillis() - startTime, timeLimit, isWhite);
            if (!isWhite) {
                eval = -eval;
            }
            return eval;
        }

        TranspositionTableEntry entry = transpositionTable.get(boardHash);

        if (entry != null && entry.depth > depth) {
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

        if (useNullMovePruning && depth >= 3 && !isSideInCheck(simulatorEngine, isWhite) && !simulatorEngine.isEndgame()) {
            int ep = simulatorEngine.doNullMove();
            nullMoveCount++;
            double nullScore = alphaBeta(simulatorEngine, depth - 1 - 2, alpha, beta, !isWhite, startTime, timeLimit);
            simulatorEngine.undoNullMove(ep);

            if (isWhite && nullScore >= beta) {
                return beta;
            } else if (!isWhite && nullScore <= alpha) {
                return alpha;
            }
        }

        double alphaOriginal = alpha; // Store the original alpha value
        double betaOriginal = beta;   // Store the original beta value

        MoveList moves = simulatorEngine.getAllLegalMoves();

        if (isWhite) {
            return maximizer(simulatorEngine, depth, alpha, beta, isWhite, boardHash, alphaOriginal, moves, startTime, timeLimit);
        } else {
            return minimizer(simulatorEngine, depth, alpha, beta, isWhite, boardHash, betaOriginal, moves, startTime, timeLimit);
        }
    }

    private boolean isSideInCheck(Engine engine, boolean isWhite) {
        GameStateEnum state = engine.getGameState().getState();
        return (isWhite && state == GameStateEnum.WHITE_IN_CHECK) || (!isWhite && state == GameStateEnum.BLACK_IN_CHECK);
    }

    private double maximizer(Engine simulatorEngine, int depth, double alpha, double beta, boolean isWhite, long boardHash, double alphaOriginal, MoveList moves, long startTime, long timeLimit) {
        long start = System.nanoTime(); // Start timing
        double maxEval = Double.NEGATIVE_INFINITY;
        int bestMoveAtThisNode = -1; // Variable to track the best move at this node

        ArrayList<Integer> orderedMoves = sortMovesByEfficiency(moves, isWhite, depth);
        for (int index = 0; index < orderedMoves.size(); index++) {
            int move = orderedMoves.get(index);
            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();

            double eval;
            TranspositionTableEntry entry = transpositionTable.get(newBoardHash);

            if (entry != null && entry.depth >= depth) {
                eval = entry.score; // Use the score from the transposition table
            } else {
                int nextDepth = depth - 1;
                boolean reduced = false;
                if (useLateMoveReductions && depth >= LMR_MIN_DEPTH && index >= LMR_MOVE_INDEX_THRESHOLD
                        && !MoveHelper.isCapture(move) && !isKillerMove(depth, move)) {
                    nextDepth = depth - 1 - LMR_DEPTH_REDUCTION;
                    reduced = true;
                }

                eval = alphaBeta(simulatorEngine, nextDepth, alpha, beta, !isWhite, startTime, timeLimit);

                if (eval == EXIT_FLAG || positionChanged()) {
                    simulatorEngine.undoLastMove();
                    log.info("maxi Position changed");
                    return EXIT_FLAG;
                }

                if (reduced && eval > alpha) {
                    eval = alphaBeta(simulatorEngine, depth - 1, alpha, beta, !isWhite, startTime, timeLimit);
                    if (eval == EXIT_FLAG || positionChanged()) {
                        simulatorEngine.undoLastMove();
                        log.info("maxi Position changed");
                        return EXIT_FLAG;
                    }
                }
            }

            log.debug("DEPTH: " + depth + " --- " + Move.convertIntToMove(move));
            long endTime = System.nanoTime();
            log.debug("DEPTH: " + depth);
            log.debug("--> [+] Time taken for maximizer: {} ms", (endTime - start) / 1e6);

            simulatorEngine.undoLastMove();

            if (eval > maxEval) { // Found a better evaluation
                maxEval = eval;
                bestMoveAtThisNode = move; // Update the best move
            }

            alpha = Math.max(alpha, eval);
            if (beta <= alpha) {
                updateKillerMoves(depth, move);
                incrementHistory(move, depth);
                log.debug(" Maxi New Killer Move is {}", Move.convertIntToMove(move));
                break; // Alpha-beta pruning
            }
        }

        // After the for loop, update the transposition table with the best move
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
                             boolean isWhite, long boardHash,
                             double betaOriginal, MoveList moves, long startTime,
                             long timeLimit) {
        long start = System.nanoTime(); // Start timing
        double minEval = Double.POSITIVE_INFINITY;
        int bestMoveAtThisNode = -1; // Track the best move at this node

        ArrayList<Integer> orderedMoves = sortMovesByEfficiency(moves, isWhite, depth);
        for (int index = 0; index < orderedMoves.size(); index++) {
            int move = orderedMoves.get(index);
            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();
            double eval;
            TranspositionTableEntry entry = transpositionTable.get(newBoardHash);

            if (entry != null && entry.depth >= depth) {
                eval = entry.score;
            } else {
                int nextDepth = depth - 1;
                boolean reduced = false;
                if (useLateMoveReductions && depth >= LMR_MIN_DEPTH && index >= LMR_MOVE_INDEX_THRESHOLD
                        && !MoveHelper.isCapture(move) && !isKillerMove(depth, move)) {
                    nextDepth = depth - 1 - LMR_DEPTH_REDUCTION;
                    reduced = true;
                }

                eval = alphaBeta(simulatorEngine, nextDepth, alpha, beta, !isWhite, startTime, timeLimit);

                if (eval == EXIT_FLAG || positionChanged()) {
                    log.info("mini Position changed");
                    simulatorEngine.undoLastMove();
                    return EXIT_FLAG;
                }

                if (reduced && eval < beta) {
                    eval = alphaBeta(simulatorEngine, depth - 1, alpha, beta, !isWhite, startTime, timeLimit);
                    if (eval == EXIT_FLAG || positionChanged()) {
                        log.info("mini Position changed");
                        simulatorEngine.undoLastMove();
                        return EXIT_FLAG;
                    }
                }
            }

            long endTime = System.nanoTime();
            log.debug("DEPTH: " + depth + " --- " + Move.convertIntToMove(move));
            log.debug("<-- [-] Time taken for minimizer: {} ms", (endTime - start) / 1e6);

            simulatorEngine.undoLastMove();

            if (eval < minEval) {
                minEval = eval;
                bestMoveAtThisNode = move; // Update the best move at this node
            }

            beta = Math.min(beta, eval);
            if (alpha >= beta) {
                updateKillerMoves(depth, move);
                incrementHistory(move, depth);
                log.debug("Mini New Killer Move is {}", Move.convertIntToMove(move));
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


    ArrayList<Integer> sortMovesByEfficiency(MoveList moves, boolean isWhite, int currentDepth) {
        int size = moves.size();

        int[] moveBuffer = new int[size];
        int[] scoreBuffer = new int[size];

        for (int i = 0; i < size; i++) {
            int moveInt = moves.getMove(i);
            int score = 0;

            boolean isKiller = false;
            for (int killerMove : killerMoves[currentDepth]) {
                if (moveInt == killerMove) {
                    score = KILLER_MOVE_SCORE;
                    isKiller = true;
                    break;
                }
            }

            if (!isKiller) {
                int mvvLvaScore = calculateMvvLvaScore(moveInt);
                if (mvvLvaScore != 0) {
                    score = mvvLvaScore;
                } else {
                    int from = moveInt & 0x3F;
                    int to = (moveInt >> 6) & 0x3F;
                    score = historyTable[from][to];
                }
            }

            moveBuffer[i] = moveInt;
            scoreBuffer[i] = score;
        }

        for (int i = 1; i < size; i++) {
            int keyMove = moveBuffer[i];
            int keyScore = scoreBuffer[i];
            int j = i - 1;
            while (j >= 0 && scoreBuffer[j] < keyScore) {
                moveBuffer[j + 1] = moveBuffer[j];
                scoreBuffer[j + 1] = scoreBuffer[j];
                j--;
            }
            moveBuffer[j + 1] = keyMove;
            scoreBuffer[j + 1] = keyScore;
        }

        ArrayList<Integer> sortedMoveList = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            sortedMoveList.add(moveBuffer[i]);
        }

        return sortedMoveList;
    }

    public double evaluateBoard(Engine simulatorEngine, boolean isWhitesTurn, long startTime, long timeLimit) {
        if (simulatorEngine.getGameState().isInStateCheckMate()) {
            return CHECKMATE;
        }

        if (simulatorEngine.getGameState().isInStateDraw()) {
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

        double score = quiescenceSearch(simulatorEngine, isWhitesTurn, alpha, beta, startTime, timeLimit, 0);
        captureTranspositionTable.put(boardStateHash, new CaptureTranspositionTableEntry(score, isWhitesTurn));

        return score;
    }

    private double quiescenceSearch(Engine simulatorEngine, boolean isWhitesTurn, double alpha, double beta, long startTime, long timeLimit, int depth) {
        if (System.currentTimeMillis() - startTime > timeLimit) {
            log.debug("timeout");
            return AI.EXIT_FLAG; // Timeout
        }

        double standPat = evaluateStaticPosition(simulatorEngine.getGameState(), isWhitesTurn, depth);
        if (standPat >= beta) {
            return beta; // Fail-hard beta cutoff
        }
        if (alpha < standPat) {
            alpha = standPat; // Delta pruning
        }

        MoveList moves = getPossibleCapturesOrPromotions(simulatorEngine);
        for (int i = 0; i < moves.size(); i++) {
            simulatorEngine.performMove(moves.getMove(i));
            double score = -quiescenceSearch(simulatorEngine, !isWhitesTurn, -beta, -alpha, startTime, timeLimit, ++depth);
            simulatorEngine.undoLastMove();

            if (score >= beta) {
                return beta; // Beta cutoff
            }
            if (score > alpha) {
                alpha = score; // Found a better move
            }
        }
        return alpha; // Best score in the subtree
    }

    private double evaluateStaticPosition(GameState gameState, boolean isWhitesTurn, int depth) {

        if (gameState.isInStateCheckMate()) {
            log.debug("Checkmate found");
            return CHECKMATE - depth; // -depth to allow faster checkmates
        }
        if (gameState.isInStateDraw()) {
            log.debug("DRAW");
            return DRAW;
        }
        double scoreDifference = gameState.getScore().getScoreDifference();

        log.debug("Evaluate static position score {}, {} ", isWhitesTurn ? scoreDifference : -scoreDifference, isWhitesTurn ? "WHITE" : "BLACK");
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

    private boolean timeLimitExceeded(long startTime) {
        return System.currentTimeMillis() - startTime > timeLimit;
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
        int numKillerMoves = killerMoves[depth].length; // Get the number of killer moves for this depth

        // Check if the move is already in the killer moves array
        for (int i = 0; i < numKillerMoves; i++) {
            if (killerMoves[depth][i] == move) {
                return; // If move is already a killer move, no need to update
            }
        }

        // Shift existing killer moves down and insert the new move at the beginning
        for (int i = numKillerMoves - 1; i > 0; i--) {
            killerMoves[depth][i] = killerMoves[depth][i - 1];
        }
        killerMoves[depth][0] = move; // Insert new killer move at the top
    }

    private boolean isKillerMove(int depth, int move) {
        if (depth >= killerMoves.length) {
            return false;
        }
        for (int killerMove : killerMoves[depth]) {
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
