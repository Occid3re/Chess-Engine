package julius.game.chessengine.syzygy;

import julius.game.chessengine.syzygy.bridge.SyzygyConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TablesTest {

    private static Tables tables;
    private static Method decodeMethod;

    @BeforeAll
    static void setUpReflectionAccess() throws Exception {
        Constructor<Tables> constructor = Tables.class.getDeclaredConstructor(String.class, int.class, int.class);
        constructor.setAccessible(true);
        tables = constructor.newInstance("test", 6, 6);

        decodeMethod = Tables.class.getDeclaredMethod("decodeRecommendedMove", int.class);
        decodeMethod.setAccessible(true);
    }

    @Test
    void decodeRecommendedMoveUsesZeroBasedSquares() {
        int dtzRaw = (SyzygyConstants.TB_WIN << SyzygyConstants.TB_RESULT_WDL_SHIFT)
                | (7 << SyzygyConstants.TB_RESULT_TO_SHIFT)
                | (15 << SyzygyConstants.TB_RESULT_FROM_SHIFT);

        Optional<SyzygyMove> move = invokeDecode(dtzRaw);

        assertThat(move).isPresent();
        assertThat(move.get().fromIndex()).isEqualTo(15);
        assertThat(move.get().toIndex()).isEqualTo(7);
    }

    @Test
    void decodeRecommendedMoveAllowsASquareIndices() {
        int dtzRaw = (SyzygyConstants.TB_DRAW << SyzygyConstants.TB_RESULT_WDL_SHIFT)
                | (8 << SyzygyConstants.TB_RESULT_TO_SHIFT)
                | (0 << SyzygyConstants.TB_RESULT_FROM_SHIFT);

        Optional<SyzygyMove> move = invokeDecode(dtzRaw);

        assertThat(move).isPresent();
        assertThat(move.get().fromIndex()).isEqualTo(0);
        assertThat(move.get().toIndex()).isEqualTo(8);
    }

    @SuppressWarnings("unchecked")
    private Optional<SyzygyMove> invokeDecode(int dtzRaw) {
        try {
            return (Optional<SyzygyMove>) decodeMethod.invoke(tables, dtzRaw);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(ex.getCause());
        } catch (IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }
}

