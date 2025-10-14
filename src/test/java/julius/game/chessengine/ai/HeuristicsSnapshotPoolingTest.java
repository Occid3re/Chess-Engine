package julius.game.chessengine.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static testsupport.TestUtils.readField;

class HeuristicsSnapshotPoolingTest {

    @Test
    @DisplayName("Heuristics snapshots reuse pooled buffers and refresh contents")
    void snapshotReusesBuffersAndCopiesFreshData() throws Exception {
        Class<?> heuristicsClass = Class.forName("julius.game.chessengine.ai.AI$Heuristics");
        Constructor<?> constructor = heuristicsClass.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        Object heuristics = constructor.newInstance(4);

        int[][] killers = (int[][]) readField(heuristics, "killers");
        int[][] history = (int[][]) readField(heuristics, "history");
        int[][] counter = (int[][]) readField(heuristics, "counter");

        killers[0][0] = 12345;
        killers[1][0] = 67890;
        history[2][3] = 17;
        counter[4][5] = 222;

        Method snapshotMethod = heuristicsClass.getDeclaredMethod("snapshot", int.class);
        snapshotMethod.setAccessible(true);
        Object snapshot1 = snapshotMethod.invoke(heuristics, 4);

        Class<?> snapshotClass = Class.forName("julius.game.chessengine.ai.AI$Heuristics$Snapshot");
        Field killersField = snapshotClass.getDeclaredField("killers");
        Field historyField = snapshotClass.getDeclaredField("history");
        Field counterField = snapshotClass.getDeclaredField("counter");
        killersField.setAccessible(true);
        historyField.setAccessible(true);
        counterField.setAccessible(true);

        int[][] snapshotKillers1 = (int[][]) killersField.get(snapshot1);
        int[][] snapshotHistory1 = (int[][]) historyField.get(snapshot1);
        int[][] snapshotCounter1 = (int[][]) counterField.get(snapshot1);

        assertEquals(12345, snapshotKillers1[0][0]);
        assertEquals(67890, snapshotKillers1[1][0]);
        assertEquals(17, snapshotHistory1[2][3]);
        assertEquals(222, snapshotCounter1[4][5]);

        Method closeMethod = snapshotClass.getDeclaredMethod("close");
        closeMethod.setAccessible(true);
        closeMethod.invoke(snapshot1);

        killers[0][0] = 9999;
        killers[1][0] = 1111;
        history[2][3] = 88;
        counter[4][5] = 333;

        Object snapshot2 = snapshotMethod.invoke(heuristics, 4);
        assertSame(snapshot1, snapshot2, "Snapshot instances should be reused after closing");

        int[][] snapshotKillers2 = (int[][]) killersField.get(snapshot2);
        int[][] snapshotHistory2 = (int[][]) historyField.get(snapshot2);
        int[][] snapshotCounter2 = (int[][]) counterField.get(snapshot2);

        assertEquals(9999, snapshotKillers2[0][0]);
        assertEquals(1111, snapshotKillers2[1][0]);
        assertEquals(88, snapshotHistory2[2][3]);
        assertEquals(333, snapshotCounter2[4][5]);

        closeMethod.invoke(snapshot2);
    }
}
