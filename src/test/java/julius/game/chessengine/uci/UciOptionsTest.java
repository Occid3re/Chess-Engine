package julius.game.chessengine.uci;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for UCI option handling.
 */
public class UciOptionsTest {

    @Test
    void testSetOptionsAffectEngine() throws Exception {
        UciHandler handler = new UciHandler();

        // send option commands
        assertTrue(handler.handle("setoption name Threads value 2"));
        assertTrue(handler.handle("setoption name Hash value 32"));
        assertTrue(handler.handle("setoption name Move Overhead value 150"));

        // access AI via reflection
        Field aiField = UciHandler.class.getDeclaredField("ai");
        aiField.setAccessible(true);
        Object aiObj = aiField.get(handler);
        Class<?> aiClass = aiObj.getClass();

        Field searchThreadsField = aiClass.getDeclaredField("searchThreads");
        searchThreadsField.setAccessible(true);
        assertEquals(2, searchThreadsField.getInt(aiObj));

        Field hashField = aiClass.getDeclaredField("hashSizeMb");
        hashField.setAccessible(true);
        assertEquals(32, hashField.getInt(aiObj));

        Field overheadField = UciHandler.class.getDeclaredField("moveOverheadMs");
        overheadField.setAccessible(true);
        assertEquals(150, overheadField.getInt(handler));

        // ensure engine still answers readyok
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out, true));
            handler.handle("isready");
        } finally {
            System.setOut(originalOut);
        }
        assertTrue(out.toString().contains("readyok"));
    }
}
