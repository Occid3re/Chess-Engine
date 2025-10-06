package julius.game.chessengine.pgn;

import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.utils.MoveStack;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpeningPgnReaderTest {

    @Test
    void parseExtractsHeadersMovesAndHashes() {
        String pgn = """
                [Event \"Test Match\"]
                [Site \"Example\"]
                [Result \"1-0\"]

                1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 {Morphy Defense} (3... Nf6 4. O-O) 4. Ba4 Nf6 5. 0-0 Be7 1-0
                """;

        OpeningPgnReader reader = new OpeningPgnReader();
        List<OpeningPgnReader.ParsedGame> games = reader.parse(pgn);

        assertEquals(1, games.size());
        OpeningPgnReader.ParsedGame game = games.get(0);

        Map<String, String> headers = game.headers();
        assertEquals("Test Match", headers.get("Event"));
        assertEquals("Example", headers.get("Site"));
        assertEquals("1-0", headers.get("Result"));

        List<Integer> moves = game.moves();
        assertEquals(10, moves.size(), "Result token should not be treated as a move");

        List<Long> hashes = game.hashes();
        assertEquals(moves.size(), hashes.size());

        Engine engine = new Engine();
        engine.startNewGame();
        for (int move : moves) {
            engine.performMove(move);
        }

        assertEquals(moves.size(), engine.getLine().size(), "Every SAN token should translate to a performed move");

        String san = new PgnParser(new MoveStack(engine.getLine())).parseToPgn().getPgn();
        String movesSan = san.substring(san.indexOf("\n\n") + 2);
        assertEquals("1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. O-O Be7", movesSan);
    }

    @Test
    void fingerprintCombinesResourcesDeterministically() {
        OpeningPgnReader reader = new OpeningPgnReader();
        List<byte[]> resources = List.of(
                "first".getBytes(StandardCharsets.UTF_8),
                "second".getBytes(StandardCharsets.UTF_8));

        String fingerprint = reader.fingerprint(resources);
        assertEquals(64, fingerprint.length());
        assertEquals(fingerprint, reader.fingerprint(resources));

        List<byte[]> reversed = List.of(
                "second".getBytes(StandardCharsets.UTF_8),
                "first".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(fingerprint, reader.fingerprint(reversed), "Order of resources should impact fingerprint");
    }
}
