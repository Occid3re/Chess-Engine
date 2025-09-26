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
 * -Xms8g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+AlwaysPreTouch -XX:+UseNUMA -XX:ActiveProcessorCount=24 -Dchessengine.tt.mb=256 -Dchessengine.searchThreads=16 -Dchessengine.lazySmpThreads=8 -Dchessengine.rootParallelLimit=48
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
                        List.of("Nc3", "Nd6")
                },
                new Object[]{
                        "3r2k1/pp3p2/3B1b2/3p1p1p/8/2P5/PP3PPP/3R2K1 w - - 2 27",
                        List.of("Bc5", "Bc7")
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
                        List.of("O-O", "e2", "f4")
                },
                new Object[]{
                        "2r1k2r/ppPb1ppp/4nN2/8/2P1P3/2PB4/5PPP/4K2R b K - 4 24",
                        List.of("gxf6")
                },
                new Object[]{
                        "2r1k2r/ppPb1p1p/4np2/8/2P1P3/2PB4/5PPP/4K2R w K - 0 25",
                        List.of("Kd2", "O-O")
                },
                new Object[]{
                        "r1bqk2r/ppp2ppp/2p2n2/2b3B1/4P3/3P4/PPP2PPP/RN1QKB1R b KQkq - 2 6",
                        List.of("Nxe4")
                },
                new Object[]{
                        "8/1p6/2k2P1p/P2p4/3p4/2p1n3/2P3P1/6K1 b - - 0 38",
                        List.of("Kd6", "Kd7", "d3")
                },
                new Object[]{
                        "1k1r2r1/ppp2ppp/5n2/P1b1p3/R6P/1P2p1P1/2PBNq2/Q2K3R b - - 1 20",
                        List.of("Rxd2")
                },
                new Object[]{
                        "1k1r4/ppp2p1p/6p1/P1Pq4/6QP/4p1P1/2PpN3/3K2R1 b - - 0 30",
                        List.of("Qa2")
                },
                new Object[]{
                        "r3kb1r/2p1pppp/2nq4/p2p1b2/B2P4/2P1BN2/2P2PPP/R2Q1RK1 b kq - 0 12",
                        List.of("Bd7")
                },
                new Object[]{
                        "r3kb1r/2p1pppp/2n4B/p2p1b2/B2P4/2P2N2/2P2PPP/R2Q1RK1 b kq - 0 13",
                        List.of("Bd7")
                },
                new Object[]{
                        "2r3k1/pQ1R1ppp/4p3/8/8/2P5/P4PPP/4R1K1 b - - 2 25",
                        List.of("Rf8")
                },
                new Object[]{
                        "6r1/1pk2p1p/p3p3/b3P1p1/P1p5/1q6/8/K1Br4 b - - 13 38",
                        List.of("Rxc1", "Bc3")
                },
                new Object[]{
                        "1r4k1/5p2/3p2p1/P2q4/6Q1/2R1PR1P/2P3KP/1r6 w - - 2 36",
                        List.of("Rc8")
                },
                new Object[]{
                        "r2qkb1r/1b1n1pp1/p2p1n2/1pp3N1/3BP2p/2NB4/PPP1QPPP/R4RK1 w kq - 0 14",
                        List.of("e5")
                },
                new Object[]{
                        "r2q1rk1/ppp1bppp/4b3/4p3/1nP1N3/P2P2P1/4PPBP/1RBQ1RK1 b - - 0 14",
                        List.of("Nc6")
                },
                new Object[]{
                        "1r4k1/p5pp/2n4q/5Q2/P6P/2B2P2/1PP1R1K1/r7 b - - 0 32",
                        List.of("Rd1", "Qg6")
                },
                new Object[]{
                        "rn2kb1r/pp2pppp/5q2/1p6/2b3Q1/4B2P/PP3PP1/RN2K1NR b KQkq - 1 12",
                        List.of("Nc6", "e6")
                },
                new Object[]{
                        "r1b3kr/2b2p2/2p1q2p/pp4pN/6P1/2Q1pP1P/4B1K1/3R4 b - - 5 34",
                        List.of("Qe5", "f6", "Rh7")
                },
                new Object[]{
                        "r2q1knr/2p2ppp/2B1p3/3p4/3P3P/b3PPB1/1PP2PK1/R2Q1R2 w - - 1 17",
                        List.of("Bxa8")
                },
                new Object[]{
                        "r4rk1/ppp2ppp/2nbpq2/1B6/3P4/2P1P3/PB1NQPbP/R3K1R1 b Q - 1 13",
                        List.of("Bh3")
                },
                new Object[]{
                        "r4rk1/ppp2ppp/2nbp3/1B6/3P3q/2P1P3/PB1NQPRP/2KR4 b - - 2 15",
                        List.of("Ne7", "g6", "a6","Qd8")
                },
                new Object[]{
                        "r1b2rk1/ppqp2p1/1p2p2p/4nnNQ/8/P2B4/1PP2PPP/R1B1R1K1 w - - 0 17",
                        List.of("Bf4", "Ne4")
                },
                new Object[]{
                        "2kr3r/2p1qp1p/2p1pnpP/pN1p4/3P4/7P/PPPQPP2/R3KB1R w KQ - 0 14",
                        List.of("Nc3")
                },
                new Object[]{
                        "r2q2kr/pppb1ppp/4p3/1P1pNn2/4n3/2PBP2P/P2P1PP1/RN1QK2R b KQ - 0 11",
                        List.of("Be8")
                }
        );
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
