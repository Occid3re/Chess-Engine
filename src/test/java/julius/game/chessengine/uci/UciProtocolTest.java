package julius.game.chessengine.uci;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration style tests verifying basic UCI command exchanges over
 * standard input/output.
 */
public class UciProtocolTest {

    @Test
    void testUciIsreadyPositionGoQuit() throws Exception {
        String commands = String.join("\n",
                "uci",
                "isready",
                "position startpos",
                "go movetime 200",
                "isready",
                "quit") + "\n";

        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(commands.getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(out, true));

            UciMain.main(new String[0]);

            // wait until bestmove is printed or timeout after ~2s
            for (int i = 0; i < 40; i++) {
                if (out.toString().contains("bestmove")) {
                    break;
                }
                Thread.sleep(50);
            }
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }

        String output = out.toString();
        assertTrue(output.contains("id name JuliusChessEngine"), output);
        assertTrue(output.contains("uciok"), output);
        assertTrue(output.contains("readyok"), output);
        assertTrue(output.contains("bestmove"), output);
    }

    @Test
    void testQuitReturnsFalse() {
        UciHandler handler = new UciHandler();
        assertFalse(handler.handle("quit"));
    }
}
