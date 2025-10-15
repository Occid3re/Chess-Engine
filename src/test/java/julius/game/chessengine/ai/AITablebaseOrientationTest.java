package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.tuning.AiTuning;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class AITablebaseOrientationTest {

    @Test
    void tablebaseScoresRemainWhiteOrientedWhenBlackToMove() throws Exception {
        Engine engine = new Engine();
        engine.importBoardFromFen("8/8/8/8/8/8/8/K6k b - - 0 1");

        SyzygyProbeResult probeResult = new SyzygyProbeResult(
                SyzygyWdl.LOSS,
                OptionalInt.of(1),
                OptionalInt.empty(),
                Optional.empty()
        );
        SyzygyTablebaseService stubService = stubTablebaseService(probeResult);

        AiTuning tuning = AiTuning.builder()
                .searchThreads(1)
                .lazySmpThreads(1)
                .hashSizeMb(16)
                .maxDepth(1)
                .timeLimitMillis(100)
                .build();

        AI ai = new AI(engine, tuning, stubService);

        Method resolve = AI.class.getDeclaredMethod("resolveTablebaseHit", Engine.class, boolean.class);
        resolve.setAccessible(true);
        @SuppressWarnings("unchecked")
        Optional<?> hit = (Optional<?>) resolve.invoke(ai, engine, false);

        assertThat(hit).as("tablebase probe should resolve").isPresent();
        Object tablebaseHit = hit.get();
        Method scoreAccessor = tablebaseHit.getClass().getDeclaredMethod("score");
        double score = (double) scoreAccessor.invoke(tablebaseHit);

        assertThat(score)
                .as("score should remain positive for white even when isWhite=false")
                .isPositive();

        ai.shutdown();
    }

    private SyzygyTablebaseService stubTablebaseService(SyzygyProbeResult result) {
        try {
            Class<?> clientType = Class.forName("julius.game.chessengine.syzygy.TablebaseClient");
            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    return switch (method.getName()) {
                        case "probe" -> Optional.of(result);
                        case "supportedMaxPieces" -> 7;
                        case "toString" -> "StubTablebaseClient";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                }
            };
            Object proxy = Proxy.newProxyInstance(
                    clientType.getClassLoader(),
                    new Class<?>[]{clientType},
                    handler
            );
            Constructor<SyzygyTablebaseService> ctor = SyzygyTablebaseService.class
                    .getDeclaredConstructor(clientType, int.class);
            ctor.setAccessible(true);
            return ctor.newInstance(proxy, 16);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to create stub tablebase service", ex);
        }
    }
}
