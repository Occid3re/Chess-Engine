package julius.game.chessengine.ai;

import julius.game.chessengine.board.Move;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.utils.Score;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final ConcurrentHashMap<Long, TranspositionTableEntry> transpositionTable = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, CaptureTranspositionTableEntry> captureTranspositionTable = new ConcurrentHashMap<>();

    private final int[][] killerMoves; // 2D array for killer moves, initialized in the constructor
    private final int numKillerMoves = 2;

    private ScheduledExecutorService scheduler;
    private Thread calculationThread;

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
        //currentBestMove = -1; // Reset currentBestMove after performing it
    }

    private void calculateLine() {
        log.debug("keepCalculating: {}, interrupted: {}", keepCalculating, Thread.currentThread().isInterrupted());
        while (keepCalculating && !Thread.currentThread().isInterrupted()) {
            if (positionChanged()) {
                currentBoardState = mainEngine.getBoardStateHash();
                beforeCalculationBoardState = mainEngine.getBoardStateHash();
                performCalculation();
            }
        }
    }

    private void performCalculation() {
        log.debug(" --- TranspositionTable[{}] --- ", transpositionTable.size());
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

        ArrayList<Integer> sortedMoves = sortMovesByEfficiency(simulatorEngine.getAllLegalMoves(), simulatorEngine, isWhitesTurn, depth, startTime, timeLimit);

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


        double alphaOriginal = alpha; // Store the original alpha value
        double betaOriginal = beta;   // Store the original beta value

        MoveList moves = simulatorEngine.getAllLegalMoves();

        if (isWhite) {
            return maximizer(simulatorEngine, depth, alpha, beta, isWhite, boardHash, alphaOriginal, moves, startTime, timeLimit);
        } else {
            return minimizer(simulatorEngine, depth, alpha, beta, isWhite, boardHash, betaOriginal, moves, startTime, timeLimit);
        }
    }

    private double maximizer(Engine simulatorEngine, int depth, double alpha, double beta, boolean isWhite, long boardHash, double alphaOriginal, MoveList moves, long startTime, long timeLimit) {
        long start = System.nanoTime(); // Start timing
        double maxEval = Double.NEGATIVE_INFINITY;
        int bestMoveAtThisNode = -1; // Variable to track the best move at this node

        for (int move : sortMovesByEfficiency(moves, simulatorEngine, isWhite, depth, startTime, timeLimit)) {
            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();

            double eval;
            TranspositionTableEntry entry = transpositionTable.get(newBoardHash);

            if (entry != null && entry.depth >= depth) {
                eval = entry.score; // Use the score from the transposition table
            } else {
                eval = alphaBeta(simulatorEngine, depth - 1, alpha, beta, !isWhite, startTime, timeLimit);

                if (eval == EXIT_FLAG || positionChanged()) {
                    // If time limit exceeded, exit the loop
                    simulatorEngine.undoLastMove();
                    log.info("maxi Position changed");
                    return EXIT_FLAG;
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

        for (int move : sortMovesByEfficiency(moves, simulatorEngine, isWhite, depth, startTime, timeLimit)) {
            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();
            double eval;
            TranspositionTableEntry entry = transpositionTable.get(newBoardHash);

            if (entry != null && entry.depth >= depth) {
                eval = entry.score;
            } else {
                eval = alphaBeta(simulatorEngine, depth - 1, alpha, beta, !isWhite, startTime, timeLimit);

                if (eval == EXIT_FLAG || positionChanged()) {
                    log.info("mini Position changed");
                    simulatorEngine.undoLastMove();
                    return EXIT_FLAG;
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


    private ArrayList<Integer> sortMovesByEfficiency(MoveList moves, Engine simulatorEngine, boolean isWhite, int currentDepth, long startTime, long timeLimit) {
        Map<Integer, Double> scoreCache = new HashMap<>();
        PriorityQueue<Integer> sortedMoves = new PriorityQueue<>(
                Comparator.comparingDouble((Integer moveInt) -> {
                    // Check if the move is a killer move and prioritize it

                    for (int killerMove : killerMoves[currentDepth]) {
                        if (moveInt == killerMove) {
                            log.debug("KILLER_MOVE_SCORE" + KILLER_MOVE_SCORE);
                            return KILLER_MOVE_SCORE; //adjust as needed
                        }
                    }

                    int mvvLvaScore = calculateMvvLvaScore(moveInt);
                    if (mvvLvaScore != 0) {
                        return mvvLvaScore; // Prioritize based on MVV-LVA score
                    }

                    if (scoreCache.containsKey(moveInt)) {
                        return scoreCache.get(moveInt);
                    }

                    // If not a killer move, proceed with existing scoring method
                    Long boardStateHash = simulatorEngine.getBoardStateHashAfterMove(moveInt);
                    TranspositionTableEntry entry = transpositionTable.get(boardStateHash);
                    if (entry != null && entry.depth >= currentDepth) {
                        return isWhite ? entry.score : -entry.score;
                    } else {
                        simulatorEngine.performMove(moveInt);
                        double score = evaluateBoard(simulatorEngine, isWhite, startTime, timeLimit);
                        simulatorEngine.undoLastMove();
                        scoreCache.put(moveInt, score);
                        return score;
                    }
                }).reversed()
        );

        for (int i = 0; i < moves.size(); i++) {
            sortedMoves.add(moves.getMove(i));
        }

        ArrayList<Integer> sortedMoveList = new ArrayList<>();
        while (!sortedMoves.isEmpty()) {
            sortedMoveList.add(sortedMoves.poll());
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

    private int calculateMvvLvaScore(int move) {
        if (!MoveHelper.isCapture(move)) {
            return 0; // Not a capture move
        }
        int victimValue = Score.getPieceValue(MoveHelper.deriveCapturedPieceTypeBits(move));
        int attackerValue = Score.getPieceValue(MoveHelper.derivePieceTypeBits(move));
        return victimValue - attackerValue;
    }
}
