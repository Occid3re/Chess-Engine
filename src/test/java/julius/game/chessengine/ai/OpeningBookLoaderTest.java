package julius.game.chessengine.ai;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class OpeningBookLoaderTest {

    private static final String OPENING_DIR_PROP = "chess.opening.dir";
    private static final String CACHE_PROP = "chess.opening.cache";
    private static final Path CACHE_PATH = Paths.get("target", "test-opening-cache.json");

    @AfterEach
    void cleanUp() throws IOException {
        System.clearProperty(OPENING_DIR_PROP);
        System.clearProperty(CACHE_PROP);
        Files.deleteIfExists(CACHE_PATH);
    }

    @Test
    void indexesPgnDirectoryAndKeepsTranspositions() throws IOException {
        Path openingDir = Paths.get("src/test/resources/opening").toAbsolutePath();
        Files.deleteIfExists(CACHE_PATH);
        System.setProperty(OPENING_DIR_PROP, openingDir.toString());
        System.setProperty(CACHE_PROP, CACHE_PATH.toString());

        OpeningBookLoader loader = new OpeningBookLoader();
        OpeningBookLoader.Result first = loader.load();

        assertFalse(first.entries().isEmpty(), "expected PGNs to populate the book");
        assertFalse(first.fromCache());

        Engine engine = new Engine();
        long startHash = engine.getBoardStateHash();

        List<OpeningEntry> startEntries = first.entries().get(startHash);
        assertNotNull(startEntries);
        assertEquals(2, startEntries.size());

        Map<String, OpeningEntry> byName = startEntries.stream()
                .collect(Collectors.toMap(OpeningEntry::openingName, entry -> entry));

        OpeningEntry ruy = byName.get("ruy lopez");
        OpeningEntry piano = byName.get("gioco piano");
        assertNotNull(ruy);
        assertNotNull(piano);
        assertEquals(1, ruy.ply());
        assertEquals(1, piano.ply());
        assertMoveEquals(ruy.move(), "e2", "e4");
        assertMoveEquals(piano.move(), "e2", "e4");
        assertFalse(ruy.continuation().isEmpty());
        assertMoveEquals(ruy.continuation().get(0), "e7", "e5");

        // Play the shared moves to reach the third-move position (white to move)
        engine.performMove(ruy.move());
        engine.performMove(ruy.continuation().get(0));
        engine.performMove(ruy.continuation().get(1));
        engine.performMove(ruy.continuation().get(2));
        long sharedHash = engine.getBoardStateHash();

        List<OpeningEntry> sharedEntries = first.entries().get(sharedHash);
        assertNotNull(sharedEntries);
        assertEquals(2, sharedEntries.size());

        Map<String, OpeningEntry> sharedByName = sharedEntries.stream()
                .collect(Collectors.toMap(OpeningEntry::openingName, entry -> entry));
        assertMoveEquals(sharedByName.get("ruy lopez").move(), "f1", "b5");
        assertMoveEquals(sharedByName.get("gioco piano").move(), "f1", "c4");

        OpeningBookLoader.Result second = loader.load();
        assertTrue(second.fromCache());
        assertEquals(first.entries(), second.entries());
    }

    private void assertMoveEquals(int move, String expectedFrom, String expectedTo) {
        assertEquals(expectedFrom, MoveHelper.convertIndexToString(MoveHelper.deriveFromIndex(move)));
        assertEquals(expectedTo, MoveHelper.convertIndexToString(MoveHelper.deriveToIndex(move)));
    }
}
