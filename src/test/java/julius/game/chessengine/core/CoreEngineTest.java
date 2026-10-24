package julius.game.chessengine.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreEngineTest {

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1;4;197281",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1;3;97862",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1;5;674624",
            "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1;4;422333",
            "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8;3;62379",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10;3;89890",
            // Chess960 (Shredder-FEN; counts checked against a reference engine with UCI_Chess960)
            "bbqnnrkr/pppppppp/8/8/8/8/PPPPPPPP/BBQNNRKR w HFhf - 0 1;4;201143",
            "5rkr/pppppppp/8/8/8/8/PPPPPPPP/5RKR w HFhf - 0 1;4;236411",
            "n1kbbqrn/1rppppp1/p7/1p6/1P3Pp1/1N6/P1PPP2P/R1KBBQRN w Gg - 0 6;4;337244",
            "brnbkq1r/p1pN2pp/3p3n/1p2pp1P/8/1P6/P1PPPPP1/BR1BKQNR b HBhb - 1 6;4;1093153",
    })
    void perftMatchesReferenceCounts(String fen, int depth, long expected) {
        Position p = new Position(fen);
        assertEquals(expected, MoveGen.perft(p, depth));
        assertEquals(fen, p.toFen(), "make/unmake must restore the position");
    }

    @Test
    void chess960CastlingNotationFenAndKeys() {
        // king g1, rooks f1 (queen side) and h1: castling queen side puts the king on c1 and the rook on d1
        Position p = new Position("5rkr/pppppppp/8/8/8/8/PPPPPPPP/5RKR w HFhf - 0 1");
        assertTrue(p.chess960);
        int ooo = p.parseUciMove("g1f1", true);
        assertTrue(Move.isCastle(ooo));
        assertEquals("g1c1", Move.toUci(ooo));
        assertEquals(ooo, p.parseUciMove("g1c1", false));
        assertEquals(Move.NONE, p.parseUciMove("g1h1", true), "king side is blocked by the f1 rook");
        p.makeMove(ooo);
        assertEquals("5rkr/pppppppp/8/8/8/8/PPPPPPPP/2KR3R b hf - 1 1", p.toFen());
        assertEquals(new Position(p.toFen()).key, p.key, "incremental key after castling");
        p.unmakeMove(ooo);
        assertEquals("5rkr/pppppppp/8/8/8/8/PPPPPPPP/5RKR w HFhf - 0 1", p.toFen());

        // standard chess reads both notations and keeps KQkq
        Position s = new Position("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        int oo = s.parseUciMove("e1g1");
        assertEquals(oo, s.parseUciMove("e1h1"));
        assertEquals("e1h1", Move.toUci(oo, true));
        assertEquals("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", s.toFen());
        assertFalse(s.chess960);

        // X-FEN KQkq on a Chess960 setup means the outermost rooks
        Position x = new Position("rkr5/pppppppp/8/8/8/8/PPPPPPPP/RKR5 w KQkq - 0 1");
        assertEquals("rkr5/pppppppp/8/8/8/8/PPPPPPPP/RKR5 w CAca - 0 1", x.toFen());
    }

    @Test
    void makeUnmakeRestoresHashKeys() {
        Position p = new Position("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        long key = p.key, pawnKey = p.pawnKey;
        int[] moves = new int[256];
        int n = MoveGen.generateAll(p, moves, 0);
        for (int i = 0; i < n; i++) {
            if (!p.isLegal(moves[i])) continue;
            p.makeMove(moves[i]);
            Position fresh = new Position(p.toFen());
            assertEquals(fresh.key, p.key, "incremental key after " + Move.toUci(moves[i]));
            p.unmakeMove(moves[i]);
            assertEquals(key, p.key);
            assertEquals(pawnKey, p.pawnKey);
        }
    }

    @Test
    void staticExchangeEvaluation() {
        // Pawn takes undefended knight: wins material
        Position p = new Position("4k3/8/8/3n4/4P3/8/8/4K3 w - - 0 1");
        int exd5 = p.parseUciMove("e4d5");
        assertTrue(p.seeGE(exd5, 300));
        // Queen takes pawn defended by pawn: loses material
        p.setFen("4k3/2p5/3p4/8/8/8/3Q4/4K3 w - - 0 1");
        int qxd6 = p.parseUciMove("d2d6");
        assertFalse(p.seeGE(qxd6, 0));
        assertTrue(p.seeGE(qxd6, -900));
    }

    @Test
    void detectsDraws() {
        Position p = new Position("8/8/4k3/8/8/3K4/3B4/8 w - - 0 1");
        assertTrue(p.isInsufficientMaterial());
        p.setFen("8/8/4k3/8/8/3K4/3R4/8 w - - 0 1");
        assertFalse(p.isInsufficientMaterial());

        Position r = new Position();
        for (String mv : new String[]{"g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1"}) {
            r.makeMove(r.parseUciMove(mv));
        }
        // With the current position as search root, game-history repeats need a threefold repetition.
        assertFalse(r.isRepetition(r.historyPly()), "second occurrence in game history only");
        assertTrue(r.isRepetition(0), "a repeat inside the search tree is a draw");
        r.makeMove(r.parseUciMove("f6g8"));
        assertTrue(r.isRepetition(r.historyPly()), "threefold repetition");
    }

    @Test
    void givesCheckAgreesWithMakeMove() {
        String[] fens = {
                "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
                "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
                "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
                "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
        };
        int[] moves = new int[256];
        for (String fen : fens) {
            Position p = new Position(fen);
            int n = MoveGen.generateAll(p, moves, 0);
            for (int i = 0; i < n; i++) {
                int m = moves[i];
                if (!p.isLegal(m)) continue;
                boolean predicted = p.givesCheck(m);
                p.makeMove(m);
                assertEquals(p.inCheck(), predicted, fen + " " + Move.toUci(m));
                p.unmakeMove(m);
            }
        }
    }

    @Test
    void uciSurvivesNonNumericOptionsAndSetsTuningParameters() {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        Uci uci = new Uci(new java.io.PrintStream(bytes, true));
        int before = Tune.rfpMargin;
        try {
            assertTrue(uci.handle("setoption name Ponder value false"));
            assertTrue(uci.handle("setoption name rfpMargin value 90"));
            assertTrue(uci.handle("isready"));
            assertTrue(bytes.toString().contains("readyok"), bytes.toString());
            assertEquals(90, Tune.rfpMargin);
        } finally {
            Tune.set("rfpMargin", before);
        }
    }

    /** Sends a "go" and waits for its bestmove; returns all lines the engine printed for it. */
    private static java.util.List<String> goAndWait(Uci uci, java.util.List<String> out, String go) throws InterruptedException {
        int from = out.size();
        assertTrue(uci.handle(go));
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (out) {
                for (int i = from; i < out.size(); i++) {
                    if (out.get(i).startsWith("bestmove")) return new java.util.ArrayList<>(out.subList(from, i + 1));
                }
            }
            Thread.sleep(5);
        }
        throw new AssertionError("no bestmove for " + go);
    }

    private static String bestmove(java.util.List<String> lines) {
        return lines.get(lines.size() - 1).split(" ")[1];
    }

    private static int lastScore(java.util.List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String[] t = lines.get(i).split(" ");
            for (int k = 0; k + 2 < t.length; k++) {
                if (t[k].equals("score") && t[k + 1].equals("cp")) return Integer.parseInt(t[k + 2]);
            }
        }
        throw new AssertionError("no score in " + lines);
    }

    @Test
    void searchmovesRestrictsTheRootMoves() throws InterruptedException {
        java.util.List<String> out = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Uci uci = new Uci(out::add);
        uci.handle("position startpos");
        String best = bestmove(goAndWait(uci, out, "go depth 6 searchmoves a2a3 h2h3"));
        assertTrue(best.equals("a2a3") || best.equals("h2h3"), best);
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            // 4.2.1 stopped these searches inside an iteration that had found a new best move and reported only the
            // older PV (fastchess: "Bestmove does not match beginning of last PV").
            "r2qk2r/ppp2ppp/2n1bn2/2b5/8/3P1N1P/PPPBBPP1/RN1QK2R b KQkq - 4 8;363813",
            "rnbqkb1r/1p3ppp/p4n2/2Pp4/1P6/4B3/P1P2PPP/RN1QKBNR w KQkq - 0 7;192361",
            "r2qk1nr/1b2bppp/p1n1p3/1pppP3/3P4/P1P2N2/1P2BPPP/RNBQ1RK1 w kq - 1 9;246411",
    })
    void theLastReportedPvStartsWithTheMovePlayed(String fen, int nodes) throws InterruptedException {
        java.util.List<String> out = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Uci uci = new Uci(out::add);
        uci.handle("setoption name Hash value 64");
        uci.handle("ucinewgame");
        uci.handle("position fen " + fen);
        java.util.List<String> lines = goAndWait(uci, out, "go nodes " + nodes);
        String best = bestmove(lines);
        String pv = null;
        for (String line : lines) if (line.startsWith("info ") && line.contains(" pv ")) pv = line;
        assertTrue(pv != null && pv.substring(pv.indexOf(" pv ") + 4).split(" ")[0].equals(best), best + " after " + pv);
    }

    @Test
    void rootPenaltyMakesTheEngineDeviateOnlyWhenItIsLarge() throws InterruptedException {
        java.util.List<String> out = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Uci uci = new Uci(out::add);
        uci.handle("position startpos moves e2e4 e7e5 g1f3");
        String best = bestmove(goAndWait(uci, out, "go depth 8"));
        uci.handle("ucinewgame");
        uci.handle("setoption name RootPenalty value " + best + ":400");
        assertFalse(best.equals(bestmove(goAndWait(uci, out, "go depth 8"))), "a 400 cp penalty must change the move");
        uci.handle("ucinewgame");
        uci.handle("setoption name RootPenalty value none");
        assertEquals(best, bestmove(goAndWait(uci, out, "go depth 8")));
    }

    @Test
    void contemptMakesDrawsWorseForTheRootSide() throws InterruptedException {
        java.util.List<String> out = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Uci uci = new Uci(out::add);
        // King and knight against king: every move leads to a dead draw (insufficient material)
        uci.handle("position fen 8/8/8/4k3/8/8/8/K6N w - - 0 1");
        assertTrue(Math.abs(lastScore(goAndWait(uci, out, "go depth 4"))) <= 2);
        uci.handle("setoption name Contempt value 50");
        int score = lastScore(goAndWait(uci, out, "go depth 4"));
        assertTrue(score >= -52 && score <= -48, "draw with contempt 50: " + score);
    }

    @Test
    void findsMateInTwo() {
        SearchManager manager = new SearchManager(16, 1);
        Position p = new Position("r1b1kb1r/pppp1ppp/5q2/4n3/3KP3/2N3PN/PPP4P/R1BQ1B1R b kq - 0 1");
        // A mate in three behind a bishop sacrifice (Bc5+ Kxc5 Qb6+ Kd5 Qd6#) the network rates low at first: the
        // SPSA-tuned pruning (4.0.9) found it at depth 9, the generation 5 net at 10, generation 6 (+32 Elo in
        // self-play) at 11. Depth 12 keeps the test about mate finding, not about one net's move ordering.
        int[] result = manager.searchSync(p, SearchLimits.depth(12));
        assertEquals("f8c5", Move.toUci(result[0]));
        assertTrue(result[1] >= Searcher.MATE_BOUND);
    }

    @Test
    void evaluationIsColorSymmetric() {
        Eval e = new Eval(false);
        String[][] pairs = {
                {"r1bq1rk1/pp2bppp/2n2n2/3p4/3P4/2NBPN2/PP3PPP/R2QK2R w KQ - 0 9",
                        "r2qk2r/pp3ppp/2nbpn2/3p4/3P4/2N2N2/PP2BPPP/R1BQ1RK1 b kq - 0 9"},
        };
        for (String[] pair : pairs) {
            assertEquals(e.evaluate(new Position(pair[0])), e.evaluate(new Position(pair[1])));
        }
    }
}
