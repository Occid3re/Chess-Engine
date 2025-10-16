package julius.game.chessengine.tablebase;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.syzygy.SyzygyMove;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.syzygy.TestSyzygyTablebaseService;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class SyzygyMockRegressionTest {

    private static final String KNIGHT_BISHOP_VS_KING_PAWN_FEN = "3k4/4p3/8/2K5/8/3BN3/8/8 w - - 0 1";

    @Test
    void mockedProbeProvidesDeterministicResultForCi() {
        SyzygyProbeResult expected = new SyzygyProbeResult(
                SyzygyWdl.WIN,
                OptionalInt.of(7),
                OptionalInt.empty(),
                Optional.of(new SyzygyMove(34, 42, 0))
        );

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(
                Map.of(KNIGHT_BISHOP_VS_KING_PAWN_FEN, expected)
        );

        BitBoard board = FEN.translateFENtoBitBoard(KNIGHT_BISHOP_VS_KING_PAWN_FEN);

        Optional<SyzygyProbeResult> first = service.probe(board);
        Optional<SyzygyProbeResult> second = service.probe(board);

        assertThat(first).contains(expected);
        assertThat(second).contains(expected);
        assertThat(service.getProbedFens()).containsExactly(KNIGHT_BISHOP_VS_KING_PAWN_FEN);

        TablebaseResult tablebaseResult = TablebaseResult.from(expected);
        assertThat(tablebaseResult.wdl()).isEqualTo(SyzygyWdl.WIN);
        assertThat(tablebaseResult.dtz()).hasValue(7);
        assertThat(tablebaseResult.dtm()).isEmpty();
        assertThat(tablebaseResult.recommendedMove()).hasValueSatisfying(move -> {
            assertThat(move.fromIndex()).isEqualTo(34);
            assertThat(move.toIndex()).isEqualTo(42);
            assertThat(move.isPromotion()).isFalse();
        });

        double evaluation = Score.tablebaseToEvaluation(tablebaseResult, true, 0);
        assertThat(evaluation).isEqualTo((Score.CHECKMATE - 1) / 100.0);
    }

    @Test
    void mockedServiceIntegratesWithScorePipeline() {
        SyzygyProbeResult expected = new SyzygyProbeResult(
                SyzygyWdl.WIN,
                OptionalInt.of(7),
                OptionalInt.empty(),
                Optional.of(new SyzygyMove(34, 42, 0))
        );

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(
                Map.of(KNIGHT_BISHOP_VS_KING_PAWN_FEN, expected)
        );

        BitBoard board = FEN.translateFENtoBitBoard(KNIGHT_BISHOP_VS_KING_PAWN_FEN);

        try (TablebaseTestSupport.TablebaseServiceRestorer restorer = TablebaseTestSupport.overrideScoreTablebase(service)) {
            Score score = Score.initializeScore(board);
            score.refresh(board, GameStateEnum.PLAY);

            assertThat(score.getTablebaseResult()).contains(TablebaseResult.from(expected));
            assertThat(score.getTablebaseCentipawnScore()).hasValue(Score.CHECKMATE - 1);
        }
    }
}
