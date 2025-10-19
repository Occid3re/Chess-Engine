package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.Move;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import testsupport.TestReportWriter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Generates a static evaluation report for the {@link BestMoveFixtures} without
 * running the full search loop. The existing {@link BestMoveSearchTest} suite
 * fails today, which makes it harder to reason about the size of the gap between
 * the expected best move and what the evaluation function prefers. This
 * diagnostic keeps the feedback loop short: it iterates over every fixture,
 * evaluates all legal moves from the mover's perspective and highlights the
 * largest centipawn deltas. The test always passes – it is meant purely as a
 * logging aid – but it writes structured diagnostics under
 * {@code target/test-diagnostics} and prints a concise summary to the console so
 * that future agents can focus on the worst offenders first.
 */
class BestMoveEvaluationDiagnosticsTest {

    @Test
    void shouldReportEvaluationGapsForExpectedMoves() {
        List<BestMoveFixtures.BestMoveTestCase> fixtures = BestMoveFixtures.cases();
        List<EvaluationGap> gaps = fixtures.stream()
                .map(this::evaluateFixture)
                .collect(Collectors.toCollection(ArrayList::new));

        gaps.sort(Comparator.comparingDouble(EvaluationGap::cpLoss).reversed());

        String summary = buildSummary(gaps, fixtures.size());
        System.out.println(summary);

        if (!gaps.isEmpty()) {
            List<String> jsonLines = gaps.stream()
                    .map(EvaluationGap::toJsonLine)
                    .toList();
            List<String> humanReadable = gaps.stream()
                    .map(EvaluationGap::toHumanReadable)
                    .toList();

            TestReportWriter.writeLines("best-move-expected-eval-gaps.jsonl", jsonLines);
            TestReportWriter.writeLines("best-move-expected-eval-gaps.txt", humanReadable);
        }
        TestReportWriter.writeLines("best-move-expected-eval-gaps-summary.txt", List.of(summary));

        Assertions.assertEquals(fixtures.size(), gaps.size(),
                "Every fixture should produce an evaluation diagnostic entry");
    }

    private EvaluationGap evaluateFixture(BestMoveFixtures.BestMoveTestCase testCase) {
        Engine engine = new Engine();
        engine.importBoardFromFen(testCase.fen());

        boolean whiteToMove = testCase.fen().split(" ")[1].equals("w");
        IntArrayList legalMoves = engine.getAllLegalMoves();
        List<MoveEvaluation> evaluations = new ArrayList<>(legalMoves.size());
        for (int i = 0; i < legalMoves.size(); i++) {
            int moveInt = legalMoves.getInt(i);
            engine.performMove(moveInt);
            double scoreDiff = engine.getGameState().getScore().getScoreDifference();
            double orientedScore = orientScoreForMover(whiteToMove, scoreDiff);
            engine.undoLastMove();
            evaluations.add(new MoveEvaluation(Move.convertIntToMove(moveInt).toString(), orientedScore));
        }

        Comparator<MoveEvaluation> comparator = Comparator.comparingDouble(MoveEvaluation::score).reversed();
        evaluations.sort(comparator);

        MoveEvaluation bestMove = evaluations.isEmpty() ? null : evaluations.getFirst();
        Map<String, MoveEvaluation> evaluationMap = evaluations.stream()
                .collect(Collectors.toMap(MoveEvaluation::move, ev -> ev, (left, right) -> left));

        List<MoveEvaluation> expectedEvaluations = testCase.expectedMoves().stream()
                .map(evaluationMap::get)
                .filter(Objects::nonNull)
                .sorted(comparator)
                .toList();
        MoveEvaluation bestExpected = expectedEvaluations.isEmpty() ? null : expectedEvaluations.getFirst();

        double cpLoss = (bestMove != null && bestExpected != null) ? bestMove.score() - bestExpected.score() : Double.NaN;
        List<MoveEvaluation> topCandidates = evaluations.subList(0, Math.min(5, evaluations.size()));

        return new EvaluationGap(
                testCase.fen(),
                whiteToMove,
                testCase.expectedMoves(),
                bestMove,
                bestExpected,
                cpLoss,
                topCandidates,
                expectedEvaluations
        );
    }

    private static String buildSummary(List<EvaluationGap> gaps, int totalFixtures) {
        int finiteCount = (int) gaps.stream().filter(gap -> Double.isFinite(gap.cpLoss())).count();
        double averageCpLoss = gaps.stream()
                .filter(gap -> Double.isFinite(gap.cpLoss()))
                .mapToDouble(EvaluationGap::cpLoss)
                .average()
                .orElse(Double.NaN);

        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append("=== Expected-move evaluation gaps (static analysis) ===").append(System.lineSeparator());
        sb.append("Fixtures analysed: ").append(totalFixtures).append(System.lineSeparator());
        sb.append("Entries with finite cp-loss: ").append(finiteCount).append(System.lineSeparator());
        if (Double.isFinite(averageCpLoss)) {
            sb.append("Average gap between evaluation best and expected best: ")
                    .append(formatCentipawns(averageCpLoss)).append(" pawns")
                    .append(System.lineSeparator());
        }

        List<EvaluationGap> topFive = gaps.stream()
                .filter(gap -> Double.isFinite(gap.cpLoss()))
                .limit(5)
                .toList();
        if (!topFive.isEmpty()) {
            sb.append("Worst offenders (by static cp-loss):").append(System.lineSeparator());
            for (int i = 0; i < topFive.size(); i++) {
                EvaluationGap gap = topFive.get(i);
                sb.append("  ").append(i + 1).append('.').append(' ')
                        .append(formatCentipawns(gap.cpLoss())).append(" pawns: expected ")
                        .append(gap.expectedMoves())
                        .append(" vs best ")
                        .append(gap.bestMove() != null ? gap.bestMove().move() : "<none>")
                        .append(" (FEN: ")
                        .append(gap.fen())
                        .append(')')
                        .append(System.lineSeparator());
            }
        }
        sb.append("======================================================").append(System.lineSeparator());
        return sb.toString();
    }

    private static double orientScoreForMover(boolean whiteToMove, double scoreDifference) {
        return whiteToMove ? scoreDifference : -scoreDifference;
    }

    private static String formatCentipawns(double pawns) {
        if (!Double.isFinite(pawns)) {
            return "n/a";
        }
        double cp = pawns * 100.0;
        if (Math.abs(cp) >= Score.CHECKMATE - 1000) {
            double mateDistance = Score.CHECKMATE - Math.abs(cp);
            long plies = Math.max(0, Math.round(mateDistance));
            return (pawns > 0 ? "#+" : "#-") + (plies > 0 ? plies : "");
        }
        return String.format(Locale.US, "%+.2f", pawns);
    }

    private record MoveEvaluation(String move, double score) {
    }

    private record EvaluationGap(
            String fen,
            boolean whiteToMove,
            List<String> expectedMoves,
            MoveEvaluation bestMove,
            MoveEvaluation bestExpectedMove,
            double cpLoss,
            List<MoveEvaluation> topCandidates,
            List<MoveEvaluation> expectedEvaluations
    ) {

        EvaluationGap {
            expectedMoves = List.copyOf(expectedMoves);
            topCandidates = List.copyOf(topCandidates);
            expectedEvaluations = List.copyOf(expectedEvaluations);
        }

        String toHumanReadable() {
            StringBuilder sb = new StringBuilder();
            sb.append(System.lineSeparator());
            sb.append("=== Static evaluation diagnostic ===").append(System.lineSeparator());
            sb.append("FEN: ").append(fen).append(System.lineSeparator());
            sb.append("Side to move: ").append(whiteToMove ? "White" : "Black").append(System.lineSeparator());
            sb.append("Expected moves: ").append(expectedMoves).append(System.lineSeparator());
            if (bestMove != null) {
                sb.append("Evaluation best: ").append(bestMove.move())
                        .append(" -> ").append(formatCentipawns(bestMove.score())).append(" pawns")
                        .append(System.lineSeparator());
            }
            if (bestExpectedMove != null) {
                sb.append("Best expected evaluation: ").append(bestExpectedMove.move())
                        .append(" -> ").append(formatCentipawns(bestExpectedMove.score())).append(" pawns")
                        .append(System.lineSeparator());
            } else {
                sb.append("Best expected evaluation: <unavailable>").append(System.lineSeparator());
            }
            if (Double.isFinite(cpLoss)) {
                sb.append("Static cp-loss vs expectation: ")
                        .append(formatCentipawns(cpLoss)).append(" pawns")
                        .append(System.lineSeparator());
            }
            sb.append("Top candidates: ").append(System.lineSeparator());
            for (int i = 0; i < topCandidates.size(); i++) {
                MoveEvaluation candidate = topCandidates.get(i);
                sb.append("  ").append(i + 1).append('.').append(' ')
                        .append(candidate.move())
                        .append(" -> ").append(formatCentipawns(candidate.score())).append(" pawns")
                        .append(System.lineSeparator());
            }
            if (!expectedEvaluations.isEmpty()) {
                sb.append("Expected move evaluations:").append(System.lineSeparator());
                for (MoveEvaluation evaluation : expectedEvaluations) {
                    sb.append("  ").append(evaluation.move())
                            .append(" -> ").append(formatCentipawns(evaluation.score())).append(" pawns")
                            .append(System.lineSeparator());
                }
            }
            sb.append("=====================================").append(System.lineSeparator());
            return sb.toString();
        }

        String toJsonLine() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"fen\":\"").append(jsonEscape(fen)).append("\",");
            sb.append("\"side\":\"").append(whiteToMove ? 'w' : 'b').append("\",");
            sb.append("\"expected\":[");
            for (int i = 0; i < expectedMoves.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("\"").append(jsonEscape(expectedMoves.get(i))).append("\"");
            }
            sb.append(']');
            sb.append(',');
            sb.append("\"best\":").append(bestMove != null ? "\"" + jsonEscape(bestMove.move()) + "\"" : "null");
            sb.append(',');
            sb.append("\"bestScore\":").append(formatJsonNumber(bestMove != null ? bestMove.score() : null, 2));
            sb.append(',');
            sb.append("\"expectedBest\":").append(bestExpectedMove != null ? "\"" + jsonEscape(bestExpectedMove.move()) + "\"" : "null");
            sb.append(',');
            sb.append("\"expectedScore\":").append(formatJsonNumber(bestExpectedMove != null ? bestExpectedMove.score() : null, 2));
            sb.append(',');
            sb.append("\"cpLoss\":").append(formatJsonNumber(cpLoss, 2));
            sb.append(',');
            sb.append("\"topCandidates\":[");
            for (int i = 0; i < topCandidates.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                MoveEvaluation candidate = topCandidates.get(i);
                sb.append('{')
                        .append("\"move\":\"").append(jsonEscape(candidate.move())).append("\",\"score\":")
                        .append(formatJsonNumber(candidate.score(), 2))
                        .append('}');
            }
            sb.append(']');
            sb.append('}');
            return sb.toString();
        }
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
        if (value == null || !Double.isFinite(value)) {
            return "null";
        }
        String format = "%." + decimals + "f";
        return String.format(Locale.US, format, value);
    }
}

