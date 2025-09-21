package julius.game.chessengine.ai;

import julius.game.chessengine.board.Move;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Verifies that the AI selects the expected best move from a set of FEN
 * positions within a small time budget. Similar in spirit to
 * {@link MateSearchTest} but checks the single move chosen by the engine
 * instead of the game result.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BestMoveSearchTest {

    /**
     * Test matrix: (fen, expected moves in algebraic notation). Some positions
     * have multiple acceptable best moves, so we keep a list.
     */
    private Stream<Object[]> fenMatrix() {
        return Stream.of(
                new Object[]{
                        "r1b1kbnr/ppp1p1pp/3q4/2N2p2/1n1pP3/5N2/P1PP1PPP/R1BQKB1R w KQkq - 0 7",
                        List.of("c3")
                },
                new Object[]{
                        "4k3/1bp1bp1p/p3p3/1r1qN3/3P1p1r/2B5/PPP2PP1/R3RQK1 w - - 0 19",
                        List.of("a3", "f3", "a4")
                },
                new Object[]{
                        "r1bqkb1r/pppppppp/2n2n2/1P6/8/8/PBPPPPPP/RN1QKBNR b KQkq - 0 3",
                        List.of("Na5")
                },
                new Object[]{
                        "r1b2rk1/ppp2p2/2n2n2/P6p/2P1P1p1/4P1K1/1B1N4/R5NR b - - 1 20",
                        List.of("Ne8", "Nh7")
                },
                new Object[]{
                        "rnbqk1nr/p1pp3p/1p3p2/4p3/1b1PN3/2N5/PPP1PPPP/R1BQKB1R w KQkq - 0 6",
                        List.of("dxe5", "a3")
                },
                new Object[]{
                        "r1b2k1r/1p2n2p/3p1q1n/p1p3N1/1BQ2P1P/3pP3/PPP3P1/1K1R3R w - - 0 21",
                        List.of("Bc3")
                },
                new Object[]{
                        "rnbqk2r/pppp1ppp/4p3/1Pb5/3Pn3/2P5/PB2PPPP/RN1QKBNR b KQkq - 0 5",
                        List.of("Be7", "Bd6", "Qf6")
                },
                new Object[]{
                        "3r2k1/pppq1ppp/2n2n2/1N2P3/5Qb1/5N2/PPP1PPPP/2K2B1R w - - 5 15",
                        List.of("Nc3")
                },
                new Object[]{
                        "3r2k1/pp3p2/3B1b2/3p1p1p/8/2P5/PP3PPP/3R2K1 w - - 2 27",
                        List.of("Bc5")
                },
                new Object[]{
                        "r5k1/pb1p2pp/2pP1r2/4Qp2/2p5/q1B2P2/P1P2PPP/1R1R2K1 b - - 7 21",
                        List.of("Ba6", "Rb8")
                },
                new Object[]{
                        "rnb1k2r/2bp3p/3qpp1n/P5p1/Q1P3P1/PN6/4BPNP/R4RK1 w - - 1 24",
                        List.of("f4")
                },
                new Object[]{
                        "rnb1k2r/2bp3p/3qpp1n/P5p1/Q1P3P1/PN5P/4BPN1/R4RK1 b - - 0 24",
                        List.of("Qh2")
                },
                new Object[]{
                        "2k5/p1p4p/2p1p3/8/8/8/P1Pr1PPP/1R4K1 w - - 3 23",
                        List.of("Rc1")
                },
                new Object[]{
                        "r1bqk2r/ppp2ppp/2n1p3/4P3/3Pp3/5N2/P1P2PPP/R1B1QRK1 w kq - 0 11",
                        List.of("Qxe4")
                },
                new Object[]{
                        "r1bqkb1r/pppppppp/2n2n2/8/2PP4/2N5/PP2PPPP/R1BQKBNR b KQkq - 2 3",
                        List.of("e5", "d5")
                },
                new Object[]{
                "3rk2r/1bqpbppp/p1n1p3/1p2P3/5Bn1/2NQ1N2/PPP1BPPP/R2R2K1 w k - 5 14",
                        List.of("Ne4")
                },
                new Object[]{
                "2r1k2r/ppPb1ppp/4n3/3N4/2P1P3/2PB4/5PPP/4K2R w K - 3 24",
                        List.of("0-0", "e2", "f4")
                },
                new Object[]{
                        "2r1k2r/ppPb1ppp/4nN2/8/2P1P3/2PB4/5PPP/4K2R b K - 4 24",
                        List.of("gxf6")
                },
                new Object[]{
                        "2r1k2r/ppPb1p1p/4np2/8/2P1P3/2PB4/5PPP/4K2R w K - 0 25",
                        List.of("Kd2", "0-0")
                }
        );
    }

    @Test
    void timeoutDoesNotFallbackToFirstLegalMove() throws Exception {
        Engine engine = new Engine();
        String fen = "rnbqkbnr/pppp1ppp/8/4p3/3PP3/8/PPP2PPP/RNBQKBNR b KQkq - 0 2";
        engine.importBoardFromFen(fen);

        MoveList legalMoves = engine.getAllLegalMoves();
        Assertions.assertTrue(legalMoves.size() > 1, "Test position must allow multiple legal moves");
        int firstLegal = legalMoves.getMove(0);
        int preservedBest = legalMoves.getMove(legalMoves.size() - 1);
        Assertions.assertNotEquals(firstLegal, preservedBest,
                "The preserved best move should differ from the first generated legal move");

        AI ai = new AI(engine);
        long hash = engine.getBoardStateHash();

        setPrivateField(ai, "currentBoardState", hash);
        setPrivateField(ai, "previousBestMove", preservedBest);
        setPrivateField(ai, "previousBestMoveHash", hash);
        setPrivateField(ai, "currentBestMove", -1);
        setPrivateField(ai, "bestMoveForHash", -1L);
        setPrivateField(ai, "searchResultReady", false);

        SearchTask task = new SearchTask(99L, hash, engine.whitesTurn(), System.nanoTime(), 1);

        ai.completeSearchTask(task, engine);

        int bestAfterTimeout = ai.getCurrentBestMoveInt();
        Assertions.assertEquals(preservedBest, bestAfterTimeout,
                "Search completion without a new result must reuse the preserved best move");
        Assertions.assertNotEquals(firstLegal, bestAfterTimeout,
                "Engine must not fall back to the first legal move after a timeout");

        List<MoveAndScore> pv = ai.getCalculatedLine();
        Assertions.assertFalse(pv.isEmpty(), "Principal variation should reflect the preserved best move");
        Assertions.assertEquals(preservedBest, pv.get(0).getMove(),
                "PV root move should match the preserved best move");
    }

    @ParameterizedTest(name = "Best move {1} for FEN {0}")
    @MethodSource("fenMatrix")
    void testBestMove(String fen, List<String> expectedMoves) throws InterruptedException {
        Engine engine = new Engine();
        engine.importBoardFromFen(fen);


        AI ai = new AI(engine);
        ai.setTimeLimit(1000L); // milliseconds

        boolean whiteToMove = fen.split(" ")[1].equals("w");
        ai.startAutoPlay(whiteToMove, !whiteToMove);

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(2);
        int lastMove = -1;
        while (System.currentTimeMillis() < deadline) {
            lastMove = engine.getLastMove();
            if (lastMove != -1) break;
            TimeUnit.MILLISECONDS.sleep(50);
        }

        ai.stopCalculation();

        Assertions.assertNotEquals(-1, lastMove, "Engine failed to make a move for FEN: " + fen);
        String moveString = Move.convertIntToMove(lastMove).toString();
        String statistics = compileMoveStatistics(fen, moveString, ai, expectedMoves);
        System.out.println(statistics);
        Assertions.assertTrue(expectedMoves.contains(moveString),
                "Expected one of " + expectedMoves + " but got " + moveString + " for FEN: " + fen
                        + statistics);
    }

    private String compileMoveStatistics(String fen, String chosenMove, AI ai, List<String> expectedMoves) {
        Engine analysisEngine = new Engine();
        analysisEngine.importBoardFromFen(fen);

        boolean whiteToMove = fen.split(" ")[1].equals("w");
        double baselineForMover = orientScoreForMover(whiteToMove,
                analysisEngine.getGameState().getScore().getScoreDifference());

        MoveList legalMovesSnapshot = analysisEngine.getAllLegalMoves();
        List<Integer> legalMoves = new ArrayList<>(legalMovesSnapshot.size());
        for (int i = 0; i < legalMovesSnapshot.size(); i++) {
            legalMoves.add(legalMovesSnapshot.getMove(i));
        }

        List<MoveEvaluation> evaluations = new ArrayList<>(legalMoves.size());
        for (int moveInt : legalMoves) {
            analysisEngine.performMove(moveInt);
            double scoreDiff = analysisEngine.getGameState().getScore().getScoreDifference();
            double moverScore = orientScoreForMover(whiteToMove, scoreDiff);
            analysisEngine.undoLastMove();
            evaluations.add(new MoveEvaluation(Move.convertIntToMove(moveInt).toString(), moverScore));
        }

        Comparator<MoveEvaluation> comparator = whiteToMove
                ? Comparator.comparingDouble(MoveEvaluation::score).reversed()
                : Comparator.comparingDouble(MoveEvaluation::score);
        evaluations.sort(comparator);

        MoveEvaluation chosenEval = evaluations.stream()
                .filter(ev -> ev.move().equals(chosenMove))
                .findFirst()
                .orElse(null);

        String pvString = renderPrincipalVariation(whiteToMove, ai.getCalculatedLine());

        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append("=== Engine decision statistics ===").append(System.lineSeparator());
        sb.append("FEN: ").append(fen).append(System.lineSeparator());
        sb.append("Side to move: ").append(whiteToMove ? "White" : "Black").append(System.lineSeparator());
        sb.append("Expected best moves: ").append(expectedMoves).append(System.lineSeparator());
        sb.append("Baseline evaluation: ").append(formatCentipawns(baselineForMover)).append(" pawns")
                .append(System.lineSeparator());

        if (chosenEval != null) {
            double delta = chosenEval.score() - baselineForMover;
            sb.append("Chosen move: ").append(chosenMove)
                    .append(" -> ").append(formatCentipawns(chosenEval.score())).append(" pawns")
                    .append(" (Δ vs baseline: ").append(formatCentipawns(delta)).append(")")
                    .append(System.lineSeparator());
        } else {
            sb.append("Chosen move: ").append(chosenMove)
                    .append(" (not present in legal move snapshot)")
                    .append(System.lineSeparator());
        }

        sb.append("Top candidates by evaluation:").append(System.lineSeparator());
        for (int i = 0; i < Math.min(5, evaluations.size()); i++) {
            MoveEvaluation ev = evaluations.get(i);
            sb.append("  ").append(i + 1).append(". ").append(ev.move())
                    .append(" -> ").append(formatCentipawns(ev.score())).append(" pawns");
            if (chosenEval != null) {
                double delta = ev.score() - chosenEval.score();
                if (Math.abs(delta) < 0.5) {
                    sb.append(" (matches chosen)");
                } else {
                    sb.append(" (Δ vs chosen: ").append(formatCentipawns(delta)).append(")");
                }
            }
            sb.append(System.lineSeparator());
        }

        sb.append("Principal variation: ").append(pvString).append(System.lineSeparator());
        sb.append("==================================").append(System.lineSeparator());

        return sb.toString();
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

    private static void setPrivateField(Object target, String name, Object value) throws Exception {
        Field field = AI.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record MoveEvaluation(String move, double score) {
    }
}
