package julius.game.chessengine.syzygy;

import julius.game.chessengine.syzygy.bridge.SyzygyBridge;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

class SyzygyBridgeThreadSafetyTest {

    private static final Class<?>[] WDL_SIGNATURE = {
            long.class, long.class, long.class, long.class, long.class,
            long.class, long.class, long.class, int.class, boolean.class
    };

    private static final Class<?>[] DTZ_SIGNATURE = {
            long.class, long.class, long.class, long.class, long.class,
            long.class, long.class, long.class, int.class, int.class, boolean.class
    };

    @Test
    void probeMethodsAreSynchronized() throws NoSuchMethodException {
        Method wdl = SyzygyBridge.class.getDeclaredMethod("probeSyzygyWDL", WDL_SIGNATURE);
        Method dtz = SyzygyBridge.class.getDeclaredMethod("probeSyzygyDTZ", DTZ_SIGNATURE);

        assertThat(Modifier.isSynchronized(wdl.getModifiers()))
                .as("probeSyzygyWDL must be synchronized to avoid concurrent native access")
                .isTrue();
        assertThat(Modifier.isSynchronized(dtz.getModifiers()))
                .as("probeSyzygyDTZ must be synchronized to avoid concurrent native access")
                .isTrue();
    }
}
