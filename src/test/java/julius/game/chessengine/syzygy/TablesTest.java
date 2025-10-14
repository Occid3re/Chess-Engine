package julius.game.chessengine.syzygy;

import julius.game.chessengine.syzygy.bridge.SyzygyConstants;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TablesTest {

    @Test
    void decodeRecommendedMoveUsesZeroBasedIndices() {
        int raw = buildDtzRaw(
                SyzygyConstants.TB_WIN,
                33,
                41,
                1,
                SyzygyConstants.TB_PROMOTES_NONE
        );

        Optional<SyzygyMove> decoded = Tables.decodeRecommendedMove(raw);

        assertThat(decoded).isPresent();
        assertThat(decoded.get().fromIndex()).isEqualTo(33);
        assertThat(decoded.get().toIndex()).isEqualTo(41);
        assertThat(decoded.get().promotionPieceTypeBits()).isZero();
    }

    @Test
    void decodeRecommendedMoveSupportsAFileSquares() {
        int raw = buildDtzRaw(
                SyzygyConstants.TB_WIN,
                0,
                8,
                2,
                SyzygyConstants.TB_PROMOTES_NONE
        );

        Optional<SyzygyMove> decoded = Tables.decodeRecommendedMove(raw);

        assertThat(decoded).isPresent();
        assertThat(decoded.get().fromIndex()).isEqualTo(0);
        assertThat(decoded.get().toIndex()).isEqualTo(8);
    }

    private static int buildDtzRaw(int wdl, int from, int to, int dtz, int promote) {
        return (wdl << SyzygyConstants.TB_RESULT_WDL_SHIFT)
                | (to << SyzygyConstants.TB_RESULT_TO_SHIFT)
                | (from << SyzygyConstants.TB_RESULT_FROM_SHIFT)
                | (promote << SyzygyConstants.TB_RESULT_PROMOTES_SHIFT)
                | (dtz << SyzygyConstants.TB_RESULT_DTZ_SHIFT);
    }
}

