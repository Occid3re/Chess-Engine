package julius.game.chessengine.engine;

import julius.game.chessengine.board.BitBoard;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GameStateThreefoldRepetitionTest {

    @Test
    void detectsThreefoldRepetitionUsingPrimitiveHistory() {
        BitBoard board = new BitBoard();
        GameState state = new GameState(board);

        long baseHash = board.getBoardStateHash();
        state.recordHash(baseHash);
        state.recordHash(baseHash);

        Assertions.assertTrue(state.isThreefoldRepetition(),
                "Three identical Zobrist keys should trigger a repetition draw candidate.");
        Assertions.assertEquals(baseHash, state.getLastZobrist(),
                "Last Zobrist hash should match the head of the primitive history buffer.");
    }

    @Test
    void removingHashUpdatesRepetitionAndHistoryHead() {
        BitBoard board = new BitBoard();
        GameState state = new GameState(board);

        long baseHash = board.getBoardStateHash();
        long nextHash = baseHash ^ 0x1FL;

        state.recordHash(nextHash);
        Assertions.assertEquals(nextHash, state.getLastZobrist(),
                "Last Zobrist should reflect the most recently pushed hash.");

        state.removeHash(nextHash);

        Assertions.assertEquals(baseHash, state.getLastZobrist(),
                "Removing the latest hash should reveal the previous head without boxing.");
        Assertions.assertEquals(1, state.getRepetition().get(baseHash),
                "Base hash count should remain intact after removing a different head value.");
    }

    @Test
    void clonedGameStateRetainsIndependentPrimitiveHistory() {
        BitBoard board = new BitBoard();
        GameState original = new GameState(board);

        long baseHash = board.getBoardStateHash();
        long alternateHash = baseHash ^ 0xAAFFL;

        original.recordHash(alternateHash);

        GameState clone = new GameState(original);

        long thirdHash = baseHash ^ 0xFF00L;
        original.recordHash(thirdHash);

        Assertions.assertEquals(alternateHash, clone.getLastZobrist(),
                "Clone should preserve the head hash present at clone time.");
        Assertions.assertEquals(thirdHash, original.getLastZobrist(),
                "Original should advance to the newly recorded hash.");

        original.removeHash(thirdHash);
        Assertions.assertEquals(alternateHash, original.getLastZobrist(),
                "After undoing, the original should revert to the previous primitive head.");
        Assertions.assertEquals(alternateHash, clone.getLastZobrist(),
                "Clone history should remain independent from mutations on the original.");
    }
}

