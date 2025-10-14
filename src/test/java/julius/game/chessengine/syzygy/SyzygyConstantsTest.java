package julius.game.chessengine.syzygy;

import julius.game.chessengine.syzygy.bridge.SyzygyConstants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyzygyConstantsTest {

    @Test
    void distanceToZeroDecodesPositiveValues() {
        int encoded = encodeDtz(5) | SyzygyConstants.TB_WIN;
        assertThat(SyzygyConstants.distanceToZero(encoded)).isEqualTo(5);
    }

    @Test
    void distanceToZeroSignExtendsNegativeValues() {
        int encoded = encodeDtz(-1) | SyzygyConstants.TB_WIN;
        assertThat(SyzygyConstants.distanceToZero(encoded)).isEqualTo(-1);

        int deeper = encodeDtz(-33) | SyzygyConstants.TB_CURSED_WIN;
        assertThat(SyzygyConstants.distanceToZero(deeper)).isEqualTo(-33);
    }

    @Test
    void winDrawLossMatchesEncodedValue() {
        int encoded = encodeDtz(0) | SyzygyConstants.TB_CURSED_WIN;
        assertThat(SyzygyConstants.winDrawLoss(encoded)).isEqualTo(SyzygyConstants.TB_CURSED_WIN);
    }

    private static int encodeDtz(int dtz) {
        return (dtz & 0xFFF) << SyzygyConstants.TB_RESULT_DTZ_SHIFT;
    }
}
