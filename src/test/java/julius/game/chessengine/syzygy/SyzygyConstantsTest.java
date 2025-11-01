package julius.game.chessengine.syzygy;

import julius.game.chessengine.syzygy.bridge.SyzygyConstants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyzygyConstantsTest {

    @Test
    void winDrawLossExtractsEncodedValue() {
        int encodedWin = SyzygyConstants.TB_WIN << SyzygyConstants.TB_RESULT_WDL_SHIFT;
        int encodedCursed = SyzygyConstants.TB_CURSED_WIN << SyzygyConstants.TB_RESULT_WDL_SHIFT;

        assertThat(SyzygyConstants.winDrawLoss(encodedWin)).isEqualTo(SyzygyConstants.TB_WIN);
        assertThat(SyzygyConstants.winDrawLoss(encodedCursed)).isEqualTo(SyzygyConstants.TB_CURSED_WIN);
        assertThat(SyzygyConstants.winDrawLoss(SyzygyConstants.TB_RESULT_FAILED))
                .isEqualTo(SyzygyConstants.TB_RESULT_FAILED);
    }

    @Test
    void distanceToZeroSignExtends() {
        int positive = 7 << SyzygyConstants.TB_RESULT_DTZ_SHIFT;
        int negative = 0xFFB << SyzygyConstants.TB_RESULT_DTZ_SHIFT; // -5 encoded in 12-bit two's complement

        assertThat(SyzygyConstants.distanceToZero(positive)).isEqualTo(7);
        assertThat(SyzygyConstants.distanceToZero(negative)).isEqualTo(-5);
    }
}
