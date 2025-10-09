package julius.game.chessengine.ai;

import org.junit.jupiter.params.provider.Arguments;

import java.util.List;

import java.util.stream.Stream;

/**
 * Centralises the list of FEN positions used by the best move diagnostics. The
 * previous implementation embedded the matrix inside {@link BestMoveSearchTest}
 * which made it awkward for other tests to reason about the same scenarios. By
 * exposing the fixtures from a single source future agents (and new tests)
 * automatically stay in sync when positions are added or removed.
 */
public final class BestMoveFixtures {

    private static final List<BestMoveTestCase> CASES = List.of(
            new BestMoveTestCase(
                    "8/8/8/8/8/1kb1n3/8/2K5 b - - 31 16",
                    List.of("Be1", "Bb4", "Ba5"),
                    8
            ),
            new BestMoveTestCase(
                    "2q2rk1/4pp1p/P2p1bp1/1B1Nn3/1B2P3/4K3/7P/5Q1R w - - 4 29",
                    List.of("h4", "g1", "Qg2"),
                    6
            ),
            new BestMoveTestCase(
                    "3q1rk1/4pp1p/3p1bp1/PB6/1B2Pn2/8/4QPPP/1N2K2R w K - 1 22",
                    List.of("Qf3", "Qc4", "Qd2")
            ),
            new BestMoveTestCase(
                    "r1bqkbnr/1pp2ppp/p7/4Q3/4P3/2N5/PPP2PPP/R1B1KB1R b KQkq - 0 7",
                    List.of("Qe7")
            ),
            new BestMoveTestCase(
                    "4r1k1/3R1pp1/5r1p/2P1p3/P7/2P4q/1PQ2P1P/3R1K2 w - - 8 28",
                    List.of("Ke1"),
                    5
            ),
            new BestMoveTestCase(
                    "4r1k1/R4pp1/4r1np/2P1p3/4Bq2/2P2P2/PPQ2P1P/5RK1 w - - 1 21",
                    List.of("Kh1", "Qd1", "Ra4"),
                    5
            ),
            new BestMoveTestCase(
                    "rn1qk2r/p1pbpp2/1p1p2pb/3P3p/1QPNPP2/2N4P/PP2B1P1/R4RK1 b kq - 0 14",
                    List.of("O-O","c5", "e5"),
                    6
            ),
            new BestMoveTestCase(
                    "r1b1kbnr/ppp1p1pp/3q4/2N2p2/1n1pP3/5N2/P1PP1PPP/R1BQKB1R w KQkq - 0 7",
                    List.of("c3", "Nb3"),
                    6
            ),
            new BestMoveTestCase(
                    "3rk2r/1bqpbppp/p1n1p3/1p2P3/5Bn1/2NQ1N2/PPP1BPPP/R2R2K1 w k - 5 14",
                    List.of("Ne4")
            ),
            new BestMoveTestCase(
                    "r1bqk2r/ppp2ppp/2p2n2/2b3B1/4P3/3P4/PPP2PPP/RN1QKB1R b KQkq - 2 6",
                    List.of("Nxe4", "Bxf2"),
                    5
            ),
            new BestMoveTestCase(
                    "r3kb1r/2p1pppp/2nq4/p2p1b2/B2P4/2P1BN2/2P2PPP/R2Q1RK1 b kq - 0 12",
                    List.of("Bd7")
            ),
            new BestMoveTestCase(
                    "r2q1rk1/ppp1bppp/4b3/4p3/1nP1N3/P2P2P1/4PPBP/1RBQ1RK1 b - - 0 14",
                    List.of("Nc6"),
                    6
            ),
            new BestMoveTestCase(
                    "r4rk1/ppp2ppp/2nbpq2/1B6/3P4/2P1P3/PB1NQPbP/R3K1R1 b Q - 1 13",
                    List.of("Bh3"),
                    5
            ),
            new BestMoveTestCase(
                    "2kr3r/2p1qp1p/2p1pnpP/pN1p4/3P4/7P/PPPQPP2/R3KB1R w KQ - 0 14",
                    List.of("Nc3"),
                    6
            ),
            new BestMoveTestCase(
                    "r4rk1/ppqn1pbp/2p3p1/4p3/3P4/1RPBBN2/PP3PP1/R2QK3 b Q - 0 15",
                    List.of("Re8", "a5")
            ),
            new BestMoveTestCase(
                    "r1bq1rk1/5ppp/p4n2/3N2B1/3pP3/3B1Q2/P4PPP/5RK1 b - - 1 18",
                    List.of("Bg4", "Qd7", "Nxd5"),
                    5
            ),
            new BestMoveTestCase(
                    "r1bq1rk1/ppppb1pp/4p3/4N1B1/1n1Pp2P/4P3/PPPQ1PP1/R3KB1R b KQ - 1 10",
                    List.of("a5", "Nd5", "b6")
            ),
            new BestMoveTestCase(
                    "r2qkb1r/pppb1ppp/4p3/3pP3/2PQ4/4P3/P1P2PPP/R1B1KB1R b KQkq - 0 9",
                    List.of("Bb4", "c5", "dxc4"),
                    5
            ),
            new BestMoveTestCase(
                    "r2qkb1r/2p1n1pp/bp3p2/p2pp3/P7/1BP1BN2/1PP1QPPP/R4RK1 w kq - 1 12",
                    List.of("c4")
            ),
            new BestMoveTestCase(
                    "1k1r3r/qppb2pp/8/2bP4/2B2p2/P1B5/1PQ2P1P/1R2R1K1 b - - 5 29",
                    List.of("Rf8", "Re8", "Bd6", "f3", "Qb6"),
                    5
            ),
            new BestMoveTestCase(
                    "4k3/1bp1bp1p/p3p3/1r1qN3/3P1p1r/2B5/PPP2PP1/R3RQK1 w - - 0 19",
                    List.of("a3", "f3", "a4")
            ),
            new BestMoveTestCase(
                    "r1bqkb1r/pppppppp/2n2n2/1P6/8/8/PBPPPPPP/RN1QKBNR b KQkq - 0 3",
                    List.of("Na5"),
                    6
            ),
            new BestMoveTestCase(
                    "r1b2rk1/ppp2p2/2n2n2/P6p/2P1P1p1/4P1K1/1B1N4/R5NR b - - 1 20",
                    List.of("Ne8", "Nh7")
            ),
            new BestMoveTestCase(
                    "rnbqk1nr/p1pp3p/1p3p2/4p3/1b1PN3/2N5/PPP1PPPP/R1BQKB1R w KQkq - 0 6",
                    List.of("dxe5", "a3")
            ),
            new BestMoveTestCase(
                    "r1b2k1r/1p2n2p/3p1q1n/p1p3N1/1BQ2P1P/3pP3/PPP3P1/1K1R3R w - - 0 21",
                    List.of("Bc3"),
                    5
            ),
            new BestMoveTestCase(
                    "rnbqk2r/pppp1ppp/4p3/1Pb5/3Pn3/2P5/PB2PPPP/RN1QKBNR b KQkq - 0 5",
                    List.of("Be7", "Bd6", "Qf6"),
                    6
            ),
            new BestMoveTestCase(
                    "3r2k1/pppq1ppp/2n2n2/1N2P3/5Qb1/5N2/PPP1PPPP/2K2B1R w - - 5 15",
                    List.of("Nc3", "Nd6"),
                    5
            ),
            new BestMoveTestCase(
                    "3r2k1/pp3p2/3B1b2/3p1p1p/8/2P5/PP3PPP/3R2K1 w - - 2 27",
                    List.of("Bc5", "Bc7")
            ),
            new BestMoveTestCase(
                    "r5k1/pb1p2pp/2pP1r2/4Qp2/2p5/q1B2P2/P1P2PPP/1R1R2K1 b - - 7 21",
                    List.of("Ba6", "Rb8")
            ),
            new BestMoveTestCase(
                    "rnb1k2r/2bp3p/3qpp1n/P5p1/Q1P3P1/PN6/4BPNP/R4RK1 w - - 1 24",
                    List.of("f4"),
                    5
            ),
            new BestMoveTestCase(
                    "rnb1k2r/2bp3p/3qpp1n/P5p1/Q1P3P1/PN5P/4BPN1/R4RK1 b - - 0 24",
                    List.of("Qh2")
            ),
            new BestMoveTestCase(
                    "2k5/p1p4p/2p1p3/8/8/8/P1Pr1PPP/1R4K1 w - - 3 23",
                    List.of("Rc1"),
                    6
            ),
            new BestMoveTestCase(
                    "r1bqk2r/ppp2ppp/2n1p3/4P3/3Pp3/5N2/P1P2PPP/R1B1QRK1 w kq - 0 11",
                    List.of("Qxe4")
            ),
            new BestMoveTestCase(
                    "r1bqkb1r/pppppppp/2n2n2/8/2PP4/2N5/PP2PPPP/R1BQKBNR b KQkq - 2 3",
                    List.of("e5", "d5", "d6"),7
            ),
            new BestMoveTestCase(
                    "2r1k2r/ppPb1ppp/4n3/3N4/2P1P3/2PB4/5PPP/4K2R w K - 3 24",
                    List.of("O-O", "e2", "f4")
            ),
            new BestMoveTestCase(
                    "2r1k2r/ppPb1ppp/4nN2/8/2P1P3/2PB4/5PPP/4K2R b K - 4 24",
                    List.of("gxf6")
            ),
            new BestMoveTestCase(
                    "2r1k2r/ppPb1p1p/4np2/8/2P1P3/2PB4/5PPP/4K2R w K - 0 25",
                    List.of("Kd2", "O-O")
            ),
            new BestMoveTestCase(
                    "8/1p6/2k2P1p/P2p4/3p4/2p1n3/2P3P1/6K1 b - - 0 38",
                    List.of("Kd6", "Kd7", "d3")
            ),
            new BestMoveTestCase(
                    "1k1r2r1/ppp2ppp/5n2/P1b1p3/R6P/1P2p1P1/2PBNq2/Q2K3R b - - 1 20",
                    List.of("Rxd2", "Qf3")
            ),
            new BestMoveTestCase(
                    "1k1r4/ppp2p1p/6p1/P1Pq4/6QP/4p1P1/2PpN3/3K2R1 b - - 0 30",
                    List.of("Qa2")
            ),
            new BestMoveTestCase(
                    "r3kb1r/2p1pppp/2n4B/p2p1b2/B2P4/2P2N2/2P2PPP/R2Q1RK1 b kq - 0 13",
                    List.of("Bd7")
            ),
            new BestMoveTestCase(
                    "2r3k1/pQ1R1ppp/4p3/8/8/2P5/P4PPP/4R1K1 b - - 2 25",
                    List.of("Rf8")
            ),
            new BestMoveTestCase(
                    "6r1/1pk2p1p/p3p3/b3P1p1/P1p5/1q6/8/K1Br4 b - - 13 38",
                    List.of("Rxc1", "Bc3")
            ),
            new BestMoveTestCase(
                    "1r4k1/5p2/3p2p1/P2q4/6Q1/2R1PR1P/2P3KP/1r6 w - - 2 36",
                    List.of("Rc8")
            ),
            new BestMoveTestCase(
                    "r2qkb1r/1b1n1pp1/p2p1n2/1pp3N1/3BP2p/2NB4/PPP1QPPP/R4RK1 w kq - 0 14",
                    List.of("e5"),
                    5
            ),
            new BestMoveTestCase(
                    "1r4k1/p5pp/2n4q/5Q2/P6P/2B2P2/1PP1R1K1/r7 b - - 0 32",
                    List.of("Rd1", "Qg6", "Rf8"),
                    5
            ),
            new BestMoveTestCase(
                    "rn2kb1r/pp2pppp/5q2/1p6/2b3Q1/4B2P/PP3PP1/RN2K1NR b KQkq - 1 12",
                    List.of("Nc6", "e6")
            ),
            new BestMoveTestCase(
                    "r1b3kr/2b2p2/2p1q2p/pp4pN/6P1/2Q1pP1P/4B1K1/3R4 b - - 5 34",
                    List.of("Qe5", "f6", "Rh7")
            ),
            new BestMoveTestCase(
                    "r2q1knr/2p2ppp/2B1p3/3p4/3P3P/b3PPB1/1PP2PK1/R2Q1R2 w - - 1 17",
                    List.of("Bxa8", "bxa3")
            ),
            new BestMoveTestCase(
                    "r4rk1/ppp2ppp/2nbp3/1B6/3P3q/2P1P3/PB1NQPRP/2KR4 b - - 2 15",
                    List.of("Ne7", "g6", "a6", "Qd8", "Rb8", "Qh3")
            ),
            new BestMoveTestCase(
                    "r1b2rk1/ppqp2p1/1p2p2p/4nnNQ/8/P2B4/1PP2PPP/R1B1R1K1 w - - 0 17",
                    List.of("Bf4", "Ne4")
            ),
            new BestMoveTestCase(
                    "r2q2kr/pppb1ppp/4p3/1P1pNn2/4n3/2PBP2P/P2P1PP1/RN1QK2R b KQ - 0 11",
                    List.of("Be8")
            ),
            new BestMoveTestCase(
                    "rnbqkbnr/pp2pppp/2p5/3p4/3PP3/2N5/PPP2PPP/R1BQKBNR b KQkq - 0 3",
                    List.of("dxe4")
            ),
            new BestMoveTestCase(
                    "3B4/3nrk1p/1p3bp1/p7/8/R1P2N2/PP3PP1/3R1K2 b - - 3 29",
                    List.of("Ke6", "Nc6", "Re8", "Ke8")
            ),
            new BestMoveTestCase(
                    "6k1/1R5p/6p1/3RN3/p5n1/2P1b3/PP2K1P1/8 w - - 4 42",
                    List.of("Rd8")
            ),
            new BestMoveTestCase(
                    "3r3k/1p1n1pp1/p2P1n1p/q1pN4/4B3/PP3P1P/2P2PKB/R7 w - - 6 35",
                    List.of("Nxf6")
            ),
            new BestMoveTestCase(
                    "k7/pppr4/8/8/8/7r/PPP5/K2Q4 w - - 0 1",
                    List.of("Qf1", "Qg1")
            ),
            new BestMoveTestCase(
                    "r1bq1rk1/2p2ppp/p4n2/3N2B1/1p2P3/3B4/Pb3PPP/1R1Q1RK1 b - - 1 15",
                    List.of("Be5")
            ),
            new BestMoveTestCase(
                    "r2q3r/5pkp/p3bN2/4P3/3p4/3B2Q1/P4PPP/5RK1 b - - 2 22",
                    List.of("Kf8")
            ),
            new BestMoveTestCase(
                    "r1bqkb1r/pppp2pp/2n1pn2/5pB1/3P3P/2N2N2/PPP1PPP1/R2QKB1R b KQkq - 4 5",
                    List.of("Bb4", "d5", "h6")
            ),
            new BestMoveTestCase(
                    "5k2/5p1p/2p5/2p1B3/p4N1P/1r2P3/2q1BKP1/3R4 b - - 0 35",
                    List.of("Ke7", "a3", "Qf5"),
                    8
            ),
            new BestMoveTestCase(
                    "5k2/5p1p/2p2B2/8/2p2NPP/pr2P3/2q1BK2/3R4 b - - 1 37",
                    List.of("Rb8")
            ),
            new BestMoveTestCase(
                    "2r3k1/p4p2/3Rp2p/1p2P1pK/8/1P4P1/P3Q2P/1q6 b - - 0 1",
                    List.of("Qg6")
            ),
            new BestMoveTestCase(
                    "4k2r/1R3R2/p3p1pp/4b3/1BnNr3/8/P1P5/5K2 w - - 1 1",
                    List.of("Re7"),
                    8
            ),
            new BestMoveTestCase(
                    "4k2r/1R3R2/p3p1pp/4b3/1BnNr3/8/P1P5/5K2 w - - 1 1",
                    List.of("Re7"),
                    8
            ),
            new BestMoveTestCase(
                    "8/2b5/2k1b3/2p2r2/8/5R1P/PP1N2r1/R1B2K2 b - - 3 48",
                    List.of("Rh2"),
                    8
            ),
            new BestMoveTestCase(
                    "2kr3r/8/1p4np/p1p1qp2/P1Ppp3/1B6/1PP1QPP1/3RN1K1 w - - 0 26",
                    List.of("Qh5", "g3"),
                    6
            )
    );

    private BestMoveFixtures() {
    }

    public static List<BestMoveTestCase> cases() {
        return CASES;
    }

    public static Stream<Arguments> arguments() {
        return CASES.stream()
                .map(testCase -> Arguments.of(testCase.fen(), testCase.expectedMoves(), testCase.depth()));
    }

    public record BestMoveTestCase(String fen, List<String> expectedMoves, Integer depth) {

        public BestMoveTestCase(String fen, List<String> expectedMoves) {
            this(fen, expectedMoves, null);
        }

        public BestMoveTestCase {
            expectedMoves = List.copyOf(expectedMoves);
            if (depth != null && depth < 1) {
                throw new IllegalArgumentException("depth must be positive when provided");
            }
        }
    }
}

