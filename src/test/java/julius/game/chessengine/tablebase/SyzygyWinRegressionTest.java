package julius.game.chessengine.tablebase;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SyzygyWinRegressionTest {

    private static final String KNIGHT_BISHOP_VS_KING_PAWN_FEN = "3k4/4p3/8/2K5/8/3BN3/8/8 w - - 0 1";

    @Test
    void whiteWinsKnightBishopVsKingPawnAccordingToSyzygy() {
        TablebaseTestSupport.assumeSyzygyConfigured();

        String directories = TablebaseTestSupport.requireConfiguredSyzygyDirectories();
        SyzygyTablebaseService service = new SyzygyTablebaseService(directories, 7, 16384);

        BitBoard board = FEN.translateFENtoBitBoard(KNIGHT_BISHOP_VS_KING_PAWN_FEN);
        Optional<SyzygyProbeResult> result = service.probe(board);

        assertThat(result)
                .describedAs("Expected Syzygy to provide a result for %s", KNIGHT_BISHOP_VS_KING_PAWN_FEN)
                .isPresent();
        assertThat(result.get().wdl()).isEqualTo(SyzygyWdl.WIN);
    }

}
