package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static julius.game.chessengine.utils.Score.CHECKMATE;

/**
 * Verifies that the AI recognises forced mate sequences within various time limits.
 * The FEN strings describe positions with mate in 1 through 6 for the side to move.
 */
public class ForcedMateSearchTest {

    private record TestPosition(String fen, int mateDepth) {}

    private static Stream<Arguments> providePositionsAndTimes() {
        List<TestPosition> positions = List.of(
                new TestPosition("7k/6Q1/6K1/8/8/8/8/8 w - - 0 1", 1),
                new TestPosition("7k/6Q1/5K2/8/8/8/8/8 w - - 0 1", 2),
                new TestPosition("7k/6Q1/4K3/8/8/8/8/8 w - - 0 1", 3),
                new TestPosition("7k/5Q2/3K4/8/8/8/8/8 w - - 0 1", 4),
                new TestPosition("7k/5Q2/4K3/8/8/8/8/8 w - - 0 1", 5),
                new TestPosition("7k/5Q2/8/3K4/8/8/8/8 w - - 0 1", 6)
        );

        long[] times = {50, 100, 200, 500, 1000};

        return positions.stream().flatMap(pos ->
                Arrays.stream(times).mapToObj(t -> Arguments.of(pos.fen(), pos.mateDepth(), t)));
    }

    @ParameterizedTest
    @MethodSource("providePositionsAndTimes")
    void aiDetectsForcedMateWithinTime(String fen, int mateDepth, long timeLimitMs) {
        Engine engine = new Engine();
        engine.importBoardFromFen(fen);
        AI ai = new AI(engine);
        long deadline = System.nanoTime() + timeLimitMs * 1_000_000L;
        double evaluation = ai.evaluateBoard(engine, engine.whitesTurn(), deadline);
        assertTrue(Math.abs(evaluation) >= CHECKMATE - mateDepth,
                () -> "Expected mate in " + mateDepth + " within " + timeLimitMs + "ms but got " + evaluation);
    }
}
