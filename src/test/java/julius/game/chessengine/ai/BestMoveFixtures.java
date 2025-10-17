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
                    "3R4/8/P4pk1/rP4p1/8/8/1K6/8 w - - 3 65",
                    List.of("Kc1", "Kc2", "Kc3"),
                    8
            ),
            new BestMoveTestCase(
                    "3rk2r/1bqpbppp/p1n1p3/1p2P3/5Bn1/2NQ1N2/PPP1BPPP/R2R2K1 w k - 5 14",
                    List.of("Ne4")
            ),
            new BestMoveTestCase(
                    "r1bqk2r/ppp2ppp/2p2n2/2b3B1/4P3/3P4/PPP2PPP/RN1QKB1R b KQkq - 2 6",
                    List.of("Nxe4")
            ),
            new BestMoveTestCase(
                    "2k5/p1p4p/2p1p3/8/8/8/P1Pr1PPP/1R4K1 w - - 3 23",
                    List.of("Rc1"),
                    5
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

