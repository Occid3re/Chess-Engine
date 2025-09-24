package julius.game.chessengine.ai;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.Move;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.evaluation.ActivityModule;
import julius.game.chessengine.evaluation.BatteryModule;
import julius.game.chessengine.evaluation.EvaluationContext;
import julius.game.chessengine.evaluation.EvaluationModule;
import julius.game.chessengine.evaluation.EvaluationPipeline;
import julius.game.chessengine.evaluation.KingSafetyModule;
import julius.game.chessengine.evaluation.MaterialModule;
import julius.game.chessengine.evaluation.PawnStructureModule;
import julius.game.chessengine.evaluation.PieceSquareModule;
import julius.game.chessengine.evaluation.ThreatModule;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final int BLEND_SCALE = 256;

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
        Assertions.assertEquals(preservedBest, pv.getFirst().getMove(),
                "PV root move should match the preserved best move");
    }

    @ParameterizedTest(name = "Best move {1} for FEN {0}")
    @MethodSource("fenMatrix")
    void testBestMove(String fen, List<String> expectedMoves) throws InterruptedException {
        Engine engine = new Engine();
        engine.importBoardFromFen(fen);


        AI ai = new AI(engine);
        ai.setTimeLimit(1000L); // milliseconds
        long startNodes = ai.getNodesVisited();
        long startNullMoves = ai.getNullMoveCount();
        long searchStartNanos = System.nanoTime();

        boolean whiteToMove = fen.split(" ")[1].equals("w");
        ai.startAutoPlay(whiteToMove, !whiteToMove);

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(2);
        int lastMove = -1;
        long observedElapsedNanos = 0L;
        while (System.currentTimeMillis() < deadline) {
            lastMove = engine.getLastMove();
            if (lastMove != -1) {
                observedElapsedNanos = System.nanoTime() - searchStartNanos;
                break;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }

        ai.stopCalculation();

        Assertions.assertNotEquals(-1, lastMove, "Engine failed to make a move for FEN: " + fen);
        String moveString = Move.convertIntToMove(lastMove).toString();
        long searchMillis = TimeUnit.NANOSECONDS.toMillis(observedElapsedNanos);
        long nodesVisited = Math.max(0L, ai.getNodesVisited() - startNodes);
        long nullMovesTried = Math.max(0L, ai.getNullMoveCount() - startNullMoves);
        List<MoveAndScore> principalVariation = new ArrayList<>(ai.getCalculatedLine());
        String statistics = compileMoveStatistics(
                fen,
                moveString,
                ai,
                expectedMoves,
                nodesVisited,
                nullMovesTried,
                searchMillis,
                principalVariation
        );
        System.out.println(statistics);
        Assertions.assertTrue(expectedMoves.contains(moveString),
                "Expected one of " + expectedMoves + " but got " + moveString + " for FEN: " + fen
                        + statistics);
    }

    private String compileMoveStatistics(String fen,
                                         String chosenMove,
                                         AI ai,
                                         List<String> expectedMoves,
                                         long nodesVisited,
                                         long nullMovesTried,
                                         long searchMillis,
                                         List<MoveAndScore> principalVariation) {
        Engine analysisEngine = new Engine();
        analysisEngine.importBoardFromFen(fen);

        boolean whiteToMove = fen.split(" ")[1].equals("w");
        double baselineForMoverCentipawns = orientScoreForMover(whiteToMove,
                analysisEngine.getGameState().getScore().getScoreDifference()) * 100.0;

        MoveList legalMovesSnapshot = analysisEngine.getAllLegalMoves();
        List<Integer> legalMoves = new ArrayList<>(legalMovesSnapshot.size());
        for (int i = 0; i < legalMovesSnapshot.size(); i++) {
            legalMoves.add(legalMovesSnapshot.getMove(i));
        }

        // Static one-ply eval of all legal moves (oriented for mover)
        List<MoveEvaluation> evaluations = new ArrayList<>(legalMoves.size());
        for (int moveInt : legalMoves) {
            analysisEngine.performMove(moveInt);
            double scoreDiff = analysisEngine.getGameState().getScore().getScoreDifference();
            double moverScoreCentipawns = orientScoreForMover(whiteToMove, scoreDiff) * 100.0;
            analysisEngine.undoLastMove();
            evaluations.add(new MoveEvaluation(Move.convertIntToMove(moveInt).toString(), moverScoreCentipawns, moveInt));
        }

        Comparator<MoveEvaluation> comparator = whiteToMove
                ? Comparator.comparingDouble(MoveEvaluation::centipawns).reversed()
                : Comparator.comparingDouble(MoveEvaluation::centipawns);
        evaluations.sort(comparator);

        MoveEvaluation chosenEval = evaluations.stream()
                .filter(ev -> ev.move().equals(chosenMove))
                .findFirst()
                .orElse(null);

        String pvString = renderPrincipalVariation(whiteToMove, principalVariation);
        int pvLength = principalVariation != null ? principalVariation.size() : 0;
        int legalMoveCount = evaluations.size();
        int chosenRank = chosenEval != null ? evaluations.indexOf(chosenEval) + 1 : -1;
        MoveEvaluation topCandidate = evaluations.isEmpty() ? null : evaluations.getFirst();
        double deltaVsBest = chosenEval != null && topCandidate != null
                ? chosenEval.centipawns() - topCandidate.centipawns()
                : Double.NaN;
        double spread = evaluations.isEmpty()
                ? Double.NaN
                : evaluations.getFirst().centipawns() - evaluations.getLast().centipawns();

        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append("=== Engine decision statistics ===").append(System.lineSeparator());
        sb.append("FEN: ").append(fen).append(System.lineSeparator());
        sb.append("Side to move: ").append(whiteToMove ? "White" : "Black").append(System.lineSeparator());
        sb.append("Expected best moves: ").append(expectedMoves).append(System.lineSeparator());
        sb.append("Baseline evaluation: ").append(formatCentipawns(baselineForMoverCentipawns)).append(" pawns")
                .append(System.lineSeparator());
        sb.append("Search threads: ").append(ai.getSearchThreads())
                .append(", Lazy SMP workers: ").append(ai.getLazySmpThreads()).append(System.lineSeparator());
        sb.append("Time limit: ").append(ai.getTimeLimit()).append(" ms, elapsed: ")
                .append(searchMillis).append(" ms").append(System.lineSeparator());
        sb.append("Nodes visited (Δ): ").append(nodesVisited);
        if (searchMillis > 0) {
            double kNps = (nodesVisited / (double) searchMillis);
            sb.append(" (~").append(String.format(Locale.US, "%.1f", kNps)).append(" kN/s)");
        }
        sb.append(System.lineSeparator());
        sb.append("Null moves tried (Δ): ").append(nullMovesTried).append(System.lineSeparator());

        if (chosenEval != null) {
            double delta = chosenEval.centipawns() - baselineForMoverCentipawns;
            sb.append("Chosen move: ").append(chosenMove)
                    .append(" -> ").append(formatCentipawns(chosenEval.centipawns())).append(" pawns")
                    .append(" (Δ vs baseline: ").append(formatCentipawns(delta)).append(")")
                    .append(System.lineSeparator());
            if (!Double.isNaN(deltaVsBest) && Math.abs(deltaVsBest) > 0.5) {
                sb.append("Rank among legal: ").append(chosenRank).append("/").append(legalMoveCount)
                        .append(" (Δ vs top: ").append(formatCentipawns(deltaVsBest)).append(")")
                        .append(System.lineSeparator());
            } else {
                sb.append("Rank among legal: ").append(chosenRank).append("/").append(legalMoveCount)
                        .append(System.lineSeparator());
            }
        } else {
            sb.append("Chosen move: ").append(chosenMove)
                    .append(" (not present in legal move snapshot)")
                    .append(System.lineSeparator());
        }

        // ===== Module-level insight for chosen vs baseline (+ optional top alternative) =====
        topCandidate = evaluations.isEmpty() ? null : evaluations.getFirst();
        if (chosenEval != null) {
            sb.append(renderModuleInfluence(fen, whiteToMove, chosenEval, topCandidate));
        }

        // ===== NEW: Explain why the best "expected" move (if different) wasn’t chosen =====
        MoveEvaluation expectedRef = selectBestExpectedCandidate(expectedMoves, evaluations);
        if (expectedRef != null && (chosenEval == null || expectedRef.moveInt() != chosenEval.moveInt())) {
            sb.append(renderWhyNotExpected(fen, whiteToMove, chosenEval, expectedRef));
        }

        SearchDiagnostics diagnostics = ai.getLastDiagnostics();
        if (diagnostics != null && diagnostics != SearchDiagnostics.EMPTY) {
            sb.append(renderSearchDiagnostics(diagnostics));
        }

        sb.append("Total legal moves: ").append(legalMoveCount);
        if (!Double.isNaN(spread)) {
            sb.append(" (evaluation spread: ").append(formatCentipawns(spread)).append(")");
        }
        sb.append(System.lineSeparator());
        sb.append("Top candidates by evaluation:").append(System.lineSeparator());
        for (int i = 0; i < Math.min(5, evaluations.size()); i++) {
            MoveEvaluation ev = evaluations.get(i);
            sb.append("  ").append(i + 1).append(". ").append(ev.move())
                    .append(" -> ").append(formatCentipawns(ev.centipawns())).append(" pawns");
            if (chosenEval != null) {
                double d = ev.centipawns() - chosenEval.centipawns();
                if (Math.abs(d) < 50.0) sb.append(" (matches chosen)");
                else sb.append(" (Δ vs chosen: ").append(formatCentipawns(d)).append(")");
            }
            sb.append(System.lineSeparator());
        }

        sb.append("Principal variation: ").append(pvString)
                .append(" (length ").append(pvLength).append(")")
                .append(System.lineSeparator());
        sb.append("==================================").append(System.lineSeparator());

        return sb.toString();
    }

    private String renderSearchDiagnostics(SearchDiagnostics diagnostics) {
        String ls = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("Search heuristics summary:").append(ls);

        String bestScore = Double.isNaN(diagnostics.bestScore())
                ? "n/a"
                : formatCentipawns(diagnostics.bestScore() * 100.0) + " pawns";

        sb.append(String.format(Locale.US,
                "  Depth reached: %d plies (selective: %d, qsearch max: %d)%n",
                diagnostics.bestDepth(), diagnostics.deepestPlyVisited(), diagnostics.deepestQuiescencePly()));
        sb.append(String.format(Locale.US,
                "  Best score: %s, iterations: %d%n",
                bestScore,
                diagnostics.iterationsCompleted()));
        sb.append(String.format(Locale.US,
                "  Root moves: %d generated, %d explored, root β-cutoffs: %d%n",
                diagnostics.rootMovesGenerated(), diagnostics.rootMovesExplored(), diagnostics.rootBetaCutoffs()));
        sb.append(String.format(Locale.US,
                "  Aspiration window restarts: fail-low %d, fail-high %d, full resets %d%n",
                diagnostics.aspirationFailLows(), diagnostics.aspirationFailHighs(), diagnostics.aspirationResets()));
        sb.append(String.format(Locale.US,
                "  TT lookups: %d (hits %d, exact %d, cutoffs %d)%n",
                diagnostics.transpositionLookups(), diagnostics.transpositionHits(), diagnostics.transpositionExactHits(),
                diagnostics.transpositionCutoffs()));
        sb.append(String.format(Locale.US,
                "  Null-move pruning: tries %d, prunes %d, verifications %d (fails %d)%n",
                diagnostics.nullMoveTries(), diagnostics.nullMovePrunes(), diagnostics.nullMoveVerifications(),
                diagnostics.nullMoveVerificationFails()));
        sb.append(String.format(Locale.US,
                "  Late move reductions: %d applied (avg %s plies), prunes %d, futility prunes %d%n",
                diagnostics.lateMoveReductions(), diagnostics.formatAverageLmrReduction(),
                diagnostics.lateMovePrunes(), diagnostics.futilityPrunes()));
        sb.append(String.format(Locale.US,
                "  Interior β-cutoffs: %d, static eval calls: %d%n",
                diagnostics.betaCutoffs(), diagnostics.staticEvalCalls()));
        sb.append(String.format(Locale.US,
                "  Quiescence: nodes %d, stand-pat cuts %d, delta prunes %d, SEE prunes %d, captures %d%n",
                diagnostics.quiescenceNodes(), diagnostics.quiescenceStandPatCuts(), diagnostics.quiescenceDeltaPrunes(),
                diagnostics.quiescenceSeePrunes(), diagnostics.quiescenceCaptures()));
        return sb.toString();
    }

    /**
     * Choose, among expected algebraic strings, the best legal candidate by our static 1-ply evaluation.
     */
    private MoveEvaluation selectBestExpectedCandidate(List<String> expectedMoves, List<MoveEvaluation> sortedEvals) {
        if (expectedMoves == null || expectedMoves.isEmpty() || sortedEvals == null) return null;
        // The list is sorted best->worst for the mover already.
        for (MoveEvaluation ev : sortedEvals) {
            if (expectedMoves.contains(ev.move())) {
                return ev;
            }
        }
        return null; // none legal or none matched
    }

    /**
     * Render an explanation why the expected (reference) move lost the head-to-head vs the chosen move.
     * Uses module deltas and simple tactical tags derived from evaluation state only (no extra engine APIs).
     */
    private String renderWhyNotExpected(String fen,
                                        boolean whiteToMove,
                                        MoveEvaluation chosen,
                                        MoveEvaluation expectedRef) {
        String ls = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("Why not expected move ").append(expectedRef.move()).append(":").append(ls);

        // Engines for baseline, chosen, expected
        Engine base = createEngineForMove(fen, null);
        Engine engChosen = createEngineForMove(fen, chosen != null ? chosen.moveInt() : null);
        Engine engExpected = createEngineForMove(fen, expectedRef.moveInt());

        ModuleContributionSummary mBase = collectModuleContributions(base, whiteToMove);
        ModuleContributionSummary mChosen = chosen != null ? collectModuleContributions(engChosen, whiteToMove) : null;
        ModuleContributionSummary mExpected = collectModuleContributions(engExpected, whiteToMove);

        int baseTotal = mBase.totalBlended();
        int chosenTotal = mChosen != null ? mChosen.totalBlended() : baseTotal; // fallback
        int expectedTotal = mExpected.totalBlended();

        int headToHead = expectedTotal - chosenTotal;

        sb.append(String.format(Locale.US,
                "  Eval totals (blended): chosen %s vs expected %s (Δ expected - chosen: %s)%n",
                formatPawns(chosenTotal), formatPawns(expectedTotal), formatPawns(headToHead)));

        // Simple tactical tags from state changes and material swing only
        String chosenTags = computeTacticalTags(base, engChosen, whiteToMove);
        String expectedTags = computeTacticalTags(base, engExpected, whiteToMove);
        if (!chosenTags.isEmpty() || !expectedTags.isEmpty()) {
            sb.append("  Tactical tags:").append(ls);
            if (!chosenTags.isEmpty()) sb.append("    chosen:   ").append(chosenTags).append(ls);
            if (!expectedTags.isEmpty()) sb.append("    expected: ").append(expectedTags).append(ls);
        }

        // Top-3 module differences baseline->chosen and baseline->expected, then chosen vs expected
        sb.append(renderTopModuleDiffs("  Biggest improvements (baseline → chosen)", mBase, mChosen));
        sb.append(renderTopModuleDiffs("  Biggest improvements (baseline → expected)", mBase, mExpected));
        sb.append(renderHeadToHeadModuleDiffs(mChosen, mExpected));

        // Heuristic flags: if expected worsens king safety or gives back material vs chosen, call it out
        Assertions.assertNotNull(mChosen);
        int chosenKS = getModule(mChosen, "King safety");
        int expectedKS = getModule(mExpected, "King safety");
        int chosenMat = getModule(mChosen, "Material");
        int expectedMat = getModule(mExpected, "Material");

        if (expectedKS < chosenKS) {
            sb.append(String.format(Locale.US, "  Note: expected worsens king safety vs chosen by %s.%n",
                    formatPawns(expectedKS - chosenKS)));
        }
        if (expectedMat < chosenMat) {
            sb.append(String.format(Locale.US, "  Note: expected is worse on material swing vs chosen by %s.%n",
                    formatPawns(expectedMat - chosenMat)));
        }

        return sb.toString();
    }

    /** Build short tags like [check], [capture], [king-unsafe↑/↓] from evaluation-only info. */
    private String computeTacticalTags(Engine before, Engine after, boolean moverWasWhite) {
        List<String> tags = new ArrayList<>();

        // Check tag: after-move state indicates opponent in check
        GameStateEnum st = after.getGameState().getState();
        boolean opponentInCheck = (moverWasWhite && st == GameStateEnum.BLACK_IN_CHECK)
                || (!moverWasWhite && st == GameStateEnum.WHITE_IN_CHECK);
        if (opponentInCheck) tags.add("check");

        // Capture tag: infer via material blended change sign
        ModuleContributionSummary b = collectModuleContributions(before, moverWasWhite);
        ModuleContributionSummary a = collectModuleContributions(after, moverWasWhite);
        int matB = getModule(b, "Material");
        int matA = getModule(a, "Material");
        if (matA > matB) tags.add("capture");

        // King safety swing (own safety improves/worsens)
        int ksB = getModule(b, "King safety");
        int ksA = getModule(a, "King safety");
        int ksDelta = ksA - ksB;
        if (ksDelta >= 30) tags.add("king-safety↑");
        else if (ksDelta <= -30) tags.add("king-safety↓");

        return String.join(", ", tags);
    }

    /** Fetch blended value of a named module (or 0 if absent). */
    private int getModule(ModuleContributionSummary s, String name) {
        for (ModuleContribution c : s.contributions()) {
            if (c.name().equals(name)) return c.blended();
        }
        return 0;
    }

    /** Top-3 absolute module gains/losses from 'from' → 'to'. sign=+1 keeps gains first; sign=-1 would invert if needed. */
    private String renderTopModuleDiffs(String heading,
                                        ModuleContributionSummary from,
                                        ModuleContributionSummary to) {
        if (from == null || to == null) return "";
        Map<String, ModuleContribution> idxFrom = indexByModule(from.contributions());
        Map<String, ModuleContribution> idxTo = indexByModule(to.contributions());
        record D(String name, int delta) {}
        List<D> deltas = new ArrayList<>();
        for (String k : idxFrom.keySet()) {
            ModuleContribution a = idxFrom.get(k);
            ModuleContribution b = idxTo.get(k);
            if (b == null) continue;
            int d = b.blended() - a.blended();
            if (d != 0) deltas.add(new D(k, d));
        }
        if (deltas.isEmpty()) return "";
        deltas.sort(Comparator.comparingInt((D d) -> Math.abs(d.delta())).reversed());
        String ls = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append(heading).append(":").append(ls);
        for (int i = 0; i < Math.min(3, deltas.size()); i++) {
            D d = deltas.get(i);
            sb.append("    - ").append(d.name).append(": ").append(formatPawns(d.delta)).append(ls);
        }
        return sb.toString();
    }

    /** Head-to-head module comparison: where chosen outperforms expected. */
    private String renderHeadToHeadModuleDiffs(ModuleContributionSummary chosen,
                                               ModuleContributionSummary expected) {
        if (chosen == null || expected == null) return "";
        Map<String, ModuleContribution> c = indexByModule(chosen.contributions());
        Map<String, ModuleContribution> e = indexByModule(expected.contributions());
        record D(String name, int delta) {}
        List<D> better = new ArrayList<>();
        for (String k : c.keySet()) {
            ModuleContribution mc = c.get(k);
            ModuleContribution me = e.get(k);
            if (me == null) continue;
            int d = mc.blended() - me.blended();
            if (d > 0) better.add(new D(k, d));
        }
        if (better.isEmpty()) return "";
        better.sort(Comparator.comparingInt(D::delta).reversed());
        String ls = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("  Where chosen beats expected (chosen − expected)").append(":").append(ls);
        for (int i = 0; i < Math.min(3, better.size()); i++) {
            D d = better.get(i);
            sb.append("    - ").append(d.name).append(": ").append(formatPawns(d.delta)).append(ls);
        }
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
            segments.add(notation + " (" + formatCentipawns(orientedScore * 100.0) + " pawns)");
            moverIsWhite = !moverIsWhite;
        }
        return String.join(" -> ", segments);
    }

    private String renderModuleInfluence(String fen,
                                         boolean whiteToMove,
                                         MoveEvaluation chosenEval,
                                         MoveEvaluation topCandidate) {
        Engine baselineEngine = createEngineForMove(fen, null);
        Engine chosenEngine = createEngineForMove(fen, chosenEval.moveInt());

        ModuleContributionSummary baseline = collectModuleContributions(baselineEngine, whiteToMove);
        ModuleContributionSummary chosen = collectModuleContributions(chosenEngine, whiteToMove);

        ModuleContributionSummary alternative = null;
        String alternativeMove = null;
        if (topCandidate != null && topCandidate.moveInt() != chosenEval.moveInt()) {
            Engine alternativeEngine = createEngineForMove(fen, topCandidate.moveInt());
            alternative = collectModuleContributions(alternativeEngine, whiteToMove);
            alternativeMove = topCandidate.move();
        }

        return formatModuleBreakdown(chosenEval.move(), alternativeMove, baseline, chosen, alternative);
    }

    private Engine createEngineForMove(String fen, Integer moveInt) {
        Engine engine = new Engine();
        engine.importBoardFromFen(fen);
        if (moveInt != null) {
            engine.performMove(moveInt);
        }
        return engine;
    }

    private ModuleContributionSummary collectModuleContributions(Engine engine, boolean whiteToMove) {
        return collectModuleContributions(engine.getBitBoard(), engine.getGameState().getState(), whiteToMove);
    }

    private ModuleContributionSummary collectModuleContributions(BitBoard bitBoard,
                                                                 GameStateEnum state,
                                                                 boolean whiteToMove) {
        MaterialModule material = new MaterialModule();
        PawnStructureModule pawns = new PawnStructureModule();
        PieceSquareModule pieceSquares = new PieceSquareModule();
        ActivityModule activity = new ActivityModule();
        BatteryModule battery = new BatteryModule();
        KingSafetyModule kingSafety = new KingSafetyModule();
        ThreatModule threat = new ThreatModule();

        material.setPawnChangeListener(pawns);

        List<EvaluationModule> modules = List.of(
                material,
                pawns,
                pieceSquares,
                activity,
                battery,
                kingSafety,
                threat
        );

        EvaluationPipeline pipeline = new EvaluationPipeline(modules);
        EvaluationContext context = EvaluationContext.from(new BitBoard(bitBoard), state);
        pipeline.initialize(context);
        pipeline.getBlendedScore();

        int phase = context.phase();
        int orientation = whiteToMove ? 1 : -1;

        List<ModuleContribution> contributions = new ArrayList<>(modules.size() + 1);
        contributions.add(createContribution("Material", material, phase, orientation));
        contributions.add(createContribution("Pawns", pawns, phase, orientation));
        contributions.add(createContribution("Piece-square", pieceSquares, phase, orientation));
        contributions.add(createContribution("Activity", activity, phase, orientation));
        contributions.add(createContribution("Battery", battery, phase, orientation));
        contributions.add(createContribution("King safety", kingSafety, phase, orientation));
        contributions.add(createContribution("Threats", threat, phase, orientation));

        int checkAdjustment = orientation * rawCheckAdjustment(state);
        if (checkAdjustment != 0) {
            contributions.add(new ModuleContribution("Check status", checkAdjustment, checkAdjustment, checkAdjustment));
        }

        return new ModuleContributionSummary(contributions);
    }

    private ModuleContribution createContribution(String name, EvaluationModule module, int phase, int orientation) {
        int midgame = module.getMidgameScore();
        int endgame = module.getEndgameScore();
        int blended = blendScores(midgame, endgame, phase);
        return new ModuleContribution(
                name,
                orientation * midgame,
                orientation * endgame,
                orientation * blended
        );
    }

    private static int blendScores(int midgame, int endgame, int phase) {
        int endgameWeight = Math.max(0, Math.min(BLEND_SCALE, phase));
        int midgameWeight = BLEND_SCALE - endgameWeight;
        long blended = (long) midgame * midgameWeight + (long) endgame * endgameWeight;
        return (int) (blended / BLEND_SCALE);
    }

    private static int rawCheckAdjustment(GameStateEnum state) {
        if (state == null) {
            return 0;
        }
        return switch (state) {
            case BLACK_IN_CHECK -> Score.CHECK;
            case WHITE_IN_CHECK -> -Score.CHECK;
            default -> 0;
        };
    }

    private String formatModuleBreakdown(String chosenMove,
                                         String alternativeMove,
                                         ModuleContributionSummary baseline,
                                         ModuleContributionSummary chosen,
                                         ModuleContributionSummary alternative) {
        String lineSeparator = System.lineSeparator();
        StringBuilder sb = new StringBuilder(lineSeparator);
        sb.append("Module-level evaluation insights:").append(lineSeparator);

        Map<String, ModuleContribution> chosenIndex = indexByModule(chosen.contributions());
        Map<String, ModuleContribution> alternativeIndex = alternative != null
                ? indexByModule(alternative.contributions())
                : Map.of();

        for (ModuleContribution base : baseline.contributions()) {
            ModuleContribution chosenContribution = chosenIndex.get(base.name());
            if (chosenContribution == null) {
                continue;
            }
            int chosenDelta = chosenContribution.blended() - base.blended();
            sb.append(String.format(Locale.US,
                    "  %-14s %s -> %s (Δ %s",
                    base.name(),
                    formatPawns(base.blended()),
                    formatPawns(chosenContribution.blended()),
                    formatPawns(chosenDelta)));
            if (alternative != null) {
                ModuleContribution altContribution = alternativeIndex.get(base.name());
                int altDelta = altContribution != null ? altContribution.blended() - base.blended() : 0;
                sb.append(", alt Δ ").append(formatPawns(altDelta));
            }
            sb.append(")").append(lineSeparator);
        }

        int chosenDeltaTotal = chosen.totalBlended() - baseline.totalBlended();
        sb.append(String.format(Locale.US,
                "  %-14s %s -> %s (Δ %s",
                "TOTAL",
                formatPawns(baseline.totalBlended()),
                formatPawns(chosen.totalBlended()),
                formatPawns(chosenDeltaTotal)));
        if (alternative != null) {
            int alternativeDeltaTotal = alternative.totalBlended() - baseline.totalBlended();
            sb.append(", alt Δ ").append(formatPawns(alternativeDeltaTotal));
        }
        sb.append(")").append(lineSeparator);

        sb.append(renderTopDeltas("Top module swings for " + chosenMove, baseline, chosen));
        if (alternative != null && alternativeMove != null) {
            sb.append(renderTopDeltas("Top module swings for " + alternativeMove, baseline, alternative));
        }

        return sb.toString();
    }

    private Map<String, ModuleContribution> indexByModule(List<ModuleContribution> contributions) {
        Map<String, ModuleContribution> index = new LinkedHashMap<>(contributions.size());
        for (ModuleContribution contribution : contributions) {
            index.put(contribution.name(), contribution);
        }
        return index;
    }

    private String renderTopDeltas(String heading,
                                   ModuleContributionSummary baseline,
                                   ModuleContributionSummary updated) {
        Map<String, ModuleContribution> updatedIndex = indexByModule(updated.contributions());
        List<ModuleDelta> deltas = new ArrayList<>();
        for (ModuleContribution base : baseline.contributions()) {
            ModuleContribution updatedContribution = updatedIndex.get(base.name());
            if (updatedContribution == null) {
                continue;
            }
            int delta = updatedContribution.blended() - base.blended();
            if (delta != 0) {
                deltas.add(new ModuleDelta(base.name(), delta));
            }
        }
        if (deltas.isEmpty()) {
            return "";
        }
        deltas.sort(Comparator.comparingInt((ModuleDelta d) -> Math.abs(d.delta())).reversed());

        String lineSeparator = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(heading).append(":").append(lineSeparator);
        int limit = Math.min(3, deltas.size());
        for (int i = 0; i < limit; i++) {
            ModuleDelta delta = deltas.get(i);
            sb.append("    - ").append(delta.name())
                    .append(": ").append(formatPawns(delta.delta()))
                    .append(lineSeparator);
        }
        return sb.toString();
    }

    private String formatPawns(int centipawns) {
        return formatCentipawns(centipawns) + " pawns";
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

    private record ModuleContributionSummary(List<ModuleContribution> contributions) {
        ModuleContributionSummary {
            contributions = List.copyOf(contributions);
        }

        int totalBlended() {
            int sum = 0;
            for (ModuleContribution contribution : contributions) {
                sum += contribution.blended();
            }
            return sum;
        }
    }

    private record ModuleContribution(String name, int midgame, int endgame, int blended) {
    }

    private record ModuleDelta(String name, int delta) {
    }

    private record MoveEvaluation(String move, double centipawns, int moveInt) {
    }
}
