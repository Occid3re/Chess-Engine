package julius.game.chessengine.tablebase;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.syzygy.TestSyzygyTablebaseService;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScoreTablebaseIntegrationTest {

    @Test
    void scoreUsesExactTablebaseProbeWhenAvailable() {
        TablebaseTestSupport.assumeSyzygyConfigured();

        SyzygyProbeResult probe = new SyzygyProbeResult(SyzygyWdl.WIN, OptionalInt.of(3), OptionalInt.of(7));
        SyzygyTablebaseService service = mock(SyzygyTablebaseService.class);
        when(service.probe(any(BitBoard.class))).thenReturn(Optional.of(probe));

        BitBoard board = FEN.translateFENtoBitBoard("8/8/8/8/8/8/5K2/6k1 w - - 0 1");
        TablebaseResult expected = TablebaseResult.from(probe);
        int expectedCentipawn = Score.tablebaseToCentipawn(expected, true);

        try (TablebaseTestSupport.TablebaseServiceRestorer restorer = TablebaseTestSupport.overrideScoreTablebase(service)) {
            Score score = new Score();
            score.refresh(board, GameStateEnum.PLAY);

            verify(service).probe(board);

            assertThat(score.getTablebaseResult()).contains(expected);
            assertThat(score.getTablebaseCentipawnScore()).hasValue(expectedCentipawn);
            assertThat(score.getBlendedScore()).isEqualTo(expectedCentipawn);
            assertThat(score.getScoreDifference()).isEqualTo(expectedCentipawn / 100.0);
        }
    }

    @Test
    void scoreSkipsProbeWhenBoardExceedsServiceLimit() {
        BitBoard board = FEN.translateFENtoBitBoard("6k1/8/8/8/8/8/PPPP4/6K1 w - - 0 1");
        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(Map.of(), 5);

        try (TablebaseTestSupport.TablebaseServiceRestorer restorer = TablebaseTestSupport.overrideScoreTablebase(service)) {
            Score score = new Score();
            score.refresh(board, GameStateEnum.PLAY);
        }

        assertThat(service.getProbedFens()).isEmpty();
    }

    @Test
    void tablebaseCentipawnRespectsSideToMove() {
        TablebaseResult win = new TablebaseResult(SyzygyWdl.WIN, OptionalInt.of(1), OptionalInt.empty());
        TablebaseResult loss = new TablebaseResult(SyzygyWdl.LOSS, OptionalInt.of(1), OptionalInt.empty());

        assertThat(Score.tablebaseToCentipawn(win, true)).isGreaterThan(0);
        assertThat(Score.tablebaseToCentipawn(win, false)).isLessThan(0);

        assertThat(Score.tablebaseToCentipawn(loss, true)).isLessThan(0);
        assertThat(Score.tablebaseToCentipawn(loss, false)).isGreaterThan(0);
    }

    @Test
    void scoreProbesWhenBoardWithinServiceLimit() {
        String fen = "6k1/8/8/8/8/8/PPP5/6K1 w - - 0 1";
        BitBoard board = FEN.translateFENtoBitBoard(fen);
        SyzygyProbeResult probe = new SyzygyProbeResult(SyzygyWdl.DRAW, OptionalInt.of(0), OptionalInt.empty());
        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(Map.of(fen, probe), 6);

        try (TablebaseTestSupport.TablebaseServiceRestorer restorer = TablebaseTestSupport.overrideScoreTablebase(service)) {
            Score score = new Score();
            score.refresh(board, GameStateEnum.PLAY);
        }

        assertThat(service.getProbedFens()).containsExactly(fen);
    }
}
