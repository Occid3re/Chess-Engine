package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class HeuristicsSnapshotPoolTest {

    @Test
    void snapshotsAreReusedFromPool() throws Exception {
        Engine engine = new Engine();
        AI ai = new AI(engine);

        Method capture = AI.class.getDeclaredMethod("captureHeuristicsSnapshot", int.class);
        capture.setAccessible(true);

        Object snapshot1 = invokeCapture(ai, capture, ai.getMaxDepth());
        int[][] history1 = extractIntMatrix(snapshot1, "history");
        int[][] killers1 = extractIntMatrix(snapshot1, "killers");

        ((AutoCloseable) snapshot1).close();

        Object snapshot2 = invokeCapture(ai, capture, ai.getMaxDepth());
        int[][] history2 = extractIntMatrix(snapshot2, "history");
        int[][] killers2 = extractIntMatrix(snapshot2, "killers");

        assertSame(snapshot1, snapshot2, "Snapshots should be recycled by the pool");
        assertSame(history1, history2, "History buffer should be reused");
        assertSame(killers1, killers2, "Killer move buffer should be reused");

        ((AutoCloseable) snapshot2).close();
    }

    private static Object invokeCapture(AI ai, Method capture, int depth)
            throws InvocationTargetException, IllegalAccessException {
        Object snapshot = capture.invoke(ai, depth);
        assertNotNull(snapshot, "Snapshot should not be null");
        assertTrue(snapshot instanceof AutoCloseable, "Snapshot must be AutoCloseable");
        return snapshot;
    }

    private static int[][] extractIntMatrix(Object snapshot, String fieldName)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = snapshot.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (int[][]) field.get(snapshot);
    }
}
