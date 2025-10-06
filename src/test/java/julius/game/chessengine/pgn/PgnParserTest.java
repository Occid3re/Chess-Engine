package julius.game.chessengine.pgn;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.utils.MoveStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PgnParserTest {

    @Test
    void parseToPgnProducesMetadataAndSanMoves() {
        Engine engine = new Engine();
        engine.startNewGame();
        MoveStack line = new MoveStack();

        pushMove(engine, line, "e2", "e4");
        pushMove(engine, line, "e7", "e5");
        pushMove(engine, line, "d1", "h5");
        pushMove(engine, line, "b8", "c6");
        pushMove(engine, line, "f1", "c4");
        pushMove(engine, line, "g8", "f6");
        pushMove(engine, line, "h5", "f7");

        PGN result = new PgnParser(line).parseToPgn();
        String text = result.getPgn();

        assertTrue(text.startsWith("[Event \"Alieknek testing\"]\n[Site \"Neulengbach\"]\n[Date \""));
        assertTrue(text.contains("\n[Round \"1\"]\n[White \"Alieknek\"]\n[Black \"Alieknek\"]\n[Result \"-\"]\n\n"));

        int movesStart = text.indexOf("\n\n");
        assertNotEquals(-1, movesStart, "PGN text should contain a blank line before moves");
        String moves = text.substring(movesStart + 2);
        assertEquals("1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7#", moves);
    }

    @Test
    void parseToPgnDisambiguatesKnightsByFileWhenNeeded() {
        Engine engine = new Engine();
        engine.startNewGame();
        MoveStack line = new MoveStack();

        pushMove(engine, line, "g1", "f3");
        pushMove(engine, line, "d7", "d5");
        pushMove(engine, line, "d2", "d4");
        pushMove(engine, line, "g8", "f6");
        pushMove(engine, line, "b1", "d2");

        PGN result = new PgnParser(line).parseToPgn();
        String moves = result.getPgn().substring(result.getPgn().indexOf("\n\n") + 2);
        assertEquals("1. Nf3 d5 2. d4 Nf6 3. Nbd2", moves);
        assertFalse(moves.contains("N1d2"), "Rank-based disambiguation must not be used when file is required");
    }

    private void pushMove(Engine engine, MoveStack line, String from, String to) {
        pushMove(engine, line, from, to, 0);
    }

    private void pushMove(Engine engine, MoveStack line, String from, String to, int promotionPieceBits) {
        IntArrayList legalMoves = engine.getAllLegalMoves();
        int fromIndex = MoveHelper.convertStringToIndex(from);
        int toIndex = MoveHelper.convertStringToIndex(to);
        for (int i = 0; i < legalMoves.size(); i++) {
            int move = legalMoves.getInt(i);
            if (MoveHelper.deriveFromIndex(move) == fromIndex &&
                    MoveHelper.deriveToIndex(move) == toIndex &&
                    MoveHelper.derivePromotionPieceTypeBits(move) == promotionPieceBits) {
                engine.performMove(move);
                line.push(move);
                return;
            }
        }
        fail("Move " + from + to + " not legal in current position");
    }
}
