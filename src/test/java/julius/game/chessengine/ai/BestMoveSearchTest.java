package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.Move;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.tuning.AiTuning;
import julius.game.chessengine.ai.MoveAndScore;
import julius.game.chessengine.ai.TranspositionTable;
import julius.game.chessengine.ai.TranspositionTableEntry;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import testsupport.TestReportWriter;

/**
 * Verifies that the AI selects the expected best move from a set of FEN
 * positions within a small time budget. Similar in spirit to
 * {@link MateSearchTest} but checks the single move chosen by the engine
 * -Xms8g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+AlwaysPreTouch -XX:+UseNUMA -XX:ActiveProcessorCount=24 -Dchessengine.tt.mb=256 -Dchessengine.searchThreads=16 -Dchessengine.lazySmpThreads=8 -Dchessengine.rootParallelLimit=48
 * instead of the game result.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BestMoveSearchTest {

    private static final SearchEnvironment SEARCH_ENVIRONMENT = SearchEnvironment.detect();

    private final List<DecisionStatistics> decisionSummaries = new ArrayList<>();
    private final List<String> decisionJsonLines = new ArrayList<>();
    private final List<String> decisionTextBlocks = new ArrayList<>();

    @BeforeEach
    void ensureLocale() {
        Locale.setDefault(Locale.ROOT);
    }

    /**
     * Test matrix: (fen, expected moves in algebraic notation). Some positions
     * have multiple acceptable best moves, so we keep a list.
     */
    @ParameterizedTest(name = "Best move {1} for FEN {0}")
    @MethodSource("julius.game.chessengine.ai.BestMoveFixtures#arguments")
    void testBestMove(String fen, List<String> expectedMoves) throws InterruptedException {
        Engine engine = new Engine();
        engine.importBoardFromFen(fen);

        final long timeLimitMs = 3000L;

        AiTuning tuning = SEARCH_ENVIRONMENT.applyTo(AiTuning.builder())
                .maxDepth(24)
                .timeLimitMillis(timeLimitMs)
                .nullMovePruning(true)
                .build();

        DiagnosticAI ai = new DiagnosticAI(engine, tuning);

        long nodesBefore = ai.getNodesVisited();
        long nullMovesBefore = ai.getNullMoveCount();
        long startNanos = System.nanoTime();

        MoveAndScore result = ai.searchBestMoveBlocking(timeLimitMs);
        Duration wallClock = Duration.ofNanos(System.nanoTime() - startNanos);

        Assertions.assertNotNull(result, () -> "Engine failed to produce a move for FEN: " + fen);

        long durationMillis = wallClock.toMillis();
        long nodesVisited = Math.max(0, ai.getNodesVisited() - nodesBefore);
        long nullMoves = Math.max(0, ai.getNullMoveCount() - nullMovesBefore);

        String moveString = Move.convertIntToMove(result.getMove()).toString();
        DecisionStatistics statistics = compileDecisionStatistics(
                fen,
                moveString,
                result,
                ai,
                expectedMoves,
                durationMillis,
                nodesVisited,
                nullMoves
        );
        decisionSummaries.add(statistics);

        String humanReadable = statistics.toHumanReadable();
        String diagnostics = ai.buildDiagnosticsReport(fen, expectedMoves, result, wallClock);
        System.out.println(humanReadable);
        System.out.println(statistics.toJsonLine());
        System.out.println(diagnostics);

        decisionJsonLines.add(statistics.toJsonLine());
        decisionTextBlocks.add(humanReadable + diagnostics);

        Assertions.assertTrue(expectedMoves.contains(moveString),
                () -> "Expected one of " + expectedMoves + " but got " + moveString + " for FEN: " + fen
                        + humanReadable + System.lineSeparator() + diagnostics);
    }

    private DecisionStatistics compileDecisionStatistics(String fen,
                                                         String chosenMove,
                                                         MoveAndScore searchResult,
                                                         DiagnosticAI ai,
                                                         List<String> expectedMoves,
                                                         long durationMillis,
                                                         long nodesVisited,
                                                         long nullMoves) {
        Engine analysisEngine = new Engine();
        analysisEngine.importBoardFromFen(fen);

        boolean whiteToMove = fen.split(" ")[1].equals("w");
        double baselineForMover = orientScoreForMover(whiteToMove,
                analysisEngine.getGameState().getScore().getScoreDifference());

        IntArrayList legalMovesSnapshot = analysisEngine.getAllLegalMoves();
        List<Integer> legalMoves = new ArrayList<>(legalMovesSnapshot.size());
        for (int i = 0; i < legalMovesSnapshot.size(); i++) {
            legalMoves.add(legalMovesSnapshot.getInt(i));
        }

        List<MoveEvaluation> evaluations = new ArrayList<>(legalMoves.size());
        Map<String, MoveEvaluation> evaluationMap = new HashMap<>(Math.max(legalMoves.size(), 1));
        for (int moveInt : legalMoves) {
            analysisEngine.performMove(moveInt);
            double scoreDiff = analysisEngine.getGameState().getScore().getScoreDifference();
            double moverScore = orientScoreForMover(whiteToMove, scoreDiff);
            analysisEngine.undoLastMove();
            MoveEvaluation evaluation = new MoveEvaluation(Move.convertIntToMove(moveInt).toString(), moverScore);
            evaluations.add(evaluation);
            evaluationMap.putIfAbsent(evaluation.move(), evaluation);
        }

        Comparator<MoveEvaluation> comparator = whiteToMove
                ? Comparator.comparingDouble(MoveEvaluation::score).reversed()
                : Comparator.comparingDouble(MoveEvaluation::score);
        evaluations.sort(comparator);

        MoveEvaluation chosenEvaluation = evaluationMap.get(chosenMove);
        if (chosenEvaluation == null && searchResult != null) {
            double oriented = orientScoreForMover(whiteToMove, searchResult.getScore());
            chosenEvaluation = new MoveEvaluation(chosenMove, oriented);
        }
        MoveEvaluation bestEvaluation = evaluations.isEmpty() ? null : evaluations.getFirst();

        List<MoveEvaluation> expectedEvaluationDetails = new ArrayList<>();
        for (String expected : expectedMoves) {
            MoveEvaluation evaluation = evaluationMap.get(expected);
            if (evaluation != null) {
                expectedEvaluationDetails.add(evaluation);
            }
        }

        List<MoveEvaluation> topCandidates = new ArrayList<>(evaluations.subList(0, Math.min(5, evaluations.size())));

        double bestScore = bestEvaluation != null ? bestEvaluation.score() : Double.NaN;
        double chosenScore = chosenEvaluation != null ? chosenEvaluation.score() : Double.NaN;
        double cpLoss = Double.isFinite(bestScore) && Double.isFinite(chosenScore) ? bestScore - chosenScore : Double.NaN;
        double cpGain = Double.isFinite(chosenScore) && Double.isFinite(baselineForMover)
                ? chosenScore - baselineForMover
                : Double.NaN;
        int rank = chosenEvaluation != null ? evaluations.indexOf(chosenEvaluation) + 1 : -1;

        String pvString = renderPrincipalVariation(whiteToMove, ai.principalVariation());

        return new DecisionStatistics(
                fen,
                whiteToMove,
                expectedMoves,
                chosenEvaluation,
                bestEvaluation,
                baselineForMover,
                cpLoss,
                cpGain,
                rank,
                nodesVisited,
                nullMoves,
                durationMillis,
                pvString,
                topCandidates,
                expectedEvaluationDetails
        );
    }

    @AfterAll
    void emitAggregateStatistics() {
        if (decisionSummaries.isEmpty()) {
            return;
        }
        AggregateStatistics summary = AggregateStatistics.from(decisionSummaries, SEARCH_ENVIRONMENT);
        System.out.println(summary.toHumanReadable());
        System.out.println(summary.toJsonLine());

        TestReportWriter.writeLines(
                "best-move-search-decisions.jsonl",
                decisionJsonLines
        );
        TestReportWriter.writeLines(
                "best-move-search-decisions.txt",
                decisionTextBlocks
        );
        TestReportWriter.writeLines(
                "best-move-search-summary.json",
                List.of(summary.toJsonLine())
        );
        TestReportWriter.writeLines(
                "best-move-search-summary.txt",
                List.of(summary.toHumanReadable())
        );
    }

    private static final class DiagnosticAI extends AI {

        private final Method sortMovesMethod;
        private final Method maybeRotateRootMovesMethod;
        private final Method abortRequestedMethod;
        private final Method alphaBetaMethod;
        private final Method evaluateStaticPositionMethod;
        private final Method isBetterScoreMethod;
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
            } catch (NoSuchMethodException | NoSuchFieldException e) {
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
        List<MoveAndScore> principalVariation() {
            try {
                Object value = calculatedLineField.get(this);
                if (value instanceof List<?> list) {
                    return new ArrayList<>((List<MoveAndScore>) list);
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

                    trace.record(moveInt, index, evaluation, nodesAfter - nodesBefore,
                            nanosAfter - nanosBefore, alphaBefore, betaBefore, alpha, beta);

                    if (evaluation.exitEarly) {
                        trace.addNote("Search aborted while analysing move " + formatMove(moveInt));
                        aborted = true;
                        break;
                    }

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

        private boolean isBetterScore(boolean isWhite, double score, double bestScore) {
            try {
                return (boolean) isBetterScoreMethod.invoke(this, isWhite, score, bestScore);
            } catch (IllegalAccessException | InvocationTargetException e) {
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

        String buildDiagnosticsReport(String fen, List<String> expectedMoves, MoveAndScore result,
                                      Duration wallClockDuration) {
            StringBuilder sb = new StringBuilder(4096);
            sb.append(System.lineSeparator());
            sb.append("================ Best Move Search Diagnostic ================").append(System.lineSeparator());
            sb.append("FEN: ").append(fen).append(System.lineSeparator());
            sb.append("Expected best moves: ")
                    .append(String.join(", ", expectedMoves)).append(System.lineSeparator());

            sb.append("Search result: ");
            if (result == null) {
                sb.append("<no move found>");
            } else {
                sb.append(formatMove(result.move)).append(" (score=")
                        .append(String.format(Locale.ROOT, "%.2f", result.score)).append(')');
            }
            sb.append(System.lineSeparator());

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

            Set<String> normalizedCriticalMoves = expectedMoves.stream()
                    .map(this::normalizeMoveLabel)
                    .collect(Collectors.toSet());

            List<DepthTrace> tracesSnapshot = getDepthTraces();
            tracesSnapshot.sort(Comparator.comparingInt(DepthTrace::depth)
                    .thenComparingInt(DepthTrace::attempt));

            for (DepthTrace trace : tracesSnapshot) {
                sb.append(trace.describe(normalizedCriticalMoves)).append(System.lineSeparator());
            }

            sb.append(System.lineSeparator());
            sb.append("-- Transposition table lookups for expected moves --").append(System.lineSeparator());
            for (String label : expectedMoves) {
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

        boolean foundAnyExpectedMove(List<String> expected, MoveAndScore result) {
            if (result != null) {
                for (String label : expected) {
                    if (moveMatchesLabel(result.move, label)) {
                        return true;
                    }
                }
            }
            Set<String> targets = expected.stream().map(this::normalizeMoveLabel).collect(Collectors.toSet());
            List<DepthTrace> snapshot = getDepthTraces();
            for (DepthTrace dt : snapshot) {
                for (RootMoveTrace rmt : dt.moves) {
                    for (String lbl : targets) {
                        if (moveMatchesLabel(rmt.moveInt, lbl)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private boolean moveMatchesLabel(int moveInt, String label) {
            String target = normalizeMoveLabel(label);
            if (target.isEmpty()) return false;

            Move move = Move.convertIntToMove(moveInt);
            String san = normalizeMoveLabel(move.toString());
            String coords = normalizeMoveLabel(
                    MoveHelper.convertIndexToString(MoveHelper.deriveFromIndex(moveInt)) +
                            MoveHelper.convertIndexToString(MoveHelper.deriveToIndex(moveInt))
            );
            String pretty = normalizeMoveLabel(formatMove(moveInt));

            return target.equals(san) || target.equals(coords) || pretty.startsWith(target + " ");
        }

        private final class DepthTrace {
            private final int depth;
            private final int attempt;
            private final boolean whiteToMove;
            private final double initialAlpha;
            private final double initialBeta;
            private final long startedAt = System.nanoTime();
            private final List<RootMoveTrace> moves = new ArrayList<>();
            private final List<String> notes = new ArrayList<>();
            private boolean betaCutoff;
            private int cutoffMove = -1;
            private double finalAlpha;
            private double finalBeta;
            private MoveAndScore finalBest;

            DepthTrace(int depth, int attempt, boolean whiteToMove, double initialAlpha, double initialBeta) {
                this.depth = depth;
                this.attempt = attempt;
                this.whiteToMove = whiteToMove;
                this.initialAlpha = initialAlpha;
                this.initialBeta = initialBeta;
            }

            void record(int moveInt, int order, EvaluationResult evaluation, long nodesSpent, long nanosSpent,
                        double alphaBefore, double betaBefore, double alphaAfter, double betaAfter) {
                moves.add(new RootMoveTrace(moveInt, order, evaluation, nodesSpent, nanosSpent,
                        alphaBefore, betaBefore, alphaAfter, betaAfter));
            }

            void addNote(String note) {
                notes.add(note);
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

            RootMoveTrace(int moveInt, int order, EvaluationResult evaluation, long nodesSpent, long nanosSpent,
                          double alphaBefore, double betaBefore, double alphaAfter, double betaAfter) {
                this.moveInt = moveInt;
                this.order = order;
                this.evaluation = evaluation;
                this.nodesSpent = nodesSpent;
                this.nanosSpent = nanosSpent;
                this.alphaBefore = alphaBefore;
                this.betaBefore = betaBefore;
                this.alphaAfter = alphaAfter;
                this.betaAfter = betaAfter;
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

                StringBuilder sb = new StringBuilder();
                sb.append(String.format(Locale.ROOT,
                        "#%02d %-12s | nodes=%-6d time=%4dµs | window [%.2f, %.2f] → [%.2f, %.2f]",
                        order + 1, coord + (isCritical ? " *" : ""), nodesSpent,
                        nanosSpent / 1_000, alphaBefore, betaBefore, alphaAfter, betaAfter));
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

        private record EvaluationResult(Double score, boolean exitEarly, String reason) {
            static EvaluationResult search(double score) {
                return new EvaluationResult(score, false, "alphaBeta");
            }

            static EvaluationResult mate(double score) {
                return new EvaluationResult(score, false, "checkmate");
            }

            static EvaluationResult terminal(double score) {
                return new EvaluationResult(score, false, "terminal");
            }

            static EvaluationResult exit() {
                return new EvaluationResult(null, true, "exit");
            }

            boolean hasScore() {
                return score != null;
            }
        }
    }

    private record SearchEnvironment(
            int availableProcessors,
            int searchThreads,
            int lazySmpThreads,
            int rootParallelLimit,
            int transpositionTableMb
    ) {

        static SearchEnvironment detect() {
            int availableProcessors = Math.max(1, Runtime.getRuntime().availableProcessors());

            int defaultSearchThreads = computeDefaultSearchThreads(availableProcessors);
            int searchThreads = resolveIntProperty("chessengine.searchThreads", defaultSearchThreads, 1);

            int defaultLazySmpThreads = computeDefaultLazySmpThreads(availableProcessors, searchThreads);
            int lazySmpThreads = resolveIntProperty("chessengine.lazySmpThreads", defaultLazySmpThreads, 1);

            int defaultRootParallelLimit = computeDefaultRootParallelLimit(availableProcessors, searchThreads, lazySmpThreads);
            int rootParallelLimit = resolveIntProperty("chessengine.rootParallelLimit", defaultRootParallelLimit, 2);

            int defaultTranspositionTableMb = computeDefaultTranspositionTableMb(availableProcessors);
            int ttSizeMb = resolveIntProperty("chessengine.tt.mb", defaultTranspositionTableMb, 64);

            return new SearchEnvironment(availableProcessors, searchThreads, lazySmpThreads, rootParallelLimit, ttSizeMb);
        }

        AiTuning.Builder applyTo(AiTuning.Builder builder) {
            Objects.requireNonNull(builder, "builder");
            builder.searchThreads(searchThreads);
            builder.lazySmpThreads(lazySmpThreads);
            builder.hashSizeMb(transpositionTableMb);
            return builder;
        }

        boolean multiThreaded() {
            return Math.max(searchThreads, lazySmpThreads) > 1;
        }

        String inlineSummary() {
            return String.format(Locale.ROOT,
                    "runtimeCores=%d, searchThreads=%d, lazySmpThreads=%d, rootParallelLimit=%d, ttSize=%d MB, multiThreaded=%s",
                    availableProcessors, searchThreads, lazySmpThreads, rootParallelLimit, transpositionTableMb,
                    multiThreaded() ? "yes" : "no");
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("Search environment:").append(System.lineSeparator());
            sb.append("  Runtime processors: ").append(availableProcessors).append(System.lineSeparator());
            sb.append("  Search threads: ").append(searchThreads).append(System.lineSeparator());
            sb.append("  Lazy SMP threads: ").append(lazySmpThreads).append(System.lineSeparator());
            sb.append("  Root parallel limit: ").append(rootParallelLimit).append(System.lineSeparator());
            sb.append("  Transposition table: ").append(transpositionTableMb).append(" MB").append(System.lineSeparator());
            sb.append("  Multi-threaded: ").append(multiThreaded() ? "yes" : "no").append(System.lineSeparator());
            return sb.toString();
        }

        private static int resolveIntProperty(String key, int computedDefault, int minValue) {
            String value = System.getProperty(key);
            if (value == null || value.isBlank()) {
                int resolved = clampAtLeast(computedDefault, minValue);
                System.setProperty(key, Integer.toString(resolved));
                return resolved;
            }
            try {
                int parsed = Integer.parseInt(value.trim());
                int resolved = clampAtLeast(parsed, minValue);
                if (resolved != parsed) {
                    System.setProperty(key, Integer.toString(resolved));
                }
                return resolved;
            } catch (NumberFormatException ex) {
                int resolved = clampAtLeast(computedDefault, minValue);
                System.setProperty(key, Integer.toString(resolved));
                return resolved;
            }
        }

        private static int computeDefaultSearchThreads(int availableProcessors) {
            if (availableProcessors <= 1) {
                return 1;
            }
            if (availableProcessors == 2) {
                return 2;
            }
            int scaled = (int) Math.floor(availableProcessors * 0.67);
            int capped = Math.min(availableProcessors - 1, Math.max(2, scaled));
            return clampAtLeast(capped, 1);
        }

        private static int computeDefaultLazySmpThreads(int availableProcessors, int searchThreads) {
            if (searchThreads <= 1) {
                return 1;
            }
            int headroom = Math.max(0, availableProcessors - searchThreads);
            if (headroom <= 1) {
                return 1;
            }
            int candidate = Math.max(1, searchThreads / 2);
            int bounded = Math.min(candidate, headroom);
            return clampAtLeast(bounded, 1);
        }

        private static int computeDefaultRootParallelLimit(int availableProcessors, int searchThreads, int lazySmpThreads) {
            int baseline = Math.max(2, availableProcessors * 3);
            int required = Math.max(baseline, searchThreads + lazySmpThreads);
            return Math.min(192, required);
        }

        private static int computeDefaultTranspositionTableMb(int availableProcessors) {
            int scaled = Math.max(availableProcessors * 64, 128);
            return Math.min(512, scaled);
        }

        private static int clampAtLeast(int value, int minValue) {
            return Math.max(minValue, value);
        }
    }

    private record DecisionStatistics(
            String fen,
            boolean whiteToMove,
            List<String> expectedMoves,
            MoveEvaluation chosenEvaluation,
            MoveEvaluation bestEvaluation,
            double baselineScore,
            double cpLoss,
            double cpGain,
            int rank,
            long nodesVisited,
            long nullMoves,
            long durationMillis,
            String principalVariation,
            List<MoveEvaluation> topCandidates,
            List<MoveEvaluation> expectedEvaluations
    ) {

        DecisionStatistics {
            expectedMoves = List.copyOf(expectedMoves);
            topCandidates = List.copyOf(topCandidates);
            expectedEvaluations = List.copyOf(expectedEvaluations);
        }

        String toHumanReadable() {
            StringBuilder sb = new StringBuilder();
            sb.append(System.lineSeparator());
            sb.append("=== Engine decision statistics ===").append(System.lineSeparator());
            sb.append("FEN: ").append(fen).append(System.lineSeparator());
            sb.append("Side to move: ").append(whiteToMove ? "White" : "Black").append(System.lineSeparator());
            sb.append("Expected best moves: ").append(expectedMoves).append(System.lineSeparator());
            sb.append("Baseline evaluation: ").append(formatCentipawns(baselineScore)).append(" pawns")
                    .append(System.lineSeparator());
            sb.append("Search configuration: ").append(SEARCH_ENVIRONMENT.inlineSummary())
                    .append(System.lineSeparator());

            if (chosenEvaluation != null) {
                sb.append("Chosen move: ").append(chosenEvaluation.move())
                        .append(" -> ").append(formatCentipawns(chosenEvaluation.score())).append(" pawns");
                if (Double.isFinite(cpGain)) {
                    sb.append(" (Δ vs baseline: ").append(formatCentipawns(cpGain)).append(")");
                }
                if (rank > 0) {
                    sb.append(" [rank #").append(rank).append(']');
                }
                sb.append(System.lineSeparator());
            } else {
                sb.append("Chosen move: ").append("<unavailable>")
                        .append(" (not present in legal move snapshot)")
                        .append(System.lineSeparator());
            }

            if (bestEvaluation != null) {
                double deltaVsChosen = chosenEvaluation != null && Double.isFinite(cpLoss)
                        ? -cpLoss
                        : Double.NaN;
                sb.append("Engine top move: ").append(bestEvaluation.move())
                        .append(" -> ").append(formatCentipawns(bestEvaluation.score())).append(" pawns");
                if (Double.isFinite(deltaVsChosen)) {
                    sb.append(" (Δ vs chosen: ").append(formatCentipawns(deltaVsChosen)).append(")");
                }
                sb.append(System.lineSeparator());
            }

            sb.append("Nodes visited: ").append(nodesVisited)
                    .append(", null-move prunes: ").append(nullMoves)
                    .append(", search duration: ").append(durationMillis).append(" ms")
                    .append(System.lineSeparator());

            sb.append("Top candidates by evaluation:").append(System.lineSeparator());
            for (int i = 0; i < topCandidates.size(); i++) {
                MoveEvaluation ev = topCandidates.get(i);
                sb.append("  ").append(i + 1).append(". ").append(ev.move())
                        .append(" -> ").append(formatCentipawns(ev.score())).append(" pawns");
                if (chosenEvaluation != null) {
                    double delta = ev.score() - chosenEvaluation.score();
                    if (Math.abs(delta) < 0.5) {
                        sb.append(" (matches chosen)");
                    } else if (Double.isFinite(delta)) {
                        sb.append(" (Δ vs chosen: ").append(formatCentipawns(delta)).append(")");
                    }
                }
                sb.append(System.lineSeparator());
            }

            if (!expectedEvaluations.isEmpty()) {
                sb.append("Expected move evaluations:").append(System.lineSeparator());
                for (MoveEvaluation ev : expectedEvaluations) {
                    sb.append("  ").append(ev.move())
                            .append(" -> ").append(formatCentipawns(ev.score())).append(" pawns");
                    if (chosenEvaluation != null && !ev.move().equals(chosenEvaluation.move())) {
                        double delta = ev.score() - chosenEvaluation.score();
                        if (Double.isFinite(delta)) {
                            sb.append(" (Δ vs chosen: ").append(formatCentipawns(delta)).append(")");
                        }
                    }
                    sb.append(System.lineSeparator());
                }
            }

            if (Double.isFinite(cpLoss)) {
                sb.append("Evaluation delta vs engine best: ")
                        .append(formatCentipawns(-cpLoss)).append(" pawns")
                        .append(System.lineSeparator());
            }

            sb.append("Principal variation: ").append(principalVariation).append(System.lineSeparator());
            sb.append("==================================").append(System.lineSeparator());

            return sb.toString();
        }

        String toJsonLine() {
            StringBuilder sb = new StringBuilder();
            sb.append("[BMSTAT] {");
            boolean first = true;
            first = appendJsonEnvironment(sb, "environment", SEARCH_ENVIRONMENT, first);
            first = appendJsonString(sb, "fen", fen, first);
            first = appendJsonString(sb, "side", whiteToMove ? "w" : "b", first);
            first = appendJsonStringArray(sb, expectedMoves, first);
            first = appendJsonString(sb, "chosen", chosenEvaluation != null ? chosenEvaluation.move() : null, first);
            first = appendJsonNumber(sb, "chosenScore", chosenEvaluation != null ? chosenEvaluation.score() : null, 2, first);
            first = appendJsonString(sb, "best", bestEvaluation != null ? bestEvaluation.move() : null, first);
            first = appendJsonNumber(sb, "bestScore", bestEvaluation != null ? bestEvaluation.score() : null, 2, first);
            first = appendJsonNumber(sb, "baseline", baselineScore, 2, first);
            first = appendJsonNumber(sb, "cpLoss", cpLoss, 2, first);
            first = appendJsonNumber(sb, "cpGain", cpGain, 2, first);
            first = appendJsonInt(sb, "rank", rank > 0 ? rank : null, first);
            first = appendJsonLong(sb, "nodes", nodesVisited, first);
            first = appendJsonLong(sb, "nullMoves", nullMoves, first);
            first = appendJsonLong(sb, "durationMs", durationMillis, first);
            first = appendJsonEvaluations(sb, "topCandidates", topCandidates, first);
            first = appendJsonEvaluations(sb, "expectedCandidates", expectedEvaluations, first);
            first = appendJsonString(sb, "pv", principalVariation, first);
            sb.append('}');
            return sb.toString();
        }
    }

    private record AggregateStatistics(
            SearchEnvironment environment,
            int positions,
            double avgCpLoss,
            double maxCpLoss,
            double avgCpGain,
            double avgRank,
            double top1Rate,
            double avgNodes,
            double avgNullMoves,
            double avgDurationMs
    ) {

        static AggregateStatistics from(List<DecisionStatistics> stats, SearchEnvironment environment) {
            double totalCpLoss = 0.0;
            double maxCpLoss = Double.NEGATIVE_INFINITY;
            int cpLossCount = 0;
            double totalCpGain = 0.0;
            int cpGainCount = 0;
            double totalRank = 0.0;
            int rankCount = 0;
            long totalNodes = 0L;
            long totalNullMoves = 0L;
            long totalDuration = 0L;
            int top1 = 0;

            for (DecisionStatistics stat : stats) {
                if (Double.isFinite(stat.cpLoss())) {
                    totalCpLoss += stat.cpLoss();
                    cpLossCount++;
                    if (stat.cpLoss() > maxCpLoss) {
                        maxCpLoss = stat.cpLoss();
                    }
                }
                if (Double.isFinite(stat.cpGain())) {
                    totalCpGain += stat.cpGain();
                    cpGainCount++;
                }
                if (stat.rank() > 0) {
                    totalRank += stat.rank();
                    rankCount++;
                    if (stat.rank() == 1) {
                        top1++;
                    }
                }
                totalNodes += stat.nodesVisited();
                totalNullMoves += stat.nullMoves();
                totalDuration += stat.durationMillis();
            }

            double avgCpLoss = cpLossCount > 0 ? totalCpLoss / cpLossCount : Double.NaN;
            double maxCpLossVal = cpLossCount > 0 ? maxCpLoss : Double.NaN;
            double avgCpGain = cpGainCount > 0 ? totalCpGain / cpGainCount : Double.NaN;
            double avgRank = rankCount > 0 ? totalRank / rankCount : Double.NaN;
            double top1Rate = stats.isEmpty() ? Double.NaN : (double) top1 / stats.size();
            double avgNodes = stats.isEmpty() ? Double.NaN : (double) totalNodes / stats.size();
            double avgNullMoves = stats.isEmpty() ? Double.NaN : (double) totalNullMoves / stats.size();
            double avgDurationMs = stats.isEmpty() ? Double.NaN : (double) totalDuration / stats.size();

            return new AggregateStatistics(
                    environment,
                    stats.size(),
                    avgCpLoss,
                    maxCpLossVal,
                    avgCpGain,
                    avgRank,
                    top1Rate,
                    avgNodes,
                    avgNullMoves,
                    avgDurationMs
            );
        }

        String toHumanReadable() {
            StringBuilder sb = new StringBuilder();
            sb.append(System.lineSeparator());
            sb.append("=== Aggregate best-move metrics ===").append(System.lineSeparator());
            sb.append(environment.describe());
            sb.append("Positions tested: ").append(positions).append(System.lineSeparator());
            if (Double.isFinite(avgCpLoss)) {
                sb.append("Average evaluation loss: ").append(formatCentipawns(avgCpLoss)).append(" pawns")
                        .append(System.lineSeparator());
            }
            if (Double.isFinite(maxCpLoss)) {
                sb.append("Worst evaluation loss: ").append(formatCentipawns(maxCpLoss)).append(" pawns")
                        .append(System.lineSeparator());
            }
            if (Double.isFinite(avgCpGain)) {
                sb.append("Average gain vs baseline: ").append(formatCentipawns(avgCpGain)).append(" pawns")
                        .append(System.lineSeparator());
            }
            if (Double.isFinite(avgRank)) {
                sb.append("Average rank of chosen move: ")
                        .append(String.format(Locale.US, "%.2f", avgRank))
                        .append(System.lineSeparator());
            }
            if (Double.isFinite(top1Rate)) {
                sb.append("Top-1 accuracy: ")
                        .append(String.format(Locale.US, "%.1f%%", top1Rate * 100.0))
                        .append(System.lineSeparator());
            }
            if (Double.isFinite(avgNodes)) {
                sb.append("Average nodes searched: ")
                        .append(String.format(Locale.US, "%.0f", avgNodes))
                        .append(System.lineSeparator());
            }
            if (Double.isFinite(avgNullMoves)) {
                sb.append("Average null-move prunes: ")
                        .append(String.format(Locale.US, "%.0f", avgNullMoves))
                        .append(System.lineSeparator());
            }
            if (Double.isFinite(avgDurationMs)) {
                sb.append("Average search duration: ")
                        .append(String.format(Locale.US, "%.1f ms", avgDurationMs))
                        .append(System.lineSeparator());
            }
            sb.append("===================================").append(System.lineSeparator());
            return sb.toString();
        }

        String toJsonLine() {
            StringBuilder sb = new StringBuilder();
            sb.append("[BMSUM] {");
            boolean first = true;
            first = appendJsonEnvironment(sb, "environment", environment, first);
            first = appendJsonInt(sb, "positions", positions, first);
            first = appendJsonNumber(sb, "avgCpLoss", avgCpLoss, 2, first);
            first = appendJsonNumber(sb, "maxCpLoss", maxCpLoss, 2, first);
            first = appendJsonNumber(sb, "avgCpGain", avgCpGain, 2, first);
            first = appendJsonNumber(sb, "avgRank", avgRank, 2, first);
            first = appendJsonNumber(sb, "top1Rate", top1Rate, 4, first);
            first = appendJsonNumber(sb, "avgNodes", avgNodes, 2, first);
            first = appendJsonNumber(sb, "avgNullMoves", avgNullMoves, 2, first);
            first = appendJsonNumber(sb, "avgDurationMs", avgDurationMs, 2, first);
            sb.append('}');
            return sb.toString();
        }
    }

    private static boolean appendJsonString(StringBuilder sb, String key, String value, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(jsonEscape(key)).append('"').append(':');
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(jsonEscape(value)).append('"');
        }
        return false;
    }

    private static boolean appendJsonStringArray(StringBuilder sb, List<String> values, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(jsonEscape("expected")).append('"').append(':');
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(jsonEscape(values.get(i))).append('"');
        }
        sb.append(']');
        return false;
    }

    private static boolean appendJsonNumber(StringBuilder sb, String key, Double value, int decimals, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(jsonEscape(key)).append('"').append(':');
        String formatted = formatJsonNumber(value, decimals);
        sb.append(formatted != null ? formatted : "null");
        return false;
    }

    private static boolean appendJsonInt(StringBuilder sb, String key, Integer value, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(jsonEscape(key)).append('"').append(':');
        if (value == null) {
            sb.append("null");
        } else {
            sb.append(value.intValue());
        }
        return false;
    }

    private static boolean appendJsonLong(StringBuilder sb, String key, Long value, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(jsonEscape(key)).append('"').append(':');
        if (value == null) {
            sb.append("null");
        } else {
            sb.append(value.longValue());
        }
        return false;
    }

    private static boolean appendJsonBoolean(StringBuilder sb, String key, Boolean value, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(jsonEscape(key)).append('"').append(':');
        if (value == null) {
            sb.append("null");
        } else {
            sb.append(value.booleanValue());
        }
        return false;
    }

    private static boolean appendJsonEnvironment(StringBuilder sb, String key, SearchEnvironment environment, boolean first) {
        if (environment == null) {
            return first;
        }
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(jsonEscape(key)).append('"').append(':');
        sb.append('{');
        boolean innerFirst = true;
        innerFirst = appendJsonInt(sb, "availableProcessors", environment.availableProcessors(), innerFirst);
        innerFirst = appendJsonInt(sb, "searchThreads", environment.searchThreads(), innerFirst);
        innerFirst = appendJsonInt(sb, "lazySmpThreads", environment.lazySmpThreads(), innerFirst);
        innerFirst = appendJsonInt(sb, "rootParallelLimit", environment.rootParallelLimit(), innerFirst);
        innerFirst = appendJsonInt(sb, "ttMb", environment.transpositionTableMb(), innerFirst);
        appendJsonBoolean(sb, "multiThreaded", environment.multiThreaded(), innerFirst);
        sb.append('}');
        return false;
    }

    private static boolean appendJsonEvaluations(StringBuilder sb, String key, List<MoveEvaluation> values, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(jsonEscape(key)).append('"').append(':');
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            MoveEvaluation ev = values.get(i);
            sb.append('{');
            sb.append("\"move\":\"").append(jsonEscape(ev.move())).append("\",");
            String formatted = formatJsonNumber(ev.score(), 2);
            sb.append("\"score\":").append(formatted != null ? formatted : "null");
            sb.append('}');
        }
        sb.append(']');
        return false;
    }

    private static String jsonEscape(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format(Locale.US, "\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String formatJsonNumber(Double value, int decimals) {
        if (value == null) {
            return null;
        }
        return formatJsonNumber(value.doubleValue(), decimals);
    }

    private static String formatJsonNumber(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return null;
        }
        String format = "%." + decimals + "f";
        return String.format(Locale.US, format, value);
    }


    private String renderPrincipalVariation(boolean whiteToMove, List<MoveAndScore> pv) {
        if (pv == null || pv.isEmpty()) {
            return "<unavailable>";
        }

        List<String> segments = new ArrayList<>(pv.size());
        boolean moverIsWhite = whiteToMove;
        for (MoveAndScore moveAndScore : pv) {
            String notation = Move.convertIntToMove(moveAndScore.getMove()).toString();
            double orientedScore = moverIsWhite ? moveAndScore.getScore() : -moveAndScore.getScore();
            segments.add(notation + " (" + formatCentipawns(orientedScore) + " pawns)");
            moverIsWhite = !moverIsWhite;
        }
        return String.join(" -> ", segments);
    }

    private static double orientScoreForMover(boolean whiteToMove, double scoreDifference) {
        return whiteToMove ? scoreDifference : -scoreDifference;
    }

    private static String formatCentipawns(double centipawns) {
        if (!Double.isFinite(centipawns)) {
            return "n/a";
        }
        if (Math.abs(centipawns) >= Score.CHECKMATE - 1000) {
            double mateDistance = Score.CHECKMATE - Math.abs(centipawns);
            long plies = Math.max(0, Math.round(mateDistance));
            return (centipawns > 0 ? "#+" : "#-") + (plies > 0 ? plies : "");
        }
        return String.format(Locale.US, "%+.2f", centipawns / 100.0);
    }

    private record MoveEvaluation(String move, double score) {
    }
}
