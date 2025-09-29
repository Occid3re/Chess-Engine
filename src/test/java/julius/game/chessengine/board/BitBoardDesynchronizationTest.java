package julius.game.chessengine.board;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static julius.game.chessengine.board.BoardStateAssertions.assertBoardConsistent;
import static julius.game.chessengine.board.BoardStateAssertions.describeMove;

class BitBoardDesynchronizationTest {

    private static final long DFS_VISIT_LIMIT = 1_000_000L;

    private record Scenario(String label, String fen, int depth) {
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("Initial position", null, 3),
            new Scenario("Logged queen-capture position",
                    "8/8/4q1k1/4p1p1/5p2/7P/4K3/8 b - - 2 43", 5),
            new Scenario("Logged king-capture position",
                    "8/8/3q4/4pk2/5pP1/8/4K3/8 b - - 0 44", 5)
    );

    @Test
    void exhaustiveLegalMoveExplorationDetectsBoardStateDesync() {
        for (Scenario scenario : SCENARIOS) {
            BitBoard board = scenario.fen() == null
                    ? new BitBoard()
                    : FEN.translateFENtoBitBoard(scenario.fen());

            String label = scenario.label();
            long initialHash = board.getBoardStateHash();
            assertBoardConsistent(board, label + " (initial state)");

            long[] visited = new long[1];
            explore(board, scenario.depth(), label, visited);

            assertTrue(visited[0] > 0, label + " exploration should visit at least one position");
            assertTrue(visited[0] <= DFS_VISIT_LIMIT,
                    label + " exploration exceeded visit limit: " + visited[0]);

            assertEquals(initialHash, board.getBoardStateHash(),
                    label + " board hash should be restored after exhaustive exploration");
            assertBoardConsistent(board, label + " (after exhaustive exploration)");
        }
    }

    @Test
    void randomPlayoutsMaintainBoardConsistency() {
        long seedBase = 0xC0FFEE1234L;
        for (int i = 0; i < SCENARIOS.size(); i++) {
            Scenario scenario = SCENARIOS.get(i);
            BitBoard board = scenario.fen() == null
                    ? new BitBoard()
                    : FEN.translateFENtoBitBoard(scenario.fen());

            String label = scenario.label();
            long seed = seedBase + i * 0x9E3779B97F4A7C15L;
            runRandomPlayout(board, 256, seed, label);
        }
    }

    private void explore(BitBoard board, int depth, String label, long[] visited) {
        if (visited[0]++ > DFS_VISIT_LIMIT) {
            fail(label + " exceeded exploration limit of " + DFS_VISIT_LIMIT + " positions");
        }

        assertBoardConsistent(board, label + " depth=" + depth + " (before branching)");

        if (depth == 0) {
            return;
        }

        BitBoard.MoveGenResult gen = board.generateAllPossibleMovesWithPins(board.isWhitesTurn());
        IntArrayList pseudoMoves = gen.moves();
        List<Integer> legalMoves = new ArrayList<>(pseudoMoves.size());
        for (int idx = 0; idx < pseudoMoves.size(); idx++) {
            int move = pseudoMoves.getInt(idx);
            if (board.isMoveLegalFast(move, gen.pinState())) {
                legalMoves.add(move);
            }
        }

        if (legalMoves.isEmpty()) {
            return;
        }

        for (int move : legalMoves) {
            board.performMove(move);
            assertBoardConsistent(board, label + " depth=" + depth + " after " + describeMove(move));
            explore(board, depth - 1, label, visited);
            board.undoMove(move);
            assertBoardConsistent(board, label + " depth=" + depth + " after undo " + describeMove(move));
        }
    }

    private void runRandomPlayout(BitBoard board, int maxPlies, long seed, String label) {
        Random random = new Random(seed);
        List<Integer> history = new ArrayList<>();
        long initialHash = board.getBoardStateHash();
        assertBoardConsistent(board, label + " (random playout start)");

        for (int ply = 0; ply < maxPlies; ply++) {
            BitBoard.MoveGenResult gen = board.generateAllPossibleMovesWithPins(board.isWhitesTurn());
            IntArrayList pseudoMoves = gen.moves();
            List<Integer> legalMoves = new ArrayList<>(pseudoMoves.size());
            for (int idx = 0; idx < pseudoMoves.size(); idx++) {
                int move = pseudoMoves.getInt(idx);
                if (board.isMoveLegalFast(move, gen.pinState())) {
                    legalMoves.add(move);
                }
            }

            if (legalMoves.isEmpty()) {
                break;
            }

            int move = legalMoves.get(random.nextInt(legalMoves.size()));
            board.performMove(move);
            history.add(move);
            assertBoardConsistent(board, label + " random ply=" + history.size() + " after " + describeMove(move));
        }

        for (int i = history.size() - 1; i >= 0; i--) {
            int move = history.get(i);
            board.undoMove(move);
            assertBoardConsistent(board, label + " undo ply=" + (history.size() - i) + " after " + describeMove(move));
        }

        assertEquals(initialHash, board.getBoardStateHash(),
                label + " board hash should be restored after random playout undo");
        assertBoardConsistent(board, label + " (after random playout)");
    }

}
