package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.Move;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.tuning.AiTuning;
import julius.game.chessengine.utils.Score;
import julius.game.chessengine.syzygy.TestSyzygySupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import testsupport.TestReportWriter;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Verifies that the AI selects the expected best move from a set of FEN
 * positions within a small time budget. Similar in spirit to
 * {@link MateSearchTest} but checks the single move chosen by the engine
 * -Xms8g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+AlwaysPreTouch -XX:+UseNUMA -XX:ActiveProcessorCount=24 -Dchessengine.tt.mb=256 -Dchessengine.searchThreads=16 -Dchessengine.lazySmpThreads=8 -Dchessengine.rootParallelLimit=48
 * instead of the game result.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BestMoveSearchTest {

    private static final String PARALLEL_OVERRIDE_PROPERTY = "chessengine.diagnostics.useParallelRoot";
    private static final String ROOT_FANOUT_RATIO_PROPERTY = "chessengine.rootFanoutRatio";

    static {
        // Default to full PC resources (matches Lichess blitz config: 16 threads, 1GB hash)
        // Override with -Dchessengine.searchThreads=N or -Dchessengine.bestmove.timeMs=N
        System.setProperty(PARALLEL_OVERRIDE_PROPERTY,
                System.getProperty(PARALLEL_OVERRIDE_PROPERTY, "false"));
        System.setProperty(ROOT_FANOUT_RATIO_PROPERTY,
                System.getProperty(ROOT_FANOUT_RATIO_PROPERTY, "0.25"));
        System.setProperty("chessengine.searchThreads",
                System.getProperty("chessengine.searchThreads", "16"));
        System.setProperty("chessengine.lazySmpThreads",
                System.getProperty("chessengine.lazySmpThreads", "1"));
        System.setProperty("chessengine.rootParallelLimit",
                System.getProperty("chessengine.rootParallelLimit", "120"));
        System.setProperty("chessengine.tt.mb",
                System.getProperty("chessengine.tt.mb", "2048"));
    }

    private static final SearchEnvironment SEARCH_ENVIRONMENT = SearchEnvironment.detect();
    private static final int DEFAULT_SEARCH_DEPTH = 4;
    /** Time budget per position in milliseconds. Mirrors Lichess blitz allocation (~3s/move). */
    private static final long DEFAULT_SEARCH_TIME_MS = Long.parseLong(
            System.getProperty("chessengine.bestmove.timeMs", "3000"));
    private static final long UNBOUNDED_SEARCH_TIME_MILLIS = java.util.concurrent.TimeUnit.DAYS.toMillis(365L * 100L);

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
    void testBestMove(String fen, List<String> expectedMoves, Integer depthOverride) throws InterruptedException {
        Engine engine = new Engine();
        engine.importBoardFromFen(fen);

        // If a depth override is provided, use depth-based search; otherwise use time-based.
        // Time-based with full PC resources gives realistic Lichess-like performance.
        // In neural mode, depth overrides may hang forever — fall back to time-based.
        boolean neuralMode = "neural".equalsIgnoreCase(
                System.getProperty("chessengine.eval.mode", "classic"));
        boolean useDepthSearch = depthOverride != null && !neuralMode;
        int searchDepth = depthOverride != null ? depthOverride : 32;
        long searchTimeMs = useDepthSearch ? UNBOUNDED_SEARCH_TIME_MILLIS : DEFAULT_SEARCH_TIME_MS;
        List<String> expectedMovesView = List.copyOf(expectedMoves);

        AiTuning tuning = SEARCH_ENVIRONMENT.applyTo(AiTuning.builder())
                .maxDepth(searchDepth)
                .timeLimitMillis(searchTimeMs)
                .nullMovePruning(true)
                .build();

        DiagnosticAI ai = new DiagnosticAI(engine, tuning);

        long nodesBefore = ai.getNodesVisited();
        long nullMovesBefore = ai.getNullMoveCount();
        long startNanos = System.nanoTime();

        MoveAndScore result = useDepthSearch
                ? ai.searchToDepthBlocking(searchDepth)
                : ai.searchForTimeBlocking(searchTimeMs);
        Duration wallClock = Duration.ofNanos(System.nanoTime() - startNanos);

        int deepestCompletedDepth = ai.deepestCompletedDepth();

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
                expectedMovesView,
                durationMillis,
                nodesVisited,
                nullMoves,
                deepestCompletedDepth,
                searchDepth
        );
        decisionSummaries.add(statistics);

        String humanReadable = statistics.toHumanReadable();
        String diagnostics = ai.buildDiagnosticsReport(fen, expectedMovesView, result, wallClock);
        System.out.println(humanReadable);
        System.out.println(statistics.toJsonLine());
        System.out.println(diagnostics);

        decisionJsonLines.add(statistics.toJsonLine());
        decisionTextBlocks.add(humanReadable + diagnostics);

        // Only enforce the depth ceiling for depth-overridden tests (time-based runs are unbounded).
        if (depthOverride != null) {
            Assertions.assertTrue(deepestCompletedDepth <= searchDepth,
                    () -> "Search reached depth " + deepestCompletedDepth + " before stopping; target depth was "
                            + searchDepth + System.lineSeparator()
                            + ai.depthCoverageSummary() + System.lineSeparator()
                            + humanReadable + System.lineSeparator() + diagnostics);
        }

        boolean acceptedMove = expectedMovesView.contains(moveString);

        Assertions.assertTrue(acceptedMove,
                () -> "Expected one of " + expectedMovesView + " but got " + moveString + " for FEN: " + fen
                        + " (cpLoss=" + statistics.cpLoss() + ")" + humanReadable
                        + System.lineSeparator() + diagnostics);
    }

/*    @Test
    void diagnoseNe4SearchHotSpot() throws InterruptedException {
        final String fen = "3rk2r/1bqpbppp/p1n1p3/1p2P3/5Bn1/2NQ1N2/PPP1BPPP/R2R2K1 w k - 5 14";
        final List<String> expectedMoves = List.of("Ne4");

        Engine engine = new Engine();
        engine.importBoardFromFen(fen);

        int searchDepth = DEFAULT_SEARCH_DEPTH;

        AiTuning tuning = SEARCH_ENVIRONMENT.applyTo(AiTuning.builder())
                .maxDepth(searchDepth)
                .timeLimitMillis(UNBOUNDED_SEARCH_TIME_MILLIS)
                .nullMovePruning(true)
                .build();

        DiagnosticAI ai = new DiagnosticAI(engine, tuning);

        long nodesBefore = ai.getNodesVisited();
        long nullBefore = ai.getNullMoveCount();
        long startNanos = System.nanoTime();

        MoveAndScore result = ai.searchToDepthBlocking(searchDepth);

        long elapsedNanos = System.nanoTime() - startNanos;
        long nodesVisited = Math.max(0, ai.getNodesVisited() - nodesBefore);
        long nullMoves = Math.max(0, ai.getNullMoveCount() - nullBefore);

        Assertions.assertNotNull(result, () -> "Engine failed to produce a move for FEN: " + fen);

        String moveString = Move.convertIntToMove(result.getMove()).toString();
        boolean expectedMoveChosen = matchesExpectedMove(result.getMove(), expectedMoves);
        if (!expectedMoveChosen) {
            System.out.printf(Locale.ROOT,
                    "WARNING: expected one of %s but the engine chose %s%n",
                    expectedMoves,
                    moveString);
        }

        Duration wallClock = Duration.ofNanos(elapsedNanos);

        System.out.println("================ Slow Ne4 diagnostic ================");
        System.out.printf(Locale.ROOT,
                "Result=%s score=%.2f depth=%d wallClock=%d ms nodes=%d nullMoves=%d%n",
                moveString,
                result.getScore(),
                ai.deepestCompletedDepth(),
                wallClock.toMillis(),
                nodesVisited,
                nullMoves);
        System.out.println("Expected moves: " + expectedMoves);
        System.out.println("Result matches expected set: " + (expectedMoveChosen ? "yes" : "no"));
        System.out.println("Environment: " + SEARCH_ENVIRONMENT.inlineSummary());

        List<DiagnosticAI.DepthTraceSnapshot> depthSnapshots = ai.snapshotDepthTraces();
        depthSnapshots.sort(Comparator
                .comparingInt(DiagnosticAI.DepthTraceSnapshot::depth)
                .thenComparingInt(DiagnosticAI.DepthTraceSnapshot::attempt));

        Map<Integer, Long> nodesByDepth = new LinkedHashMap<>();
        Map<Integer, Long> timeByDepth = new LinkedHashMap<>();
        Map<Integer, Integer> attemptsByDepth = new LinkedHashMap<>();
        Map<Integer, Integer> movesByDepth = new LinkedHashMap<>();
        Map<String, Long> nodesByMove = new HashMap<>();
        Map<String, Long> timeByMove = new HashMap<>();
        Map<String, Boolean> expectedMoveFlags = new HashMap<>();

        for (DiagnosticAI.DepthTraceSnapshot depth : depthSnapshots) {
            long depthNodes = depth.rootMoves().stream()
                    .mapToLong(DiagnosticAI.RootMoveSnapshot::nodesSpent)
                    .sum();
            long depthTime = depth.rootMoves().stream()
                    .mapToLong(DiagnosticAI.RootMoveSnapshot::nanosSpent)
                    .sum();

            nodesByDepth.merge(depth.depth(), depthNodes, Long::sum);
            timeByDepth.merge(depth.depth(), depthTime, Long::sum);
            attemptsByDepth.merge(depth.depth(), 1, Integer::sum);
            movesByDepth.merge(depth.depth(), depth.rootMoves().size(), Integer::sum);

            for (DiagnosticAI.RootMoveSnapshot move : depth.rootMoves()) {
                String label = describeMove(move.moveInt());
                nodesByMove.merge(label, move.nodesSpent(), Long::sum);
                timeByMove.merge(label, move.nanosSpent(), Long::sum);
                if (matchesExpectedMove(move.moveInt(), expectedMoves)) {
                    expectedMoveFlags.merge(label, true, Boolean::logicalOr);
                }
            }
        }

        System.out.println("-- Depth aggregates --");
        for (Map.Entry<Integer, Long> entry : nodesByDepth.entrySet()) {
            int depth = entry.getKey();
            long nodes = entry.getValue();
            long millis = Duration.ofNanos(timeByDepth.getOrDefault(depth, 0L)).toMillis();
            int attempts = attemptsByDepth.getOrDefault(depth, 0);
            int totalMoves = movesByDepth.getOrDefault(depth, 0);
            double avgMoves = attempts == 0 ? 0.0 : totalMoves / (double) attempts;
            System.out.printf(Locale.ROOT,
                    "Depth %d: attempts=%d avgRootMoves=%.2f nodes=%d time=%d ms%n",
                    depth,
                    attempts,
                    avgMoves,
                    nodes,
                    millis);
        }

        System.out.println("-- Root move hot spots (aggregated) --");
        nodesByMove.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(8)
                .forEach(entry -> {
                    String move = entry.getKey();
                    long nodes = entry.getValue();
                    long nanos = timeByMove.getOrDefault(move, 0L);
                    double percentage = nodesVisited == 0 ? 0.0 : (nodes * 100.0) / nodesVisited;
                    boolean highlight = expectedMoveFlags.getOrDefault(move, false);
                    System.out.printf(Locale.ROOT,
                            "  %-8s nodes=%-8d (%.2f%%) time=%.2f ms%n",
                            highlight ? move + " *" : move,
                            nodes,
                            percentage,
                            nanos / 1_000_000.0);
                });

        System.out.println("-- Per-depth top candidates by nodes --");
        for (DiagnosticAI.DepthTraceSnapshot depth : depthSnapshots) {
            long depthNodes = depth.rootMoves().stream()
                    .mapToLong(DiagnosticAI.RootMoveSnapshot::nodesSpent)
                    .sum();
            double elapsedMs = depth.elapsedNanos() / 1_000_000.0;
            System.out.printf(Locale.ROOT,
                    "Depth %d attempt %d (%s to move): nodes=%d time=%.2f ms betaCutoff=%s completed=%s%n",
                    depth.depth(),
                    depth.attempt(),
                    depth.whiteToMove() ? "white" : "black",
                    depthNodes,
                    elapsedMs,
                    depth.betaCutoff() ? "yes" : "no",
                    depth.completed() ? "yes" : "no");

            List<DiagnosticAI.RootMoveSnapshot> ordered = depth.rootMoves().stream()
                    .sorted(Comparator.comparingLong(DiagnosticAI.RootMoveSnapshot::nodesSpent).reversed())
                    .collect(Collectors.toList());

            int limit = Math.min(5, ordered.size());
            for (int i = 0; i < limit; i++) {
                DiagnosticAI.RootMoveSnapshot move = ordered.get(i);
                String moveLabel = describeMove(move.moveInt());
                boolean highlight = matchesExpectedMove(move.moveInt(), expectedMoves);
                String score = formatScore(move.score());
                System.out.printf(Locale.ROOT,
                        "    #%02d %-10s nodes=%-7d time=%6.2f ms window=[%.2f, %.2f]→[%.2f, %.2f] result=%s via=%s%s%s%n",
                        move.order() + 1,
                        highlight ? moveLabel + " *" : moveLabel,
                        move.nodesSpent(),
                        move.nanosSpent() / 1_000_000.0,
                        move.alphaBefore(),
                        move.betaBefore(),
                        move.alphaAfter(),
                        move.betaAfter(),
                        score,
                        move.reason(),
                        move.exitEarly() ? " (aborted)" : "",
                        highlight ? " <- expected" : "");
            }
        }

        System.out.println("=======================================================");
    }*/

    private static String describeMove(int moveInt) {
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

    private static String formatScore(Double score) {
        if (score == null) {
            return "<none>";
        }
        if (score.isNaN()) {
            return "NaN";
        }
        if (score.isInfinite()) {
            return score > 0 ? "+Inf" : "-Inf";
        }
        return String.format(Locale.ROOT, "%.2f", score);
    }

    private static boolean matchesExpectedMove(int moveInt, List<String> expectedMoves) {
        if (expectedMoves == null || expectedMoves.isEmpty()) {
            return false;
        }
        String san = Move.convertIntToMove(moveInt).toString();
        String coords = MoveHelper.convertIndexToString(MoveHelper.deriveFromIndex(moveInt))
                + MoveHelper.convertIndexToString(MoveHelper.deriveToIndex(moveInt));
        String normalizedSan = san.toLowerCase(Locale.ROOT);
        String normalizedCoords = coords.toLowerCase(Locale.ROOT);
        for (String expected : expectedMoves) {
            if (expected == null) {
                continue;
            }
            String normalized = expected.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                continue;
            }
            if (normalized.equals(normalizedSan) || normalized.equals(normalizedCoords)) {
                return true;
            }
        }
        return false;
    }

    private DecisionStatistics compileDecisionStatistics(String fen,
                                                         String chosenMove,
                                                         MoveAndScore searchResult,
                                                         DiagnosticAI ai,
                                                         List<String> expectedMoves,
                                                         long durationMillis,
                                                         long nodesVisited,
                                                         long nullMoves,
                                                         int deepestDepth,
                                                         int targetDepth) {
        Engine analysisEngine = new Engine();
        analysisEngine.importBoardFromFen(fen);

        boolean whiteToMove = fen.split(" ")[1].equals("w");
        double baselineForMover = normalizeScoreForRoot(
                whiteToMove,
                analysisEngine.getGameState().getScore().getScoreDifference()
        );

        // Snapshot legal moves
        IntArrayList legalMovesSnapshot = analysisEngine.getAllLegalMoves();
        List<Integer> legalMoves = new ArrayList<>(legalMovesSnapshot.size());
        for (int i = 0; i < legalMovesSnapshot.size(); i++) {
            legalMoves.add(legalMovesSnapshot.getInt(i));
        }

        // Build 0-ply static evaluations for each legal move (push once, read eval, undo)
        List<MoveEvaluation> evaluations = new ArrayList<>(legalMoves.size());
        Map<String, MoveEvaluation> evaluationMap = new HashMap<>(Math.max(legalMoves.size(), 1));
        for (int moveInt : legalMoves) {
            analysisEngine.performMove(moveInt);
            double scoreDiff = analysisEngine.getGameState().getScore().getScoreDifference();
            double moverScore = normalizeScoreForRoot(whiteToMove, scoreDiff);
            analysisEngine.undoLastMove();

            String san = Move.convertIntToMove(moveInt).toString();
            MoveEvaluation evaluation = new MoveEvaluation(san, moverScore);
            evaluations.add(evaluation);
            // Use the SAN as the key (consistent with how you lookups later)
            evaluationMap.putIfAbsent(san, evaluation);
        }

        // Sort static candidates best→worst for the mover
        Comparator<MoveEvaluation> comparator = whiteToMove
                ? Comparator.comparingDouble(MoveEvaluation::score).reversed()
                : Comparator.comparingDouble(MoveEvaluation::score);
        evaluations.sort(comparator);

        // Chosen move:
        // keep a reference to the static evaluation (if available) and, when a
        // search result exists, override the displayed score with the searched
        // value so the diagnostics reflect the engine output.
        MoveEvaluation staticChosenEvaluation = evaluationMap.get(chosenMove);
        MoveEvaluation chosenEvaluation = staticChosenEvaluation;
        if (searchResult != null) {
            double oriented = normalizeScoreForRoot(whiteToMove, searchResult.getScore());
            chosenEvaluation = new MoveEvaluation(chosenMove, oriented);
        }

        MoveEvaluation bestEvaluation = evaluations.isEmpty() ? null : evaluations.getFirst();

        // Expected moves (static, just for display)
        List<MoveEvaluation> expectedEvaluationDetails = new ArrayList<>();
        for (String expected : expectedMoves) {
            MoveEvaluation evaluation = evaluationMap.get(expected);
            if (evaluation != null) {
                expectedEvaluationDetails.add(evaluation);
            }
        }

        // Top 5 static candidates
        List<MoveEvaluation> topCandidates =
                new ArrayList<>(evaluations.subList(0, Math.min(5, evaluations.size())));

        // Scalars
        double bestScore = bestEvaluation != null ? bestEvaluation.score() : Double.NaN;
        double chosenStaticScore = staticChosenEvaluation != null ? staticChosenEvaluation.score() : Double.NaN;

        // Compute cpLoss from the static evaluations of the best and chosen moves.
        // Skip only when either value is unavailable or non-finite.
        double cpLoss = Double.isFinite(bestScore) && Double.isFinite(chosenStaticScore)
                ? Math.max(0.0, bestScore - chosenStaticScore)
                : Double.NaN;

        double chosenDisplayedScore = chosenEvaluation != null ? chosenEvaluation.score() : Double.NaN;
        double cpGain = Double.isFinite(chosenDisplayedScore) && Double.isFinite(baselineForMover)
                ? chosenDisplayedScore - baselineForMover
                : Double.NaN;

        // Rank is based on static ordering; if desired, you could recompute rank from search ordering
        int rank = -1;
        MoveEvaluation staticChosen = evaluationMap.get(chosenMove);
        if (staticChosen != null) {
            int index = evaluations.indexOf(staticChosen);
            if (index >= 0) {
                rank = index + 1;
            }
        }

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
                expectedEvaluationDetails,
                deepestDepth,
                targetDepth
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
        private volatile int targetDepth;

        DiagnosticAI(Engine engine, AiTuning tuning) {
            super(engine, tuning, TestSyzygySupport.maybeCreateServiceFromConfiguration()
                    .orElse(null));
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

                evaluateStaticPositionMethod = AI.class.getDeclaredMethod(
                        "evaluateStaticPosition",
                        GameState.class, long.class, boolean.class, int.class
                );
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

        MoveAndScore searchToDepthBlocking(int targetDepth) {
            if (targetDepth <= 0) {
                throw new IllegalArgumentException("targetDepth must be positive");
            }
            this.targetDepth = targetDepth;
            resetDepthDiagnostics();
            return super.searchBestMoveBlocking(UNBOUNDED_SEARCH_TIME_MILLIS);
        }

        MoveAndScore searchForTimeBlocking(long timeLimitMillis) {
            if (timeLimitMillis <= 0) {
                throw new IllegalArgumentException("timeLimitMillis must be positive");
            }
            this.targetDepth = 32; // bounded only by time
            resetDepthDiagnostics();
            return super.searchBestMoveBlocking(timeLimitMillis);
        }

        int deepestCompletedDepth() {
            List<DepthTrace> snapshot = getDepthTraces();
            int deepest = 0;
            for (DepthTrace trace : snapshot) {
                if (trace.completed() && trace.depth() > deepest) {
                    deepest = trace.depth();
                }
            }
            return deepest;
        }

        String depthCoverageSummary() {
            int deepest = deepestCompletedDepth();
            int totalIterations = depthAttemptCounter.values().stream()
                    .mapToInt(AtomicInteger::get)
                    .sum();
            int target = targetDepth;
            return String.format(Locale.ROOT,
                    "Depth coverage: target=%d, deepestCompleted=%d, iterations=%d",
                    target, deepest, totalIterations);
        }

        @Override
        protected RootSearchResult searchRootMoves(Engine simulatorEngine, SearchTask task, int depth,
                                                   double alpha, double beta, SplittableRandom rng) {
            if (Boolean.parseBoolean(System.getProperty(PARALLEL_OVERRIDE_PROPERTY, "false"))) {
                return super.searchRootMoves(simulatorEngine, task, depth, alpha, beta, rng);
            }

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
                    trace.finish(alpha, beta, null, true);
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
                trace.finish(alpha, beta, result, !aborted);
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
                long boardHash = simulatorEngine.getBoardStateHash();
                // after a root move by 'isWhite', it's now opponent to move → !isWhite
                double eval = (double) evaluateStaticPositionMethod.invoke(this, state, boardHash, !isWhite, depth);
                // keep your orientation: alpha-beta below already returns scores from the root’s POV.
                // If your eval is from side-to-move POV, flip to root POV:
                if (isWhite) { // same condition you had before
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

        List<DepthTraceSnapshot> snapshotDepthTraces() {
            List<DepthTrace> traces = getDepthTraces();
            long capturedAt = System.nanoTime();
            List<DepthTraceSnapshot> snapshots = new ArrayList<>(traces.size());
            for (DepthTrace trace : traces) {
                List<RootMoveSnapshot> moves = new ArrayList<>(trace.moves.size());
                for (RootMoveTrace moveTrace : trace.moves) {
                    moves.add(new RootMoveSnapshot(
                            moveTrace.moveInt,
                            moveTrace.order,
                            moveTrace.nodesSpent,
                            moveTrace.nanosSpent,
                            moveTrace.alphaBefore,
                            moveTrace.betaBefore,
                            moveTrace.alphaAfter,
                            moveTrace.betaAfter,
                            moveTrace.evaluation.score,
                            moveTrace.evaluation.exitEarly,
                            moveTrace.evaluation.reason
                    ));
                }
                long finishedAt = trace.finishedAt != 0L ? trace.finishedAt : capturedAt;
                snapshots.add(new DepthTraceSnapshot(
                        trace.depth,
                        trace.attempt,
                        trace.whiteToMove,
                        trace.completed,
                        trace.betaCutoff,
                        trace.cutoffMove,
                        trace.initialAlpha,
                        trace.initialBeta,
                        trace.finalAlpha,
                        trace.finalBeta,
                        Math.max(0L, finishedAt - trace.startedAt),
                        List.copyOf(moves)
                ));
            }
            return snapshots;
        }

        private void resetDepthDiagnostics() {
            depthAttemptCounter.clear();
            synchronized (depthTraces) {
                depthTraces.clear();
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

            boolean whiteToMove = fen.split(" ")[1].equals("w");

            sb.append(depthCoverageSummary()).append(System.lineSeparator());
            int target = targetDepth;
            if (target > 0 && deepestCompletedDepth() < target) {
                sb.append("WARNING: target depth not reached before abort").append(System.lineSeparator());
            }

            sb.append("Search result: ");
            if (result == null) {
                sb.append("<no move found>");
            } else {
                double normalized = normalizeScoreForRoot(whiteToMove, result.score);
                sb.append(formatMove(result.move)).append(" (score=")
                        .append(String.format(Locale.ROOT, "%.2f", normalized)).append(')');
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
            private boolean completed;
            private long finishedAt;

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
                        alphaBefore, betaBefore, alphaAfter, betaAfter, this.whiteToMove));
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

            void finish(double alpha, double beta, MoveAndScore best, boolean completed) {
                this.finalAlpha = alpha;
                this.finalBeta = beta;
                this.finalBest = best;
                this.completed = completed;
                this.finishedAt = System.nanoTime();
            }

            int depth() {
                return depth;
            }

            int attempt() {
                return attempt;
            }

            boolean completed() {
                return completed;
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

                long end = finishedAt != 0L ? finishedAt : System.nanoTime();
                long elapsedMs = Duration.ofNanos(Math.max(0L, end - startedAt)).toMillis();
                sb.append("  Final window: [").append(String.format(Locale.ROOT, "%.2f", finalAlpha))
                        .append(", ").append(String.format(Locale.ROOT, "%.2f", finalBeta)).append("]")
                        .append(" after ").append(elapsedMs).append(" ms").append(System.lineSeparator());

                if (!notes.isEmpty()) {
                    for (String note : notes) {
                        sb.append("  Note: ").append(note).append(System.lineSeparator());
                    }
                }
                if (!completed) {
                    sb.append("  Note: iteration did not complete before abort").append(System.lineSeparator());
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
            private final boolean rootWhiteToMove;

            RootMoveTrace(int moveInt, int order, EvaluationResult evaluation, long nodesSpent, long nanosSpent,
                          double alphaBefore, double betaBefore, double alphaAfter, double betaAfter,
                          boolean rootWhiteToMove) {
                this.moveInt = moveInt;
                this.order = order;
                this.evaluation = evaluation;
                this.nodesSpent = nodesSpent;
                this.nanosSpent = nanosSpent;
                this.alphaBefore = alphaBefore;
                this.betaBefore = betaBefore;
                this.alphaAfter = alphaAfter;
                this.betaAfter = betaAfter;
                this.rootWhiteToMove = rootWhiteToMove;
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
                    double oriented = normalizeScoreForRoot(rootWhiteToMove, evaluation.score);
                    sb.append(String.format(Locale.ROOT, "%.2f", oriented));
                }

                sb.append(" (via ").append(evaluation.reason).append(')');
                return sb.toString();
            }
        }

        record DepthTraceSnapshot(
                int depth,
                int attempt,
                boolean whiteToMove,
                boolean completed,
                boolean betaCutoff,
                int cutoffMove,
                double initialAlpha,
                double initialBeta,
                double finalAlpha,
                double finalBeta,
                long elapsedNanos,
                List<RootMoveSnapshot> rootMoves
        ) {
        }

        record RootMoveSnapshot(
                int moveInt,
                int order,
                long nodesSpent,
                long nanosSpent,
                double alphaBefore,
                double betaBefore,
                double alphaAfter,
                double betaAfter,
                Double score,
                boolean exitEarly,
                String reason
        ) {
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
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            int searchThreads = clampAtLeast(readIntProperty("chessengine.searchThreads", 1), 1);
            int lazySmpThreads = clampAtLeast(readIntProperty("chessengine.lazySmpThreads", 1), 1);
            int rootParallelLimit = clampAtLeast(readIntProperty("chessengine.rootParallelLimit", 24), 1);
            int ttSizeMb = clampAtLeast(readIntProperty("chessengine.tt.mb", 64), 1);
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

        private static int readIntProperty(String key, int defaultValue) {
            String value = System.getProperty(key);
            if (value == null) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
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
            List<MoveEvaluation> expectedEvaluations,
            int deepestCompletedDepth,
            int targetDepth
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
            sb.append("Baseline evaluation: ").append(formatScore(baselineScore)).append(" pawns")
                    .append(System.lineSeparator());
            sb.append("Search configuration: ").append(SEARCH_ENVIRONMENT.inlineSummary())
                    .append(System.lineSeparator());
            sb.append("Depth target: ").append(targetDepth)
                    .append(", deepest completed iteration: ").append(deepestCompletedDepth)
                    .append(System.lineSeparator());

            if (chosenEvaluation != null) {
                sb.append("Chosen move: ").append(chosenEvaluation.move())
                        .append(" -> ").append(formatScore(chosenEvaluation.score())).append(" pawns");
                if (Double.isFinite(cpGain)) {
                    sb.append(" (Δ vs baseline: ").append(formatScore(cpGain)).append(")");
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
                        .append(" -> ").append(formatScore(bestEvaluation.score())).append(" pawns");
                if (Double.isFinite(deltaVsChosen)) {
                    sb.append(" (Δ vs chosen: ").append(formatScore(deltaVsChosen)).append(")");
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
                        .append(" -> ").append(formatScore(ev.score())).append(" pawns");
                if (chosenEvaluation != null) {
                    double delta = ev.score() - chosenEvaluation.score();
                    if (Math.abs(delta) < 0.5) {
                        sb.append(" (matches chosen)");
                    } else if (Double.isFinite(delta)) {
                        sb.append(" (Δ vs chosen: ").append(formatScore(delta)).append(")");
                    }
                }
                sb.append(System.lineSeparator());
            }

            if (!expectedEvaluations.isEmpty()) {
                sb.append("Expected move evaluations:").append(System.lineSeparator());
                for (MoveEvaluation ev : expectedEvaluations) {
                    sb.append("  ").append(ev.move())
                            .append(" -> ").append(formatScore(ev.score())).append(" pawns");
                    if (chosenEvaluation != null && !ev.move().equals(chosenEvaluation.move())) {
                        double delta = ev.score() - chosenEvaluation.score();
                        if (Double.isFinite(delta)) {
                            sb.append(" (Δ vs chosen: ").append(formatScore(delta)).append(")");
                        }
                    }
                    sb.append(System.lineSeparator());
                }
            }

            if (Double.isFinite(cpLoss)) {
                sb.append("Evaluation delta vs engine best: ")
                        .append(formatScore(-cpLoss)).append(" pawns")
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
            first = appendJsonInt(sb, "depthTarget", targetDepth, first);
            first = appendJsonInt(sb, "depthReached", deepestCompletedDepth, first);
            first = appendJsonString(sb, "pv", principalVariation, first);
            sb.append('}');
            return sb.toString();
        }

        boolean withinCpLossTolerance(double tolerance) {
            if (!Double.isFinite(cpLoss)) {
                return false;
            }
            return Math.abs(cpLoss) <= tolerance;
        }

        String chosenMove() {
            return chosenEvaluation != null ? chosenEvaluation.move() : null;
        }

        boolean bestMoveMatchesExpected(List<String> expectedMoves) {
            if (bestEvaluation == null || expectedMoves == null || expectedMoves.isEmpty()) {
                return false;
            }
            return expectedMoves.contains(bestEvaluation.move());
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
            double avgDurationMs,
            double avgDepthReached,
            double avgTargetDepth,
            int minTargetDepth,
            int maxTargetDepth
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
            long totalDepth = 0L;
            long totalTargetDepth = 0L;
            int minTargetDepth = Integer.MAX_VALUE;
            int maxTargetDepth = Integer.MIN_VALUE;

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
                totalDepth += stat.deepestCompletedDepth();
                totalTargetDepth += stat.targetDepth();
                minTargetDepth = Math.min(minTargetDepth, stat.targetDepth());
                maxTargetDepth = Math.max(maxTargetDepth, stat.targetDepth());
            }

            double avgCpLoss = cpLossCount > 0 ? totalCpLoss / cpLossCount : Double.NaN;
            double maxCpLossVal = cpLossCount > 0 ? maxCpLoss : Double.NaN;
            double avgCpGain = cpGainCount > 0 ? totalCpGain / cpGainCount : Double.NaN;
            double avgRank = rankCount > 0 ? totalRank / rankCount : Double.NaN;
            double top1Rate = stats.isEmpty() ? Double.NaN : (double) top1 / stats.size();
            double avgNodes = stats.isEmpty() ? Double.NaN : (double) totalNodes / stats.size();
            double avgNullMoves = stats.isEmpty() ? Double.NaN : (double) totalNullMoves / stats.size();
            double avgDurationMs = stats.isEmpty() ? Double.NaN : (double) totalDuration / stats.size();
            double avgDepthReached = stats.isEmpty() ? Double.NaN : (double) totalDepth / stats.size();
            double avgTargetDepth = stats.isEmpty() ? Double.NaN : (double) totalTargetDepth / stats.size();
            if (stats.isEmpty()) {
                minTargetDepth = 0;
                maxTargetDepth = 0;
            }

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
                    avgDurationMs,
                    avgDepthReached,
                    avgTargetDepth,
                    minTargetDepth,
                    maxTargetDepth
            );
        }

        String toHumanReadable() {
            StringBuilder sb = new StringBuilder();
            sb.append(System.lineSeparator());
            sb.append("=== Aggregate best-move metrics ===").append(System.lineSeparator());
            sb.append(environment.describe());
            sb.append("Positions tested: ").append(positions).append(System.lineSeparator());
            if (positions == 0 || !Double.isFinite(avgTargetDepth)) {
                sb.append("Target depth: n/a").append(System.lineSeparator());
            } else if (minTargetDepth == maxTargetDepth) {
                sb.append("Target depth: ").append(minTargetDepth).append(System.lineSeparator());
            } else {
                sb.append("Target depth range: ")
                        .append(minTargetDepth)
                        .append('–')
                        .append(maxTargetDepth)
                        .append(" (avg ")
                        .append(String.format(Locale.US, "%.2f", avgTargetDepth))
                        .append(')')
                        .append(System.lineSeparator());
            }
            if (Double.isFinite(avgCpLoss)) {
                sb.append("Average evaluation loss: ").append(formatScore(avgCpLoss)).append(" pawns")
                        .append(System.lineSeparator());
            }
            if (Double.isFinite(maxCpLoss)) {
                sb.append("Worst evaluation loss: ").append(formatScore(maxCpLoss)).append(" pawns")
                        .append(System.lineSeparator());
            }
            if (Double.isFinite(avgCpGain)) {
                sb.append("Average gain vs baseline: ").append(formatScore(avgCpGain)).append(" pawns")
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
            if (Double.isFinite(avgDepthReached)) {
                sb.append("Average completed depth: ")
                        .append(String.format(Locale.US, "%.2f", avgDepthReached))
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
            first = appendJsonNumber(sb, "depthTargetAvg", finiteOrNull(avgTargetDepth), 2, first);
            first = appendJsonInt(sb, "depthTargetMin", positions > 0 ? minTargetDepth : null, first);
            first = appendJsonInt(sb, "depthTargetMax", positions > 0 ? maxTargetDepth : null, first);
            first = appendJsonNumber(sb, "avgCpLoss", finiteOrNull(avgCpLoss), 2, first);
            first = appendJsonNumber(sb, "maxCpLoss", finiteOrNull(maxCpLoss), 2, first);
            first = appendJsonNumber(sb, "avgCpGain", finiteOrNull(avgCpGain), 2, first);
            first = appendJsonNumber(sb, "avgRank", finiteOrNull(avgRank), 2, first);
            first = appendJsonNumber(sb, "top1Rate", finiteOrNull(top1Rate), 4, first);
            first = appendJsonNumber(sb, "avgNodes", finiteOrNull(avgNodes), 2, first);
            first = appendJsonNumber(sb, "avgNullMoves", finiteOrNull(avgNullMoves), 2, first);
            first = appendJsonNumber(sb, "avgDurationMs", finiteOrNull(avgDurationMs), 2, first);
            first = appendJsonNumber(sb, "avgDepthReached", finiteOrNull(avgDepthReached), 2, first);
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

    private static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }


    private String renderPrincipalVariation(boolean whiteToMove, List<MoveAndScore> pv) {
        if (pv == null || pv.isEmpty()) {
            return "<unavailable>";
        }

        List<String> segments = new ArrayList<>(pv.size());
        for (int i = 0; i < pv.size(); i++) {
            MoveAndScore moveAndScore = pv.get(i);
            String notation = Move.convertIntToMove(moveAndScore.getMove()).toString();
            if (i == 0) {
                double orientedScore = normalizeScoreForRoot(whiteToMove, moveAndScore.getScore());
                segments.add(notation + " (" + formatScore(orientedScore) + " pawns)");
            } else {
                segments.add(notation);
            }
        }
        return String.join(" -> ", segments);
    }

    private static double normalizeScoreForRoot(boolean whiteToMove, double whitePerspectiveScore) {
        return whiteToMove ? whitePerspectiveScore : -whitePerspectiveScore;
    }

    private static String formatScore(double pawns) {
        if (!Double.isFinite(pawns)) return "n/a";
        // If you keep CHECKMATE in pawns too:
        if (Math.abs(pawns) >= Score.CHECKMATE - 10) {
            double mateDistance = Score.CHECKMATE - Math.abs(pawns);
            long plies = Math.max(0, Math.round(mateDistance));
            return (pawns > 0 ? "#+" : "#-") + (plies > 0 ? plies : "");
        }
        return String.format(Locale.US, "%+.2f", pawns);
    }

    private record MoveEvaluation(String move, double score) {
    }
}
