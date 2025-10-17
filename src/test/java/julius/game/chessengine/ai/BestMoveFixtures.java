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

