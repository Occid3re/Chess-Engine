package julius.game.chessengine.syzygy;

import julius.game.chessengine.board.MoveHelper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TablesTest {

    @Test
    void convertsSyzygySquaresToEngineIndices() {
        assertThat(Tables.toEngineIndex(1)).isEqualTo(MoveHelper.convertStringToIndex("a8"));
        assertThat(Tables.toEngineIndex(8)).isEqualTo(MoveHelper.convertStringToIndex("h8"));
        assertThat(Tables.toEngineIndex(26)).isEqualTo(MoveHelper.convertStringToIndex("b5"));
        assertThat(Tables.toEngineIndex(57)).isEqualTo(MoveHelper.convertStringToIndex("a1"));
        assertThat(Tables.toEngineIndex(64)).isEqualTo(MoveHelper.convertStringToIndex("h1"));
    }

    @Test
    void returnsMinusOneForInvalidSyzygySquares() {
        assertThat(Tables.toEngineIndex(0)).isEqualTo(-1);
        assertThat(Tables.toEngineIndex(65)).isEqualTo(-1);
        assertThat(Tables.toEngineIndex(-4)).isEqualTo(-1);
    }
}
