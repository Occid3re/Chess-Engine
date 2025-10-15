package julius.game.chessengine.syzygy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TablesTest {

    @Test
    void encodeEnPassantSquareUsesZeroSentinel() {
        assertThat(Tables.encodeEnPassantSquare(-1)).isZero();
        assertThat(Tables.encodeEnPassantSquare(0)).isZero();
        assertThat(Tables.encodeEnPassantSquare(44)).isEqualTo(44);
    }

    @Test
    void encodeEnPassantSquareClampsValuesBeyondBoard() {
        assertThat(Tables.encodeEnPassantSquare(64)).isZero();
        assertThat(Tables.encodeEnPassantSquare(Integer.MAX_VALUE)).isZero();
    }
}
