package julius.game.chessengine.tablebase;

import julius.game.chessengine.ai.AI;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.TestSyzygySupport;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTablebaseIntegrationTest {

    @Test
    void aiEvaluationReflectsTablebaseResult() {
        TablebaseTestSupport.assumeSyzygyConfigured();

        SyzygyProbeResult probe = new SyzygyProbeResult(SyzygyWdl.LOSS, OptionalInt.of(2), OptionalInt.of(6), Optional.empty());
        SyzygyTablebaseService service = mock(SyzygyTablebaseService.class);
        when(service.probe(any(BitBoard.class))).thenReturn(Optional.of(probe));

        try (TablebaseTestSupport.TablebaseServiceRestorer restorer = TablebaseTestSupport.overrideScoreTablebase(service)) {
            Engine engine = new Engine();
            engine.importBoardFromFen("8/8/8/8/8/8/5K2/6k1 w - - 0 1");
            engine.getGameState().refreshScore(engine.getBitBoard());

            AI ai = new AI(engine, service);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            double evaluation = ai.evaluateBoard(engine, true, deadline);

            TablebaseResult expected = TablebaseResult.from(probe);
            double expectedEval = Score.tablebaseToEvaluation(expected, true);

            assertThat(engine.getGameState().getLastTablebaseResult()).contains(expected);
            assertThat(evaluation).isEqualTo(expectedEval);

            verify(service, atLeastOnce()).probe(any(BitBoard.class));
        }
    }

    @Test
    void blackWinningTablebasePositionUsesSideToMovePerspective() {
        TablebaseTestSupport.assumeSyzygyConfigured();

        SyzygyTablebaseService service = TestSyzygySupport.maybeCreateServiceFromConfiguration()
                .orElseThrow(() -> new IllegalStateException("Syzygy service not configured"));

        try (TablebaseTestSupport.TablebaseServiceRestorer restorer = TablebaseTestSupport.overrideScoreTablebase(service)) {
            Engine engine = new Engine();
            engine.importBoardFromFen("6k1/8/8/8/8/8/5r2/6K1 b - - 0 1");
            engine.getGameState().refreshScore(engine.getBitBoard());

            AI ai = new AI(engine, service);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            double evaluation = ai.evaluateBoard(engine, false, deadline);

            TablebaseResult tablebaseResult = engine.getGameState().getLastTablebaseResult()
                    .orElseThrow(() -> new AssertionError("Expected tablebase result"));

            double whitePerspective = Score.tablebaseToEvaluation(tablebaseResult, engine.whitesTurn(),
                    engine.getGameState().getHalfmoveClock());

            assertThat(whitePerspective).isLessThan(0.0);
            assertThat(evaluation).isGreaterThan(0.0);
            assertThat(evaluation).isCloseTo(-whitePerspective, within(1e-6));
        }
    }
}
