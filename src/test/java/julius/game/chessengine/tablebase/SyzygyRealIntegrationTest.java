package julius.game.chessengine.tablebase;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.syzygy.bridge.SyzygyBridge;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TestSyzygySupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SyzygyRealIntegrationTest {

    private static final String KNIGHT_BISHOP_VS_KING_PAWN_FEN = "3k4/4p3/8/2K5/8/3BN3/8/8 w - - 0 1";

    @Test
    void knightBishopVsKingPawnProbesThroughRealTables() {
        TablebaseTestSupport.assumeSyzygyConfigured();
        Assumptions.assumeTrue(SyzygyBridge.isLibLoaded(), "Syzygy native library not loaded");

        SyzygyTablebaseService service = TestSyzygySupport.maybeCreateServiceFromConfiguration()
                .orElseThrow(() -> new IllegalStateException("Syzygy service not configured despite assumption"));
        service.ensureReady();

        Assumptions.assumeTrue(service.getEffectiveMaxPieces() > 0,
                "Syzygy tablebases were not loaded for the configured directories");

        BitBoard board = FEN.translateFENtoBitBoard(KNIGHT_BISHOP_VS_KING_PAWN_FEN);
        int pieceCount = Long.bitCount(board.getAllPieces());
        Assumptions.assumeTrue(service.getEffectiveMaxPieces() >= pieceCount,
                () -> "Configured Syzygy tables only cover " + service.getEffectiveMaxPieces() + " pieces");

        Optional<SyzygyProbeResult> result = service.probe(board);

        assertThat(result)
                .describedAs("Expected real Syzygy result for %s", KNIGHT_BISHOP_VS_KING_PAWN_FEN)
                .isPresent();

        SyzygyProbeResult probe = result.get();
        assertThat(probe.wdl()).isEqualTo(SyzygyWdl.WIN);
        assertThat(probe.dtz()).hasValue(7);
        assertThat(probe.dtm()).isEmpty();

        assertThat(probe.recommendedMove()).hasValueSatisfying(move -> {
            assertThat(move.fromIndex()).isEqualTo(34);
            assertThat(move.toIndex()).isEqualTo(42);
            assertThat(move.promotionPieceTypeBits()).isZero();
        });

        assertThat(service.getEffectiveMaxPieces())
                .isEqualTo(Math.min(service.getConfiguredMaxPieces(), SyzygyBridge.getSupportedSize()));
    }
}
