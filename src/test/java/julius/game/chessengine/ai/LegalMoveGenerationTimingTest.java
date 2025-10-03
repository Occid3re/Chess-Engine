package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.Move;
import julius.game.chessengine.engine.Engine;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import testsupport.TestLoggingExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Log4j2
@ExtendWith(TestLoggingExtension.class)
class LegalMoveGenerationTimingTest {

    private static final List<String> POSITIONS = List.of(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r2q1rk1/ppp2ppp/2npbn2/3Np3/2B1P3/2P2N2/PP3PPP/R1BQ1RK1 w - - 0 1",
            "8/8/2k5/8/8/4K3/5P2/8 w - - 0 1"
    );

    @Test
    void measureLegalMoveGenerationAndAiHandlingTimings() {
        Engine engine = new Engine();
        AI ai = new AI(engine);
        ai.setMaxDepth(2);

        try {
            for (String fen : POSITIONS) {
                engine.importBoardFromFen(fen);
                Engine measurementEngine = engine.createSimulation();

                long freshStart = System.nanoTime();
                IntArrayList freshMoves = measurementEngine.getAllLegalMoves();
                long freshDuration = System.nanoTime() - freshStart;
                assertNotNull(freshMoves, "Legal moves must not be null");
                assertTrue(freshMoves.size() > 0, "Expected legal moves for FEN " + fen);

                long cachedStart = System.nanoTime();
                IntArrayList cachedMoves = measurementEngine.getAllLegalMoves();
                long cachedDuration = System.nanoTime() - cachedStart;
                assertEquals(freshMoves.size(), cachedMoves.size(), "Cached move count differs for FEN " + fen);

                log.info("FEN {} -> fresh={} ms cached={} ms moves={}",
                        fen,
                        formatMillis(freshDuration),
                        formatMillis(cachedDuration),
                        freshMoves.size());

                long aiStart = System.nanoTime();
                MoveAndScore best = ai.searchBestMoveBlocking(100);
                long aiDuration = System.nanoTime() - aiStart;

                String moveNotation = best != null
                        ? Move.convertIntToMove(best.getMove()).toString()
                        : "none";
                String scoreNotation = best != null ? String.format("%.2f", best.getScore()) : "n/a";

                log.info("AI search for FEN {} -> duration={} ms, move={}, score={}",
                        fen,
                        formatMillis(aiDuration),
                        moveNotation,
                        scoreNotation);
            }
        } finally {
            ai.shutdown();
        }
    }

    private static String formatMillis(long nanos) {
        return String.format("%.3f", nanos / 1_000_000.0);
    }
}
