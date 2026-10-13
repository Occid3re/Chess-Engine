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
    })
    void perftMatchesReferenceCounts(String fen, int depth, long expected) {
        Position p = new Position(fen);
        assertEquals(expected, MoveGen.perft(p, depth));
        assertEquals(fen, p.toFen(), "make/unmake must restore the position");
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
    void findsMateInTwo() {
        SearchManager manager = new SearchManager(16, 1);
        Position p = new Position("r1b1kb1r/pppp1ppp/5q2/4n3/3KP3/2N3PN/PPP4P/R1BQ1B1R b kq - 0 1");
        int[] result = manager.searchSync(p, SearchLimits.depth(8));
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
