package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.Move;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.tuning.AiTuning;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Produces a verbose diagnostic trace for mate-threat positions where the engine must
 * find a single defensive resource (e.g. {@code f4} in the supplied position).
 *
 * <p>The diagnostics record move ordering, alpha/beta bounds, elapsed time and node
 * counts for every root move considered at each iterative-deepening depth. The report
 * also inspects the transposition table afterwards so we can confirm whether critical
 * moves (like {@code f4}) were searched, pruned or skipped due to time pressure.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AITest_MateThreatDiagnostics {

    private static Stream<Object[]> mateThreatScenarios() {
        return Stream.of(
                new Object[]{
                        "rnb1k2r/2bp3p/3qpp1n/P5p1/Q1P3P1/PN6/4BPNP/R4RK1 w - - 1 24",
                        List.of("f4")
                },
                new Object[]{
                        "rn2kb1r/pp2pppp/5q2/1p6/2b3Q1/4B2P/PP3PP1/RN2K1NR b KQkq - 1 12",
                        List.of("Nc6", "e6")
                },
                new Object[]{
                        "r1bqk2r/ppp2ppp/2n1p3/3pP3/3P4/2bB1N2/P1P2PPP/R1BQ1RK1 w kq - 0 9",
                        List.of("Rb1")
                }
        );
    }

    @BeforeEach
    void ensureLocale() {
        Locale.setDefault(Locale.ROOT);
    }

    @DisplayName("Detailed diagnostic trace for mate-in-one defence scenarios")
    @ParameterizedTest(name = "Scenario {index}: {0}")
    @MethodSource("mateThreatScenarios")
    void analyseMateThreat(String fen, List<String> lifesavingMoves) throws Exception {
        Engine engine = new Engine();
        engine.importBoardFromFen(fen);

        int searchThreads = configuredThreadCount("mateThreat.searchThreads",
                configuredThreadCount("chessengine.searchThreads", 1));
        int lazySmpThreads = configuredThreadCount("mateThreat.lazySmpThreads",
                configuredThreadCount("chessengine.lazySmpThreads", 1));

        AiTuning tuning = AiTuning.builder()
                .searchThreads(searchThreads)
                .lazySmpThreads(lazySmpThreads)
                .hashSizeMb(64)
                .maxDepth(14)
                .timeLimitMillis(1000)
                .nullMovePruning(true)
                .build();

        DiagnosticAI ai = new DiagnosticAI(engine, tuning);

        long start = System.nanoTime();
        MoveAndScore result = ai.searchBestMoveBlocking(1500);
        Duration wallClock = Duration.ofNanos(System.nanoTime() - start);

        assertFalse(ai.getDepthTraces().isEmpty(),
                "The diagnostic AI must record at least one search iteration");

        String report = ai.buildDiagnosticsReport(fen, lifesavingMoves, result, wallClock);
        assertNotNull(report, "The diagnostic report should never be null");

        // Emit to STDOUT so the engineer can inspect the detailed breakdown when the test runs.
        System.out.println(report);
    }

    private static int configuredThreadCount(String property, int defaultValue) {
        return Math.max(1, Integer.getInteger(property, defaultValue));
    }

    /**
     * AI subclass that mirrors {@link AI#searchRootMoves} but captures rich diagnostics
     * about the root move ordering and alpha-beta behaviour. The heavy lifting happens
     * entirely in this test helper so production code remains untouched.
     */
    private static final class DiagnosticAI extends AI {

        private final Method sortMovesMethod;
        private final Method maybeRotateRootMovesMethod;
        private final Method abortRequestedMethod;
        private final Method alphaBetaMethod;
        private final Method evaluateStaticPositionMethod;
        private final Method isBetterScoreMethod;
        private final Method prepareHelperHeuristicsMethod;
        private final Method mergeThreadHeuristicsMethod;
        private final Method createCandidateMethod;
        private final Class<?> heuristicsClass;
        private final Method heuristicsHasUpdatesMethod;
        private final Method heuristicsResetUpdatesMethod;
        private final Field searchPoolField;
        private final int rootParallelLimit;
        private final Field nodesVisitedField;
        private final Field nullMoveCountField;
        private final Field transpositionTableField;
        private final Field calculatedLineField;
        private final Map<Integer, AtomicInteger> depthAttemptCounter = new ConcurrentHashMap<>();
        private final List<DepthTrace> depthTraces = Collections.synchronizedList(new ArrayList<>());

        DiagnosticAI(Engine engine, AiTuning tuning) {
            super(engine, tuning);
            try {
                sortMovesMethod = AI.class.getDeclaredMethod("sortMovesByEfficiency", IntArrayList.class,
                        int.class, long.class, int.class, Engine.class);
                sortMovesMethod.setAccessible(true);

                isBetterScoreMethod = AI.class.getDeclaredMethod("isBetterScore", boolean.class, double.class, double.class);
                isBetterScoreMethod.setAccessible(true);

                maybeRotateRootMovesMethod = AI.class.getDeclaredMethod("maybeRotateRootMoves", IntArrayList.class,
                        SplittableRandom.class);
                maybeRotateRootMovesMethod.setAccessible(true);

                abortRequestedMethod = AI.class.getDeclaredMethod("abortRequested", long.class);
                abortRequestedMethod.setAccessible(true);

                alphaBetaMethod = AI.class.getDeclaredMethod("alphaBeta", Engine.class, int.class, double.class,
                        double.class, boolean.class, long.class, int.class, int.class, int.class);
                alphaBetaMethod.setAccessible(true);

                evaluateStaticPositionMethod = AI.class.getDeclaredMethod("evaluateStaticPosition", GameState.class,
                        boolean.class, int.class);
                evaluateStaticPositionMethod.setAccessible(true);

                nodesVisitedField = AI.class.getDeclaredField("nodesVisited");
                nodesVisitedField.setAccessible(true);

                nullMoveCountField = AI.class.getDeclaredField("nullMoveCount");
                nullMoveCountField.setAccessible(true);

                transpositionTableField = AI.class.getDeclaredField("transpositionTable");
                transpositionTableField.setAccessible(true);

                calculatedLineField = AI.class.getDeclaredField("calculatedLine");
                calculatedLineField.setAccessible(true);

                prepareHelperHeuristicsMethod = AI.class.getDeclaredMethod("prepareHelperHeuristics",
                        SearchTask.class, int.class);
                prepareHelperHeuristicsMethod.setAccessible(true);

                heuristicsClass = prepareHelperHeuristicsMethod.getReturnType();

                mergeThreadHeuristicsMethod = AI.class.getDeclaredMethod("mergeThreadHeuristics", heuristicsClass);
                mergeThreadHeuristicsMethod.setAccessible(true);

                heuristicsHasUpdatesMethod = heuristicsClass.getDeclaredMethod("hasUpdates");
                heuristicsHasUpdatesMethod.setAccessible(true);

                heuristicsResetUpdatesMethod = heuristicsClass.getDeclaredMethod("resetUpdates");
                heuristicsResetUpdatesMethod.setAccessible(true);

                createCandidateMethod = AI.class.getDeclaredMethod("createCandidate", int.class, double.class);
                createCandidateMethod.setAccessible(true);

                searchPoolField = AI.class.getDeclaredField("searchPool");
                searchPoolField.setAccessible(true);

                Field rootParallelLimitField = AI.class.getDeclaredField("ROOT_PARALLEL_LIMIT");
                rootParallelLimitField.setAccessible(true);
                rootParallelLimit = rootParallelLimitField.getInt(null);
            } catch (NoSuchMethodException | NoSuchFieldException | IllegalAccessException e) {
                throw new IllegalStateException("Unable to bootstrap DiagnosticAI instrumentation", e);
            }
        }

        @SuppressWarnings("unchecked")
        private TranspositionTable<TranspositionTableEntry> transpositionTable() {
            try {
                return (TranspositionTable<TranspositionTableEntry>) transpositionTableField.get(this);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        @SuppressWarnings("unchecked")
        private List<MoveAndScore> principalVariation() {
            try {
                Object value = calculatedLineField.get(this);
                if (value instanceof List<?> list) {
                    return (List<MoveAndScore>) list;
                }
                return List.of();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        protected RootSearchResult searchRootMoves(Engine simulatorEngine, SearchTask task, int depth,
                                                   double alpha, double beta, SplittableRandom rng) {
            DepthTrace trace = beginDepthTrace(task, depth, alpha, beta);
            if (getSearchThreads() > 1) {
                return searchRootMovesParallel(simulatorEngine, task, depth, alpha, beta, rng, trace);
            }
            return searchRootMovesSequential(simulatorEngine, task, depth, alpha, beta, rng, trace);
        }

        private RootSearchResult searchRootMovesSequential(Engine simulatorEngine, SearchTask task, int depth,
                                                          double alpha, double beta, SplittableRandom rng,
                                                          DepthTrace trace) {
            long deadline = task.getDeadline();
            boolean isWhite = task.isWhiteToMove();

            try {
                IntArrayList legal = simulatorEngine.getAllLegalMoves();
                IntArrayList ordered = (IntArrayList) sortMovesMethod.invoke(this, legal, depth,
                        simulatorEngine.getBoardStateHash(), -1, simulatorEngine);

                maybeRotateRootMovesMethod.invoke(this, ordered, rng);

                if (ordered.isEmpty()) {
                    trace.addNote("No legal moves available at the root");
                    trace.finish(alpha, beta, null);
                    return RootSearchResult.completed(null);
                }

                double bestScore = isWhite ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
                int bestMove = -1;
                boolean aborted = false;

                for (int index = 0; index < ordered.size(); index++) {
                    if (abortRequested(deadline)) {
                        trace.addNote("Abort requested before analysing move index " + index);
                        aborted = true;
                        break;
                    }

                    int moveInt = ordered.getInt(index);
                    long nodesBefore = nodesVisited();
                    long nanosBefore = System.nanoTime();
                    double alphaBefore = alpha;
                    double betaBefore = beta;

                    simulatorEngine.performMove(moveInt);

                    EvaluationResult evaluation = evaluateAfterRootMove(simulatorEngine, depth, alpha, beta,
                            isWhite, deadline, moveInt);

                    simulatorEngine.undoLastMove();

                    long nanosAfter = System.nanoTime();
                    long nodesAfter = nodesVisited();

                    if (evaluation.hasScore() && isBetterScore(isWhite, evaluation.score, bestScore)) {
                        bestScore = evaluation.score;
                        bestMove = moveInt;
                    }

                    if (evaluation.hasScore()) {
                        if (isWhite) {
                            alpha = Math.max(alpha, evaluation.score);
                        } else {
                            beta = Math.min(beta, evaluation.score);
                        }
                    }

                    double alphaAfterLoop = alpha;
                    double betaAfterLoop = beta;

                    String threadName = Thread.currentThread().getName();
                    trace.record(moveInt, index, evaluation, Math.max(0, nodesAfter - nodesBefore),
                            Math.max(0, nanosAfter - nanosBefore), alphaBefore, betaBefore, alphaAfterLoop,
                            betaAfterLoop, threadName, evaluation.reason);
                    trace.recordThreadStages(moveInt, List.of(new ThreadStage(threadName, evaluation.reason,
                            Math.max(0, nodesAfter - nodesBefore), Math.max(0, nanosAfter - nanosBefore),
                            evaluation.score, alphaBefore, betaBefore, alphaAfterLoop, betaAfterLoop)));

                    if (evaluation.exitEarly) {
                        trace.addNote("Search aborted while analysing move " + formatMove(moveInt));
                        aborted = true;
                        break;
                    }

                    if (alpha >= beta) {
                        trace.markCutoff(moveInt, alpha, beta);
                        break;
                    }
                }

                MoveAndScore result = bestMove != -1 ? new MoveAndScore(bestMove, bestScore) : null;
                trace.finish(alpha, beta, result);
                return aborted ? RootSearchResult.aborted(result) : RootSearchResult.completed(result);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Failed to capture diagnostic data", e);
            }
        }

        private RootSearchResult searchRootMovesParallel(Engine simulatorEngine, SearchTask task, int depth,
                                                         double alpha, double beta, SplittableRandom rng,
                                                         DepthTrace trace) {
            ExecutorService pool = searchPool();
            if (pool == null) {
                trace.addNote("Parallel search requested but no executor is available; using sequential fallback");
                return searchRootMovesSequential(simulatorEngine, task, depth, alpha, beta, rng, trace);
            }

            long deadline = task.getDeadline();
            boolean isWhite = task.isWhiteToMove();

            try {
                IntArrayList legal = simulatorEngine.getAllLegalMoves();
                IntArrayList ordered = (IntArrayList) sortMovesMethod.invoke(this, legal, depth,
                        simulatorEngine.getBoardStateHash(), -1, simulatorEngine);

                maybeRotateRootMovesMethod.invoke(this, ordered, rng);

                if (ordered.isEmpty()) {
                    trace.addNote("No legal moves available at the root");
                    trace.finish(alpha, beta, null);
                    return RootSearchResult.completed(null);
                }

                if (abortRequested(deadline)) {
                    trace.addNote("Abort requested before launching parallel workers");
                    trace.finish(alpha, beta, null);
                    return RootSearchResult.aborted(null);
                }

                int firstMove = ordered.getInt(0);
                long nodesBefore = nodesVisited();
                long nanosBefore = System.nanoTime();
                double alphaBefore = alpha;
                double betaBefore = beta;

                simulatorEngine.performMove(firstMove);
                EvaluationResult firstEvaluation = evaluateAfterRootMove(simulatorEngine, depth, alpha, beta,
                        isWhite, deadline, firstMove);
                simulatorEngine.undoLastMove();

                long nanosAfter = System.nanoTime();
                long nodesAfter = nodesVisited();

                if (firstEvaluation.hasScore()) {
                    if (isWhite) {
                        alpha = Math.max(alpha, firstEvaluation.score);
                    } else {
                        beta = Math.min(beta, firstEvaluation.score);
                    }
                }

                double alphaAfterFirst = alpha;
                double betaAfterFirst = beta;

                String mainThread = Thread.currentThread().getName();
                trace.record(firstMove, 0, firstEvaluation, Math.max(0, nodesAfter - nodesBefore),
                        Math.max(0, nanosAfter - nanosBefore), alphaBefore, betaBefore, alphaAfterFirst,
                        betaAfterFirst, mainThread, firstEvaluation.reason);
                trace.recordThreadStages(firstMove, List.of(new ThreadStage(mainThread, firstEvaluation.reason,
                        Math.max(0, nodesAfter - nodesBefore), Math.max(0, nanosAfter - nanosBefore),
                        firstEvaluation.score, alphaBefore, betaBefore, alphaAfterFirst, betaAfterFirst)));

                if (firstEvaluation.exitEarly) {
                    trace.addNote("Search aborted while analysing move " + formatMove(firstMove));
                    trace.finish(alpha, beta, null);
                    return RootSearchResult.aborted(null);
                }

                double bestScore = firstEvaluation.hasScore()
                        ? firstEvaluation.score
                        : (isWhite ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
                int bestMove = firstEvaluation.hasScore() ? firstMove : -1;

                if (alpha >= beta) {
                    MoveAndScore candidate = bestMove != -1 ? new MoveAndScore(bestMove, bestScore) : null;
                    trace.markCutoff(firstMove, alpha, beta);
                    trace.finish(alpha, beta, candidate);
                    return RootSearchResult.completed(candidate);
                }

                int fanout = Math.min(rootParallelLimit, ordered.size() - 1);
                if (fanout <= 0) {
                    MoveAndScore candidate = bestMove != -1 ? new MoveAndScore(bestMove, bestScore) : null;
                    trace.finish(alpha, beta, candidate);
                    return RootSearchResult.completed(candidate);
                }

                CompletionService<WorkerResult> completionService = new ExecutorCompletionService<>(pool);
                List<Future<WorkerResult>> futures = new ArrayList<>(fanout);

                AtomicReference<Double> alphaRef = new AtomicReference<>(alpha);
                AtomicReference<Double> betaRef = new AtomicReference<>(beta);
                java.util.concurrent.atomic.AtomicInteger bestMoveRef = new java.util.concurrent.atomic.AtomicInteger(bestMove);
                AtomicReference<Double> bestScoreRef = new AtomicReference<>(bestScore);
                AtomicBoolean stopRef = new AtomicBoolean(false);
                ReentrantLock fullResLock = new ReentrantLock();

                for (int i = 1; i <= fanout; i++) {
                    final int moveInt = ordered.getInt(i);
                    final int order = i;
                    futures.add(completionService.submit(() -> {
                        try {
                            return evaluateParallelMove(simulatorEngine, task, depth, deadline, isWhite,
                                    alphaRef, betaRef, bestMoveRef, bestScoreRef, stopRef, fullResLock,
                                    moveInt, order);
                        } catch (Exception ex) {
                            return WorkerResult.aborted(moveInt, order, List.of(
                                    new ThreadStage(Thread.currentThread().getName(), "error", 0L, 0L,
                                            null, Double.NaN, Double.NaN, Double.NaN, Double.NaN)));
                        }
                    }));
                }

                boolean aborted = false;
                int completed = 0;

                try {
                    while (completed < fanout) {
                        if (stopRef.get()) {
                            break;
                        }
                        if (abortRequested(deadline)) {
                            aborted = true;
                            break;
                        }
                        Future<WorkerResult> future = completionService.take();
                        completed++;
                        WorkerResult worker = future.get();
                        if (worker == null) {
                            continue;
                        }

                        trace.recordThreadStages(worker.moveInt, worker.stages);

                        double alphaBeforeMove = Double.isNaN(worker.alphaBefore) ? alphaRef.get() : worker.alphaBefore;
                        double betaBeforeMove = Double.isNaN(worker.betaBefore) ? betaRef.get() : worker.betaBefore;
                        double alphaAfterMove = Double.isNaN(worker.alphaAfter) ? alphaRef.get() : worker.alphaAfter;
                        double betaAfterMove = Double.isNaN(worker.betaAfter) ? betaRef.get() : worker.betaAfter;

                        String workerThread = worker.stages.isEmpty()
                                ? Thread.currentThread().getName()
                                : worker.stages.get(worker.stages.size() - 1).threadName;

                        trace.record(worker.moveInt, worker.order, worker.evaluation, worker.nodesSpent,
                                worker.nanosSpent, alphaBeforeMove, betaBeforeMove, alphaAfterMove, betaAfterMove,
                                workerThread, worker.evaluation.reason);

                        if (worker.evaluation.exitEarly) {
                            trace.addNote("Worker aborted while analysing move " + formatMove(worker.moveInt));
                        }

                        if (alphaRef.get() >= betaRef.get()) {
                            stopRef.set(true);
                            break;
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    aborted = true;
                } catch (Exception ex) {
                    trace.addNote("Parallel worker failure: " + ex.getMessage());
                } finally {
                    for (Future<WorkerResult> future : futures) {
                        if (!future.isDone()) {
                            future.cancel(true);
                        }
                    }
                }

                double finalAlpha = alphaRef.get();
                double finalBeta = betaRef.get();
                bestMove = bestMoveRef.get();
                bestScore = bestScoreRef.get();
                MoveAndScore candidate = bestMove != -1 ? createCandidate(bestMove, bestScore) : null;
                trace.finish(finalAlpha, finalBeta, candidate);

                if (abortRequested(deadline)) {
                    aborted = true;
                }

                return aborted ? RootSearchResult.aborted(candidate) : RootSearchResult.completed(candidate);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Failed to capture diagnostic data", e);
            }
        }

        private WorkerResult evaluateParallelMove(Engine simulatorEngine, SearchTask task, int depth, long deadline,
                                                  boolean isWhite, AtomicReference<Double> alphaRef,
                                                  AtomicReference<Double> betaRef,
                                                  java.util.concurrent.atomic.AtomicInteger bestMoveRef,
                                                  AtomicReference<Double> bestScoreRef, AtomicBoolean stopRef,
                                                  ReentrantLock fullResLock, int moveInt, int order)
                throws InvocationTargetException, IllegalAccessException {
            Engine workerEngine = simulatorEngine.createSimulation();
            Object heuristics = prepareHelperHeuristics(task, depth);
            List<ThreadStage> stages = new ArrayList<>();
            long workerStart = System.nanoTime();
            long nodesStart = nodesVisited();
            String threadName = Thread.currentThread().getName();

            try {
                if (stopRef.get() || abortRequested(deadline)) {
                    return WorkerResult.aborted(moveInt, order, stages);
                }

                workerEngine.performMove(moveInt);

                double currentAlpha = alphaRef.get();
                double currentBeta = betaRef.get();
                double stageAlphaBefore;
                double stageBetaBefore;
                long stageNodes = 0L;
                long stageNanos = 0L;
                double probeScore;
                EvaluationResult evaluation;

                if (workerEngine.getGameState().isInStateCheckMate()) {
                    probeScore = isWhite ? (Score.CHECKMATE - 1) : -(Score.CHECKMATE - 1);
                    evaluation = EvaluationResult.mate(probeScore);
                    stageAlphaBefore = currentAlpha;
                    stageBetaBefore = currentBeta;
                } else if (workerEngine.getGameState().isTerminal()) {
                    double eval = (double) evaluateStaticPositionMethod.invoke(this, workerEngine.getGameState(),
                            !isWhite, depth);
                    if (isWhite) {
                        eval = -eval;
                    }
                    probeScore = eval;
                    evaluation = EvaluationResult.terminal(probeScore);
                    stageAlphaBefore = currentAlpha;
                    stageBetaBefore = currentBeta;
                } else {
                    double pAlpha = isWhite ? currentAlpha : currentBeta - 1;
                    double pBeta = isWhite ? currentAlpha + 1 : currentBeta;
                    stageAlphaBefore = pAlpha;
                    stageBetaBefore = pBeta;

                    long probeNodesStart = nodesVisited();
                    long probeStart = System.nanoTime();
                    double probe = (double) alphaBetaMethod.invoke(this, workerEngine, depth - 1, pAlpha, pBeta,
                            !isWhite, deadline, moveInt, 1, 0);
                    long probeNanos = System.nanoTime() - probeStart;
                    long probeNodes = nodesVisited() - probeNodesStart;
                    stageNodes = Math.max(0L, probeNodes);
                    stageNanos = Math.max(0L, probeNanos);

                    if (probe == EXIT_FLAG || abortRequested(deadline)) {
                        stages.add(new ThreadStage(threadName, "probe", stageNodes, stageNanos, null,
                                stageAlphaBefore, stageBetaBefore, stageAlphaBefore, stageBetaBefore));
                        return WorkerResult.aborted(moveInt, order, stages);
                    }

                    probeScore = probe;
                    evaluation = EvaluationResult.search(probeScore);
                }

                boolean needsFull = isWhite ? (probeScore > alphaRef.get()) : (probeScore < betaRef.get());
                double finalScore = probeScore;
                double alphaAfter = alphaRef.get();
                double betaAfter = betaRef.get();

                if (needsFull && !stopRef.get()) {
                    fullResLock.lock();
                    try {
                        if (!stopRef.get() && !abortRequested(deadline)) {
                            double aNow = alphaRef.get();
                            double bNow = betaRef.get();
                            long fullNodesStart = nodesVisited();
                            long fullStart = System.nanoTime();
                            double full = (double) alphaBetaMethod.invoke(this, workerEngine, depth - 1, aNow, bNow,
                                    !isWhite, deadline, moveInt, 1, 0);
                            long fullNanos = System.nanoTime() - fullStart;
                            long fullNodes = nodesVisited() - fullNodesStart;
                            long safeFullNodes = Math.max(0L, fullNodes);
                            long safeFullNanos = Math.max(0L, fullNanos);

                            stages.add(new ThreadStage(threadName, evaluation.reason, stageNodes, stageNanos,
                                    evaluation.score, stageAlphaBefore, stageBetaBefore,
                                    stageAlphaBefore, stageBetaBefore));

                            if (full == EXIT_FLAG || abortRequested(deadline)) {
                                stages.add(new ThreadStage(threadName, "full", safeFullNodes, safeFullNanos, null,
                                        aNow, bNow, aNow, bNow));
                                return WorkerResult.aborted(moveInt, order, stages);
                            }

                            finalScore = full;
                            if (isWhite) {
                                if (full > aNow) {
                                    alphaRef.set(full);
                                }
                            } else {
                                if (full < bNow) {
                                    betaRef.set(full);
                                }
                            }

                            Double curBest = bestScoreRef.get();
                            if (isBetterScore(isWhite, full, curBest)) {
                                bestScoreRef.set(full);
                                bestMoveRef.set(moveInt);
                            }

                            if (alphaRef.get() >= betaRef.get()) {
                                stopRef.set(true);
                            }

                            alphaAfter = alphaRef.get();
                            betaAfter = betaRef.get();

                            stages.add(new ThreadStage(threadName, "full", safeFullNodes, safeFullNanos, finalScore,
                                    aNow, bNow, alphaAfter, betaAfter));
                        } else {
                            stages.add(new ThreadStage(threadName, evaluation.reason, stageNodes, stageNanos,
                                    evaluation.score, stageAlphaBefore, stageBetaBefore,
                                    stageAlphaBefore, stageBetaBefore));
                            return WorkerResult.aborted(moveInt, order, stages);
                        }
                    } finally {
                        fullResLock.unlock();
                    }
                } else {
                    Double curBest = bestScoreRef.get();
                    if (isBetterScore(isWhite, finalScore, curBest)) {
                        bestScoreRef.set(finalScore);
                        bestMoveRef.set(moveInt);
                        if (isWhite && finalScore > alphaRef.get()) {
                            alphaRef.set(finalScore);
                        }
                        if (!isWhite && finalScore < betaRef.get()) {
                            betaRef.set(finalScore);
                        }
                        if (alphaRef.get() >= betaRef.get()) {
                            stopRef.set(true);
                        }
                    }

                    alphaAfter = alphaRef.get();
                    betaAfter = betaRef.get();

                    stages.add(new ThreadStage(threadName, evaluation.reason, stageNodes, stageNanos, finalScore,
                            stageAlphaBefore, stageBetaBefore, alphaAfter, betaAfter));
                }

                long nodesSpent = Math.max(0L, nodesVisited() - nodesStart);
                long nanosSpent = Math.max(0L, System.nanoTime() - workerStart);
                MoveAndScore moveAndScore = createCandidate(moveInt, finalScore);
                boolean usedFullWindow = stages.stream().anyMatch(stage -> "full".equals(stage.phase));
                EvaluationResult finalEvaluation = usedFullWindow ? EvaluationResult.search(finalScore) : evaluation;

                return new WorkerResult(moveInt, order, finalEvaluation, moveAndScore, nodesSpent, nanosSpent,
                        stageAlphaBefore, stageBetaBefore, alphaAfter, betaAfter, stages, false);
            } finally {
                if (heuristicsHaveUpdates(heuristics)) {
                    mergeThreadHeuristics(heuristics);
                } else {
                    heuristicsResetUpdates(heuristics);
                }
            }
        }

        private boolean isBetterScore(boolean isWhite, double score, double bestScore) {
            try {
                return (boolean) isBetterScoreMethod.invoke(this, isWhite, score, bestScore);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
        }

        private Object prepareHelperHeuristics(SearchTask task, int depth) {
            try {
                return prepareHelperHeuristicsMethod.invoke(this, task, depth);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
        }

        private void mergeThreadHeuristics(Object heuristics) {
            try {
                mergeThreadHeuristicsMethod.invoke(this, heuristics);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
        }

        private boolean heuristicsHaveUpdates(Object heuristics) {
            try {
                return (boolean) heuristicsHasUpdatesMethod.invoke(heuristics);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
        }

        private void heuristicsResetUpdates(Object heuristics) {
            try {
                heuristicsResetUpdatesMethod.invoke(heuristics);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
        }

        private MoveAndScore createCandidate(int move, double score) {
            try {
                return (MoveAndScore) createCandidateMethod.invoke(this, move, score);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
        }

        private ExecutorService searchPool() {
            try {
                return (ExecutorService) searchPoolField.get(this);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        private DepthTrace beginDepthTrace(SearchTask task, int depth, double alpha, double beta) {
            int attempt = depthAttemptCounter
                    .computeIfAbsent(depth, d -> new AtomicInteger())
                    .incrementAndGet();
            DepthTrace trace = new DepthTrace(depth, attempt, task.isWhiteToMove(), alpha, beta);
            depthTraces.add(trace);
            return trace;
        }

        private boolean abortRequested(long deadline) {
            try {
                return (boolean) abortRequestedMethod.invoke(this, deadline);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
        }

        private EvaluationResult evaluateAfterRootMove(Engine simulatorEngine, int depth, double alpha, double beta,
                                                       boolean isWhite, long deadline, int moveInt)
                throws InvocationTargetException, IllegalAccessException {
            GameState state = simulatorEngine.getGameState();
            if (state.isInStateCheckMate()) {
                double score = isWhite ? (Score.CHECKMATE - 1) : -(Score.CHECKMATE - 1);
                return EvaluationResult.mate(score);
            }
            if (state.isTerminal()) {
                double eval = (double) evaluateStaticPositionMethod.invoke(this, state, !isWhite, depth);
                if (isWhite) {
                    eval = -eval;
                }
                return EvaluationResult.terminal(eval);
            }

            double score = (double) alphaBetaMethod.invoke(this, simulatorEngine, depth - 1, alpha, beta,
                    !isWhite, deadline, moveInt, 1, 0);
            if (score == EXIT_FLAG || abortRequested(deadline)) {
                return EvaluationResult.exit();
            }
            return EvaluationResult.search(score);
        }

        private long nodesVisited() {
            try {
                return nodesVisitedField.getLong(this);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        long nullMovesUsed() {
            try {
                return nullMoveCountField.getLong(this);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        List<DepthTrace> getDepthTraces() {
            synchronized (depthTraces) {
                return new ArrayList<>(depthTraces);
            }
        }

        String buildDiagnosticsReport(String fen, List<String> lifesavingMoves, MoveAndScore result,
                                      Duration wallClockDuration) {
            StringBuilder sb = new StringBuilder(4096);
            sb.append(System.lineSeparator());
            sb.append("================ Mate Threat Diagnostic ================").append(System.lineSeparator());
            sb.append("FEN: ").append(fen).append(System.lineSeparator());
            sb.append("Expected defensive resources: ")
                    .append(String.join(", ", lifesavingMoves)).append(System.lineSeparator());

            sb.append("Search result: ");
            if (result == null) {
                sb.append("<no move found>");
            } else {
                sb.append(formatMove(result.move)).append(" (score=")
                        .append(String.format(Locale.ROOT, "%.2f", result.score)).append(')');
            }
            sb.append(System.lineSeparator());

            sb.append("Thread config: searchThreads=").append(getSearchThreads())
                    .append(", lazySmpThreads=").append(getLazySmpThreads())
                    .append(System.lineSeparator());

            sb.append("Principal variation: ");
            List<MoveAndScore> pv = principalVariation();
            if (pv.isEmpty()) {
                sb.append("<empty>");
            } else {
                String pvString = pv.stream()
                        .map(ms -> formatMove(ms.move))
                        .collect(Collectors.joining(" → "));
                sb.append(pvString);
            }
            sb.append(System.lineSeparator());

            sb.append("Nodes visited: ").append(nodesVisited()).append(System.lineSeparator());
            sb.append("Null moves tried: ").append(nullMovesUsed()).append(System.lineSeparator());
            sb.append("Elapsed wall-clock: ").append(wallClockDuration.toMillis()).append(" ms")
                    .append(System.lineSeparator());

            sb.append(System.lineSeparator());
            sb.append("-- Iterative deepening overview --").append(System.lineSeparator());

            Set<String> normalizedCriticalMoves = lifesavingMoves.stream()
                    .map(this::normalizeMoveLabel)
                    .collect(Collectors.toSet());

            List<DepthTrace> tracesSnapshot = getDepthTraces();
            tracesSnapshot.sort(Comparator.comparingInt(DepthTrace::depth)
                    .thenComparingInt(DepthTrace::attempt));

            for (DepthTrace trace : tracesSnapshot) {
                sb.append(trace.describe(normalizedCriticalMoves)).append(System.lineSeparator());
            }

            sb.append(System.lineSeparator());
            sb.append("-- Transposition table lookups for critical moves --").append(System.lineSeparator());
            for (String label : lifesavingMoves) {
                sb.append(describeTranspositionEntry(label)).append(System.lineSeparator());
            }

            sb.append("========================================================").append(System.lineSeparator());
            return sb.toString();
        }

        private String describeTranspositionEntry(String moveLabel) {
            Engine probe = getMainEngine().createSimulation();
            IntArrayList legal = probe.getAllLegalMoves();
            TranspositionTable<TranspositionTableEntry> tt = transpositionTable();
            String normalizedTarget = normalizeMoveLabel(moveLabel);

            for (int i = 0; i < legal.size(); i++) {
                int move = legal.getInt(i);
                if (!normalizedTarget.equals(normalizeMoveLabel(Move.convertIntToMove(move).toString()))) {
                    continue;
                }
                probe.performMove(move);
                long hash = probe.getBoardStateHash();
                TranspositionTableEntry entry = tt != null ? tt.get(hash) : null;
                probe.undoLastMove();

                if (entry == null) {
                    return String.format(Locale.ROOT,
                            "%-4s → no TT entry (move likely unsearched or pruned early)", moveLabel);
                }
                return String.format(Locale.ROOT,
                        "%-4s → TT depth=%d score=%.2f type=%s bestMove=%s",
                        moveLabel, entry.getDepth(), entry.getScore(), entry.getNodeType(),
                        formatMove(entry.getBestMove()));
            }
            return String.format(Locale.ROOT,
                    "%-4s → not present in root move list (illegal or filtered)", moveLabel);
        }

        private String normalizeMoveLabel(String moveLabel) {
            return moveLabel == null ? "" : moveLabel.trim().toLowerCase(Locale.ROOT);
        }

        private String formatMove(int moveInt) {
            if (moveInt == -1) {
                return "<none>";
            }
            Move move = Move.convertIntToMove(moveInt);
            String coords = MoveHelper.convertIndexToString(MoveHelper.deriveFromIndex(moveInt))
                    + MoveHelper.convertIndexToString(MoveHelper.deriveToIndex(moveInt));
            String san = move.toString();
            if (!san.isBlank() && !san.equalsIgnoreCase(coords)) {
                return san + " [" + coords + "]";
            }
            return coords;
        }

        private static final class EvaluationResult {
            final Double score;
            final String reason;
            final boolean exitEarly;

            private EvaluationResult(Double score, String reason, boolean exitEarly) {
                this.score = score;
                this.reason = reason;
                this.exitEarly = exitEarly;
            }

            static EvaluationResult mate(double score) {
                return new EvaluationResult(score, "mate", false);
            }

            static EvaluationResult terminal(double score) {
                return new EvaluationResult(score, "terminal", false);
            }

            static EvaluationResult search(double score) {
                return new EvaluationResult(score, "search", false);
            }

            static EvaluationResult exit() {
                return new EvaluationResult(null, "exit", true);
            }

            boolean hasScore() {
                return score != null;
            }
        }

        private static final class ThreadStage {
            final String threadName;
            final String phase;
            final long nodesSpent;
            final long nanosSpent;
            final Double score;
            final double alphaBefore;
            final double betaBefore;
            final double alphaAfter;
            final double betaAfter;

            ThreadStage(String threadName, String phase, long nodesSpent, long nanosSpent, Double score,
                        double alphaBefore, double betaBefore, double alphaAfter, double betaAfter) {
                this.threadName = threadName;
                this.phase = phase;
                this.nodesSpent = nodesSpent;
                this.nanosSpent = nanosSpent;
                this.score = score;
                this.alphaBefore = alphaBefore;
                this.betaBefore = betaBefore;
                this.alphaAfter = alphaAfter;
                this.betaAfter = betaAfter;
            }
        }

        private static final class WorkerResult {
            final int moveInt;
            final int order;
            final EvaluationResult evaluation;
            final MoveAndScore moveAndScore;
            final long nodesSpent;
            final long nanosSpent;
            final double alphaBefore;
            final double betaBefore;
            final double alphaAfter;
            final double betaAfter;
            final List<ThreadStage> stages;
            final boolean aborted;

            WorkerResult(int moveInt, int order, EvaluationResult evaluation, MoveAndScore moveAndScore,
                         long nodesSpent, long nanosSpent, double alphaBefore, double betaBefore,
                         double alphaAfter, double betaAfter, List<ThreadStage> stages, boolean aborted) {
                this.moveInt = moveInt;
                this.order = order;
                this.evaluation = evaluation;
                this.moveAndScore = moveAndScore;
                this.nodesSpent = nodesSpent;
                this.nanosSpent = nanosSpent;
                this.alphaBefore = alphaBefore;
                this.betaBefore = betaBefore;
                this.alphaAfter = alphaAfter;
                this.betaAfter = betaAfter;
                this.stages = stages;
                this.aborted = aborted;
            }

            static WorkerResult aborted(int moveInt, int order, List<ThreadStage> stages) {
                return new WorkerResult(moveInt, order, EvaluationResult.exit(), null, 0L, 0L,
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN, stages, true);
            }
        }

        private final class DepthTrace {
            private final int depth;
            private final int attempt;
            private final boolean whiteToMove;
            private final double initialAlpha;
            private final double initialBeta;
            private final long startedAt = System.nanoTime();
            private final List<RootMoveTrace> moves = new ArrayList<>();
            private final List<ThreadEvent> threadEvents = new ArrayList<>();
            private final List<String> notes = new ArrayList<>();
            private boolean betaCutoff;
            private int cutoffMove = -1;
            private MoveAndScore finalBest;
            private double finalAlpha;
            private double finalBeta;

            DepthTrace(int depth, int attempt, boolean whiteToMove, double initialAlpha, double initialBeta) {
                this.depth = depth;
                this.attempt = attempt;
                this.whiteToMove = whiteToMove;
                this.initialAlpha = initialAlpha;
                this.initialBeta = initialBeta;
            }

            void record(int moveInt, int order, EvaluationResult evaluation, long nodesSpent, long nanosSpent,
                        double alphaBefore, double betaBefore, double alphaAfterLoop, double betaAfterLoop,
                        String threadName, String phase) {
                moves.add(new RootMoveTrace(moveInt, order, evaluation, nodesSpent, nanosSpent,
                        alphaBefore, betaBefore, alphaAfterLoop, betaAfterLoop, threadName, phase));
            }

            void addNote(String note) {
                notes.add(note);
            }

            void recordThreadStages(int moveInt, List<ThreadStage> stages) {
                if (stages == null || stages.isEmpty()) {
                    return;
                }
                for (ThreadStage stage : stages) {
                    threadEvents.add(new ThreadEvent(stage.threadName, moveInt, stage.phase,
                            stage.nodesSpent, stage.nanosSpent, stage.score,
                            stage.alphaBefore, stage.betaBefore, stage.alphaAfter, stage.betaAfter));
                }
            }

            void markCutoff(int moveInt, double alpha, double beta) {
                this.betaCutoff = true;
                this.cutoffMove = moveInt;
                this.finalAlpha = alpha;
                this.finalBeta = beta;
            }

            void finish(double alpha, double beta, MoveAndScore best) {
                this.finalAlpha = alpha;
                this.finalBeta = beta;
                this.finalBest = best;
            }

            int depth() {
                return depth;
            }

            int attempt() {
                return attempt;
            }

            String describe(Set<String> criticalMoves) {
                StringBuilder sb = new StringBuilder();
                sb.append(String.format(Locale.ROOT,
                        "Depth %2d (attempt %d, %s to move, window=[%.2f, %.2f])",
                        depth, attempt, whiteToMove ? "white" : "black",
                        initialAlpha, initialBeta));
                sb.append(System.lineSeparator());

                if (moves.isEmpty()) {
                    sb.append("  <no moves explored>").append(System.lineSeparator());
                } else {
                    moves.sort(Comparator.comparingInt(RootMoveTrace::order));
                    for (RootMoveTrace trace : moves) {
                        sb.append("  ").append(trace.describe(criticalMoves)).append(System.lineSeparator());
                    }
                }

                if (betaCutoff) {
                    sb.append("  Cutoff after ").append(formatMove(cutoffMove))
                            .append(String.format(Locale.ROOT, " (alpha=%.2f, beta=%.2f)", finalAlpha, finalBeta))
                            .append(System.lineSeparator());
                }

                if (finalBest != null) {
                    sb.append("  Best so far: ").append(formatMove(finalBest.move))
                            .append(String.format(Locale.ROOT, " score=%.2f", finalBest.score))
                            .append(System.lineSeparator());
                }

                long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
                sb.append("  Final window: [").append(String.format(Locale.ROOT, "%.2f", finalAlpha))
                        .append(", ").append(String.format(Locale.ROOT, "%.2f", finalBeta)).append("]")
                        .append(" after ").append(elapsedMs).append(" ms").append(System.lineSeparator());

                if (!notes.isEmpty()) {
                    for (String note : notes) {
                        sb.append("  Note: ").append(note).append(System.lineSeparator());
                    }
                }

                if (!threadEvents.isEmpty()) {
                    sb.append("  Thread activity:").append(System.lineSeparator());
                    Map<String, List<ThreadEvent>> byThread = threadEvents.stream()
                            .collect(Collectors.groupingBy(
                                    event -> event.threadName == null || event.threadName.isBlank()
                                            ? "<unnamed>"
                                            : event.threadName,
                                    LinkedHashMap::new,
                                    Collectors.toList()));

                    for (Map.Entry<String, List<ThreadEvent>> entry : byThread.entrySet()) {
                        long totalNodes = entry.getValue().stream().mapToLong(ThreadEvent::nodesSpent).sum();
                        long totalMicros = entry.getValue().stream().mapToLong(event -> event.nanosSpent / 1_000).sum();
                        sb.append(String.format(Locale.ROOT, "    [%s] nodes=%d time=%dµs",
                                entry.getKey(), totalNodes, totalMicros)).append(System.lineSeparator());
                        for (ThreadEvent event : entry.getValue()) {
                            sb.append(String.format(Locale.ROOT,
                                            "      %-12s %-10s | nodes=%-6d time=%4dµs | window [%.2f, %.2f] → [%.2f, %.2f]",
                                            formatMove(event.moveInt), event.phase,
                                            event.nodesSpent, event.nanosSpent / 1_000,
                                            event.alphaBefore, event.betaBefore,
                                            event.alphaAfter, event.betaAfter));
                            if (event.score != null) {
                                sb.append(String.format(Locale.ROOT, " | score=%.2f", event.score));
                            }
                            sb.append(System.lineSeparator());
                        }
                    }
                }
                return sb.toString().stripTrailing();
            }
        }

        private final class RootMoveTrace {
            private final int moveInt;
            private final int order;
            private final EvaluationResult evaluation;
            private final long nodesSpent;
            private final long nanosSpent;
            private final double alphaBefore;
            private final double betaBefore;
            private final double alphaAfter;
            private final double betaAfter;
            private final String threadName;
            private final String phase;

            RootMoveTrace(int moveInt, int order, EvaluationResult evaluation, long nodesSpent, long nanosSpent,
                          double alphaBefore, double betaBefore, double alphaAfter, double betaAfter,
                          String threadName, String phase) {
                this.moveInt = moveInt;
                this.order = order;
                this.evaluation = evaluation;
                this.nodesSpent = nodesSpent;
                this.nanosSpent = nanosSpent;
                this.alphaBefore = alphaBefore;
                this.betaBefore = betaBefore;
                this.alphaAfter = alphaAfter;
                this.betaAfter = betaAfter;
                this.threadName = threadName;
                this.phase = phase;
            }

            int order() {
                return order;
            }

            String describe(Set<String> criticalMoves) {
                String coord = formatMove(moveInt);
                Move move = Move.convertIntToMove(moveInt);
                String normalizedSan = normalizeMoveLabel(move.toString());
                boolean isCritical = criticalMoves.contains(normalizedSan)
                        || criticalMoves.contains(normalizeMoveLabel(coord));

                String workerLabel = (threadName == null || threadName.isBlank()) ? "<main>" : threadName;
                String phaseLabel = (phase == null || phase.isBlank()) ? "final" : phase;

                StringBuilder sb = new StringBuilder();
                sb.append(String.format(Locale.ROOT,
                        "#%02d %-12s | worker=%-18s | nodes=%-6d time=%4dµs | window [%.2f, %.2f] → [%.2f, %.2f]",
                        order + 1, coord + (isCritical ? " *" : ""),
                        workerLabel + "/" + phaseLabel,
                        nodesSpent, nanosSpent / 1_000,
                        alphaBefore, betaBefore, alphaAfter, betaAfter));
                sb.append(" | result=");

                if (evaluation.exitEarly) {
                    sb.append("<abort>");
                } else if (evaluation.score == null) {
                    sb.append("<none>");
                } else {
                    sb.append(String.format(Locale.ROOT, "%.2f", evaluation.score));
                }

                sb.append(" (via ").append(evaluation.reason).append(')');
                return sb.toString();
            }
        }

        private final class ThreadEvent {
            private final String threadName;
            private final int moveInt;
            private final String phase;
            private final long nodesSpent;
            private final long nanosSpent;
            private final Double score;
            private final double alphaBefore;
            private final double betaBefore;
            private final double alphaAfter;
            private final double betaAfter;

            ThreadEvent(String threadName, int moveInt, String phase, long nodesSpent, long nanosSpent,
                        Double score, double alphaBefore, double betaBefore, double alphaAfter, double betaAfter) {
                this.threadName = threadName;
                this.moveInt = moveInt;
                this.phase = phase;
                this.nodesSpent = nodesSpent;
                this.nanosSpent = nanosSpent;
                this.score = score;
                this.alphaBefore = alphaBefore;
                this.betaBefore = betaBefore;
                this.alphaAfter = alphaAfter;
                this.betaAfter = betaAfter;
            }

            long nodesSpent() {
                return nodesSpent;
            }

            long nanosSpent() {
                return nanosSpent;
            }
        }
    }
}
