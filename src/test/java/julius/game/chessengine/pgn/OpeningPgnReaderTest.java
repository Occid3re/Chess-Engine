package julius.game.chessengine.pgn;

import julius.game.chessengine.engine.Engine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpeningPgnReaderTest {

    private final OpeningPgnReader reader = new OpeningPgnReader();

    @Test
    void shouldParseMultipleGamesAndProvideMoveHashes() {
        String pgn = """
                [Event \"Ruy Lopez\"]
                [Site \"Somewhere\"]
                [Date \"2024.01.01\"]
                [Round \"1\"]
                [White \"Alpha\"]
                [Black \"Beta\"]
                [Result \"1-0\"]

                1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 { comment } 4. Ba4 (4... Be7?!) Nf6
                5. O-O Be7 6. Re1 b5 ; inline comment
                7. Bb3 d6 8. c3 0-0 9. h3 Nb8 10. d4 Nbd7 1-0

                [Event \"Promotion test\"]
                [Site \"-\"]
                [Date \"2024.01.02\"]
                [Round \"1\"]
                [White \"Gamma\"]
                [Black \"Delta\"]
                [Result \"*\"]

                1. a4 h5 2. a5 h4 3. a6 h3 4. axb7 hxg2 5. bxa8=Q+ gxf1=Q+ *
                """;

        List<OpeningPgnReader.ParsedGame> games = reader.parse(pgn);

        assertEquals(2, games.size(), "Expected two games to be parsed");

        OpeningPgnReader.ParsedGame first = games.get(0);
        assertEquals("Ruy Lopez", first.headers().get("Event"));
        assertEquals("Alpha", first.headers().get("White"));
        assertEquals("Beta", first.headers().get("Black"));

        assertEquals(20, first.moves().size(), "First game should have ten full moves");
        assertEquals(first.moves().size(), first.hashes().size(), "Each move should have a pre-move hash");

        Engine engine = new Engine();
        engine.startNewGame();
        for (int i = 0; i < first.moves().size(); i++) {
            int move = first.moves().get(i);
            long expectedHash = first.hashes().get(i);
            assertEquals(engine.getBoardStateHash(), expectedHash, "Hash should match engine state before move");
            assertTrue(engine.getAllLegalMoves().contains(move), "Move should be legal in current position");
            engine.performMove(move);
        }

        OpeningPgnReader.ParsedGame second = games.get(1);
        assertEquals(10, second.moves().size(), "Promotion game should have five full moves");
        assertEquals(second.moves().size(), second.hashes().size());

        engine.startNewGame();
        for (int move : second.moves()) {
            assertTrue(engine.getAllLegalMoves().contains(move));
            engine.performMove(move);
        }
    }

    @Test
    void shouldParseFromBytesAndGenerateStableFingerprint() {
        String content = "[Event \"Minimal\"]\n\n1. e4 e5 *";
        List<OpeningPgnReader.ParsedGame> games = reader.parse(content.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, games.size());

        String fingerprint = reader.fingerprint(List.of(
                "alpha".getBytes(StandardCharsets.UTF_8),
                "beta".getBytes(StandardCharsets.UTF_8)
        ));
        assertEquals(64, fingerprint.length(), "SHA-256 fingerprint should be 64 hex characters");
        assertEquals(fingerprint, reader.fingerprint(List.of(
                "alpha".getBytes(StandardCharsets.UTF_8),
                "beta".getBytes(StandardCharsets.UTF_8)
        )));
    }
}
