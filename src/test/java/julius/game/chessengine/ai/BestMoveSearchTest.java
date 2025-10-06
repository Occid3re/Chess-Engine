package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.Move;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    private final List<DecisionStatistics> decisionSummaries = new ArrayList<>();
    private final List<String> decisionJsonLines = new ArrayList<>();
    private final List<String> decisionTextBlocks = new ArrayList<>();

    /**
     * Test matrix: (fen, expected moves in algebraic notation). Some positions
     * have multiple acceptable best moves, so we keep a list.
     */
    @ParameterizedTest(name = "Best move {1} for FEN {0}")
    @MethodSource("julius.game.chessengine.ai.BestMoveFixtures#arguments")
    void testBestMove(String fen, List<String> expectedMoves) throws InterruptedException {
        Engine engine = new Engine();
        engine.importBoardFromFen(fen);

        final long timeLimitMs = 2000L;


        AI ai = new AI(engine);
        ai.setTimeLimit(timeLimitMs); // milliseconds

        boolean whiteToMove = fen.split(" ")[1].equals("w");
        long nodesBefore = ai.getNodesVisited();
        long nullMovesBefore = ai.getNullMoveCount();
        long startNanos = System.nanoTime();

        ai.startAutoPlay(whiteToMove, !whiteToMove);

        long deadline = System.currentTimeMillis() + TimeUnit.MILLISECONDS.toMillis(timeLimitMs + 200);
        int lastMove = -1;
        while (System.currentTimeMillis() < deadline) {
            lastMove = engine.getLastMove();
            if (lastMove != -1) break;
            TimeUnit.MILLISECONDS.sleep(50);
        }

        ai.stopCalculation();

        long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        long nodesVisited = Math.max(0, ai.getNodesVisited() - nodesBefore);
        long nullMoves = Math.max(0, ai.getNullMoveCount() - nullMovesBefore);

        Assertions.assertNotEquals(-1, lastMove, "Engine failed to make a move for FEN: " + fen);
        String moveString = Move.convertIntToMove(lastMove).toString();
        DecisionStatistics statistics = compileDecisionStatistics(
                fen,
                moveString,
                ai,
                expectedMoves,
                durationMillis,
                nodesVisited,
                nullMoves
        );
        decisionSummaries.add(statistics);

        String humanReadable = statistics.toHumanReadable();
        System.out.println(humanReadable);
        System.out.println(statistics.toJsonLine());

        decisionJsonLines.add(statistics.toJsonLine());
        decisionTextBlocks.add(humanReadable);

        Assertions.assertTrue(expectedMoves.contains(moveString),
                "Expected one of " + expectedMoves + " but got " + moveString + " for FEN: " + fen
                        + humanReadable);
    }

    private DecisionStatistics compileDecisionStatistics(String fen,
                                                         String chosenMove,
                                                         AI ai,
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

        String pvString = renderPrincipalVariation(whiteToMove, ai.getCalculatedLine());

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
        AggregateStatistics summary = AggregateStatistics.from(decisionSummaries);
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

        static AggregateStatistics from(List<DecisionStatistics> stats) {
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
