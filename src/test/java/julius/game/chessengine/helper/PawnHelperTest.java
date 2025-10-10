package julius.game.chessengine.helper;

import org.junit.jupiter.api.Test;

import static julius.game.chessengine.helper.BitHelper.bitIndex;
import static org.assertj.core.api.Assertions.assertThat;

class PawnHelperTest {

    @Test
    void countCenterPawnsCountsWhitePawnsOnD4AndE4() {
        long whiteCenterPawns = (1L << bitIndex('d', 4)) | (1L << bitIndex('e', 4));

        assertThat(PawnHelper.countCenterPawns(whiteCenterPawns)).isEqualTo(2);
    }

    @Test
    void countCenterPawnsCountsBlackPawnsOnD5AndE5() {
        long blackCenterPawns = (1L << bitIndex('d', 5)) | (1L << bitIndex('e', 5));

        assertThat(PawnHelper.countCenterPawns(blackCenterPawns)).isEqualTo(2);
    }
}
