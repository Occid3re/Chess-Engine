package julius.game.chessengine.ai;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertSame;

class HeuristicsSnapshotPoolTest {

    @Test
    void snapshotReuseAvoidsReallocatingBuffers() throws Exception {
        Class<?> heuristicsClass = Class.forName("julius.game.chessengine.ai.AI$Heuristics");
        Constructor<?> heuristicsCtor = heuristicsClass.getDeclaredConstructor(int.class);
        heuristicsCtor.setAccessible(true);
        Object heuristics = heuristicsCtor.newInstance(8);

        Class<?> poolClass = Class.forName("julius.game.chessengine.ai.AI$Heuristics$SnapshotPool");
        Constructor<?> poolCtor = poolClass.getDeclaredConstructor();
        poolCtor.setAccessible(true);
        Object pool = poolCtor.newInstance();

        Class<?> snapshotClass = Class.forName("julius.game.chessengine.ai.AI$Heuristics$Snapshot");

        Method borrowMethod = poolClass.getDeclaredMethod("borrow");
        borrowMethod.setAccessible(true);

        Method populateMethod = heuristicsClass.getDeclaredMethod("populateSnapshot", snapshotClass, int.class);
        populateMethod.setAccessible(true);

        Method closeMethod = snapshotClass.getDeclaredMethod("close");
        closeMethod.setAccessible(true);

        Object snapshot1 = borrowMethod.invoke(pool);
        populateMethod.invoke(heuristics, snapshot1, 8);

        Field killersField = snapshotClass.getDeclaredField("killers");
        killersField.setAccessible(true);
        Field historyField = snapshotClass.getDeclaredField("history");
        historyField.setAccessible(true);
        Field counterField = snapshotClass.getDeclaredField("counter");
        counterField.setAccessible(true);

        int[][] killersRef1 = (int[][]) killersField.get(snapshot1);
        int[][] historyRef1 = (int[][]) historyField.get(snapshot1);
        int[][] counterRef1 = (int[][]) counterField.get(snapshot1);

        closeMethod.invoke(snapshot1);

        Object snapshot2 = borrowMethod.invoke(pool);
        populateMethod.invoke(heuristics, snapshot2, 8);

        int[][] killersRef2 = (int[][]) killersField.get(snapshot2);
        int[][] historyRef2 = (int[][]) historyField.get(snapshot2);
        int[][] counterRef2 = (int[][]) counterField.get(snapshot2);

        assertSame(killersRef1, killersRef2, "Killers matrix should be reused");
        assertSame(historyRef1, historyRef2, "History matrix should be reused");
        assertSame(counterRef1, counterRef2, "Counter matrix should be reused");

        closeMethod.invoke(snapshot2);
    }
}

