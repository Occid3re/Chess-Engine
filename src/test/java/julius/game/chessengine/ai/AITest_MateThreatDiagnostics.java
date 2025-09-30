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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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

        AiTuning tuning = AiTuning.builder()
                .searchThreads(1)
                .lazySmpThreads(1)
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
        protected MoveAndScore searchRootMoves(Engine simulatorEngine, SearchTask task, int depth,
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
                    return null;
                }

                double bestScore = isWhite ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
                int bestMove = -1;

                for (int index = 0; index < ordered.size(); index++) {
                    if (abortRequested(deadline)) {
                        trace.addNote("Abort requested before analysing move index " + index);
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
                return result;
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
                        double alphaBefore, double betaBefore, double alphaAfterLoop, double betaAfterLoop) {
                moves.add(new RootMoveTrace(moveInt, order, evaluation, nodesSpent, nanosSpent,
                        alphaBefore, betaBefore, alphaAfterLoop, betaAfterLoop));
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
    }
}
