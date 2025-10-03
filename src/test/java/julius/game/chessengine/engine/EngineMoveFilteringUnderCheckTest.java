package julius.game.chessengine.engine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.board.MoveHelper;
import org.junit.jupiter.api.Test;
import testsupport.TestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EngineMoveFilteringUnderCheckTest {

    private record EngineHarness(Engine engine, CountingBitBoard board) {
    }

    @Test
    void singleSliderCheckFiltersToBlocksAndCaptures() throws Exception {
        EngineHarness harness = setup("4rk2/8/8/8/8/2N5/8/4K3 w - - 0 1");
        CountingBitBoard board = harness.board();

        IntArrayList pseudo = new IntArrayList();
        board.generateAllPossibleMovesInto(board.isWhitesTurn(), pseudo);
        int pseudoCount = pseudo.size();

        board.resetCounter();
        IntArrayList legal = harness.engine().getAllLegalMoves();

        Set<String> moves = toMoveSet(legal);

        assertEquals(Set.of("e1d1", "e1f1", "e1d2", "e1f2", "c3e2"), moves);
        assertTrue(board.getCounter() < pseudoCount, "expected fewer legality checks after filtering");
    }

    @Test
    void doubleCheckAllowsOnlyKingMoves() throws Exception {
        EngineHarness harness = setup("4k3/8/8/8/1b6/3n4/8/4K3 w - - 0 1");
        CountingBitBoard board = harness.board();

        IntArrayList pseudo = new IntArrayList();
        board.generateAllPossibleMovesInto(board.isWhitesTurn(), pseudo);
        int pseudoCount = pseudo.size();

        board.resetCounter();
        IntArrayList legal = harness.engine().getAllLegalMoves();

        Set<String> moves = toMoveSet(legal);

        assertEquals(Set.of("e1d1", "e1f1", "e1e2"), moves);
        assertTrue(board.getCounter() < pseudoCount, "double-check should skip non-king moves");
        assertEquals(moves.size(), board.getCounter(), "only king moves should reach legality testing");
    }

    @Test
    void enPassantCaptureRemainsAvailableUnderCheck() throws Exception {
        EngineHarness harness = setup("6kb/8/8/4KpP1/8/8/8/8 w - f6 0 1");
        CountingBitBoard board = harness.board();

        IntArrayList pseudo = new IntArrayList();
        board.generateAllPossibleMovesInto(board.isWhitesTurn(), pseudo);
        int pseudoCount = pseudo.size();

        board.resetCounter();
        IntArrayList legal = harness.engine().getAllLegalMoves();

        Set<String> moves = toMoveSet(legal);

        assertTrue(moves.contains("g5f6"), "en passant capture should be legal response");
        assertTrue(board.getCounter() < pseudoCount, "masking should skip irrelevant moves");
    }

    private EngineHarness setup(String fen) throws Exception {
        Engine engine = new Engine();
        BitBoard base = FEN.translateFENtoBitBoard(fen);
        CountingBitBoard board = new CountingBitBoard(base);
        GameState state = new GameState(board);

        TestUtils.writeField(engine, "bitBoard", board);
        TestUtils.writeField(engine, "gameState", state);
        TestUtils.writeField(engine, "legalMovesNeedUpdate", true);
        TestUtils.writeField(engine, "cachedLegalMovesHash", Long.MIN_VALUE);
        TestUtils.writeField(engine, "cachedLegalMoveCount", 0);
        TestUtils.writeField(engine, "cachedLegalMoves", new int[0]);

        return new EngineHarness(engine, board);
    }

    private String toCoordinateMove(int move) {
        int from = MoveHelper.deriveFromIndex(move);
        int to = MoveHelper.deriveToIndex(move);
        return MoveHelper.convertIndexToString(from) + MoveHelper.convertIndexToString(to);
    }

    private Set<String> toMoveSet(IntArrayList moves) {
        Set<String> result = moves.isEmpty() ? Set.of() : new java.util.HashSet<>();
        for (int i = 0; i < moves.size(); i++) {
            result.add(toCoordinateMove(moves.getInt(i)));
        }
        return result;
    }

    private static final class CountingBitBoard extends BitBoard {
        private int counter;

        CountingBitBoard(BitBoard other) {
            super(other);
        }

        @Override
        public boolean isMoveLegalFast(int move, PinState pinState) {
            counter++;
            return super.isMoveLegalFast(move, pinState);
        }

        void resetCounter() {
            counter = 0;
        }

        int getCounter() {
            return counter;
        }
    }
}

