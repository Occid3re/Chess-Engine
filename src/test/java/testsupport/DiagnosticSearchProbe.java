package testsupport;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.ai.AI;
import julius.game.chessengine.ai.MoveAndScore;
import julius.game.chessengine.ai.SearchTask;
import julius.game.chessengine.ai.TranspositionTable;
import julius.game.chessengine.ai.TranspositionTableEntry;
import julius.game.chessengine.board.Move;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.tuning.AiTuning;
import julius.game.chessengine.utils.Score;

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

/**
 * Instrumented AI used in tests to produce verbose diagnostics about the
 * iterative-deepening process, move ordering and transposition table
 * interaction without modifying the production AI implementation.
 */
public final class DiagnosticSearchProbe extends AI {

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

    public DiagnosticSearchProbe(Engine engine, AiTuning tuning) {
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
            throw new IllegalStateException("Unable to bootstrap DiagnosticSearchProbe instrumentation", e);
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
                trace.addNote("No legal moves available at root");
                trace.finish(alpha, beta, null);
                return null;
            }

            long nodesBeforeDepth = nodesVisited();
            long depthStart = System.nanoTime();

            MoveAndScore best = null;
            double bestScore = isWhite ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;

            for (int order = 0; order < ordered.size(); order++) {
                if (abortRequested(deadline)) {
                    trace.addNote("Search aborted due to deadline");
                    break;
                }

                int moveInt = ordered.getInt(order);
                double alphaBefore = alpha;
                double betaBefore = beta;

                simulatorEngine.performMove(moveInt);
                EvaluationResult evaluation = evaluateAfterRootMove(simulatorEngine, depth, alpha, beta, isWhite,
                        deadline, moveInt);
                simulatorEngine.undoLastMove();

                long nodesAfterMove = nodesVisited();
                long nanosNow = System.nanoTime();

                trace.record(moveInt, order + 1, evaluation, nodesAfterMove - nodesBeforeDepth,
                        nanosNow - depthStart, alphaBefore, betaBefore, alpha, beta);

                if (evaluation.exitEarly) {
                    trace.addNote("Termination requested while evaluating " + formatMove(moveInt));
                    break;
                }

                if (evaluation.hasScore() && (boolean) isBetterScoreMethod.invoke(this, isWhite, evaluation.score, bestScore)) {
                    bestScore = evaluation.score;
                    best = new MoveAndScore(moveInt, evaluation.score);
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

            trace.finish(alpha, beta, best);
            return best;
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

    public long nullMovesUsed() {
        try {
            return nullMoveCountField.getLong(this);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<DepthTrace> getDepthTraces() {
        synchronized (depthTraces) {
            return new ArrayList<>(depthTraces);
        }
    }

    public String buildMateThreatReport(String fen, List<String> lifesavingMoves, MoveAndScore result,
                                        Duration wallClockDuration) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append(System.lineSeparator());
        sb.append("================ Mate Threat Diagnostic ================").append(System.lineSeparator());
        sb.append("FEN: ").append(fen).append(System.lineSeparator());
        sb.append("Expected defensive resources: ")
                .append(String.join(", ", lifesavingMoves)).append(System.lineSeparator());

        appendSearchSummary(sb, result, wallClockDuration);

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

    public String buildBenchmarkReport(String fen, int targetDepth, MoveAndScore result,
                                       Duration wallClockDuration) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append(System.lineSeparator());
        sb.append("================ Custom Position Benchmark =============").append(System.lineSeparator());
        sb.append("FEN: ").append(fen).append(System.lineSeparator());
        sb.append("Target search depth: ").append(targetDepth).append(System.lineSeparator());

        appendSearchSummary(sb, result, wallClockDuration);

        sb.append(System.lineSeparator());
        sb.append("-- Iterative deepening overview --").append(System.lineSeparator());

        List<DepthTrace> tracesSnapshot = getDepthTraces();
        tracesSnapshot.sort(Comparator.comparingInt(DepthTrace::depth)
                .thenComparingInt(DepthTrace::attempt));

        for (DepthTrace trace : tracesSnapshot) {
            sb.append(trace.describe(Collections.emptySet())).append(System.lineSeparator());
        }

        sb.append("========================================================").append(System.lineSeparator());
        return sb.toString();
    }

    private void appendSearchSummary(StringBuilder sb, MoveAndScore result, Duration wallClockDuration) {
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

    public final class DepthTrace {
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

        private String describe(Set<String> highlightMoves) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT,
                    "Depth %2d (attempt %d, %s to move) α=%.2f β=%.2f", depth, attempt,
                    whiteToMove ? "white" : "black", initialAlpha, initialBeta));
            sb.append(System.lineSeparator());

            for (RootMoveTrace moveTrace : moves) {
                sb.append("   ");
                sb.append(moveTrace.describe(highlightMoves));
                sb.append(System.lineSeparator());
            }

            if (!notes.isEmpty()) {
                for (String note : notes) {
                    sb.append("   note: ").append(note).append(System.lineSeparator());
                }
            }

            sb.append("   result → best=")
                    .append(finalBest != null ? formatMove(finalBest.move) + String.format(Locale.ROOT,
                            " (%.2f)", finalBest.score) : "<none>")
                    .append(", α=").append(String.format(Locale.ROOT, "%.2f", finalAlpha))
                    .append(", β=").append(String.format(Locale.ROOT, "%.2f", finalBeta));
            if (betaCutoff) {
                sb.append(" (beta cutoff via ").append(formatMove(cutoffMove)).append(')');
            }
            return sb.toString();
        }

        int depth() {
            return depth;
        }

        int attempt() {
            return attempt;
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
        private final double alphaAfterLoop;
        private final double betaAfterLoop;

        RootMoveTrace(int moveInt, int order, EvaluationResult evaluation, long nodesSpent, long nanosSpent,
                      double alphaBefore, double betaBefore, double alphaAfterLoop, double betaAfterLoop) {
            this.moveInt = moveInt;
            this.order = order;
            this.evaluation = evaluation;
            this.nodesSpent = nodesSpent;
            this.nanosSpent = nanosSpent;
            this.alphaBefore = alphaBefore;
            this.betaBefore = betaBefore;
            this.alphaAfterLoop = alphaAfterLoop;
            this.betaAfterLoop = betaAfterLoop;
        }

        private String describe(Set<String> highlightMoves) {
            String label = formatMove(moveInt);
            boolean highlight = highlightMoves.contains(normalizeMoveLabel(label))
                    || highlightMoves.contains(normalizeMoveLabel(Move.convertIntToMove(moveInt).toString()));

            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "#%02d %-12s", order, label));
            sb.append(highlight ? " *" : "  ");
            sb.append(String.format(Locale.ROOT, " nodes=%-7d time=%4.1fms", nodesSpent,
                    nanosSpent / 1_000_000.0));

            sb.append(String.format(Locale.ROOT, " α:%.2f→%.2f β:%.2f→%.2f", alphaBefore, alphaAfterLoop,
                    betaBefore, betaAfterLoop));

            if (evaluation.hasScore()) {
                sb.append(String.format(Locale.ROOT, " score=%s", formatScore(evaluation.score)));
            } else {
                sb.append(" score=<n/a>");
            }
            sb.append(" reason=").append(evaluation.reason);
            return sb.toString();
        }

        private String formatScore(double score) {
            if (score >= Score.CHECKMATE_THRESHOLD) {
                return String.format(Locale.ROOT, "mate %+d", (int) (Score.CHECKMATE - score));
            }
            if (score <= -Score.CHECKMATE_THRESHOLD) {
                return String.format(Locale.ROOT, "mate -%d", (int) (Score.CHECKMATE + score));
            }
            return String.format(Locale.ROOT, "%+.2f", score);
        }
    }
}
