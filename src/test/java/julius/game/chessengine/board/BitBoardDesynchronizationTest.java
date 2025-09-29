package julius.game.chessengine.board;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.ai.AI;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.tuning.AiTuning;
import julius.game.chessengine.utils.Color;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static julius.game.chessengine.board.MoveHelper.*;
import static org.junit.jupiter.api.Assertions.*;

class BitBoardDesynchronizationTest {

    private static final long DFS_VISIT_LIMIT = 1_000_000L;
    private static final int AUTOPLAY_EVENT_TARGET = 4;
    private static final AiTuning CONCURRENT_TUNING = AiTuning.builder()
            .searchThreads(4)
            .lazySmpThreads(4)
            .maxDepth(5)
            .timeLimitMillis(30L)
            .build();

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

    @Test
    void autoPlayWithConcurrentAiMaintainsConsistency() throws InterruptedException {
        for (Scenario scenario : SCENARIOS) {
            String label = scenario.label();
            InstrumentedEngine engine = new InstrumentedEngine(label + "[main]");
            if (scenario.fen() != null) {
                engine.importBoardFromFen(scenario.fen());
            }

            assertBoardConsistent(engine.getBitBoard(), label + " (before autoplay)");

            // Skip autoplay for terminal positions where no legal moves exist.
            if (engine.getAllLegalMoves().isEmpty()) {
                continue;
            }

            AtomicInteger events = new AtomicInteger();
            engine.setOnPositionChanged(hash -> {
                int index = events.incrementAndGet();
                assertBoardConsistent(engine.getBitBoard(), label + " autoplay event " + index);
            });

            AI ai = new AI(engine, CONCURRENT_TUNING);
            ai.setSearchThreads(CONCURRENT_TUNING.searchThreads());
            ai.setMaxDepth(CONCURRENT_TUNING.maxDepth());
            ai.setTimeLimit(CONCURRENT_TUNING.timeLimitMillis());

            try {
                ai.startAutoPlay(true, true);
                waitForCondition(() -> events.get() >= AUTOPLAY_EVENT_TARGET, 5_000L,
                        label + " autoplay did not reach " + AUTOPLAY_EVENT_TARGET + " board updates");
            } finally {
                ai.stopCalculation();
                ai.shutdown();
                engine.setOnPositionChanged(h -> {
                });
            }

            assertBoardConsistent(engine.getBitBoard(), label + " (after autoplay)");
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

    private static void assertBoardConsistent(BitBoard board, String context) {
        long whiteUnion = board.getWhitePawns()
                | board.getWhiteKnights()
                | board.getWhiteBishops()
                | board.getWhiteRooks()
                | board.getWhiteQueens()
                | board.getWhiteKing();

        long blackUnion = board.getBlackPawns()
                | board.getBlackKnights()
                | board.getBlackBishops()
                | board.getBlackRooks()
                | board.getBlackQueens()
                | board.getBlackKing();

        assertEquals(whiteUnion, board.getWhitePieces(), context + " - white aggregate mismatch");
        assertEquals(blackUnion, board.getBlackPieces(), context + " - black aggregate mismatch");
        assertEquals(0L, whiteUnion & blackUnion, context + " - overlapping white/black occupancy");
        assertEquals(whiteUnion | blackUnion, board.getAllPieces(),
                context + " - combined occupancy mismatch");

        for (int square = 0; square < 64; square++) {
            long mask = 1L << square;
            PieceType piece = board.getPieceTypeAtIndex(square);
            boolean whiteBit = (whiteUnion & mask) != 0;
            boolean blackBit = (blackUnion & mask) != 0;
            boolean aggregatedOccupancy = whiteBit || blackBit;
            boolean boardOccupancy = board.isOccupied(square);

            String squareLabel = context + " @" + convertIndexToString(square);

            if (piece == null) {
                assertFalse(aggregatedOccupancy, squareLabel + " - aggregate occupied but no piece recorded");
                assertFalse(boardOccupancy, squareLabel + " - occupancy flag set without piece");
                assertNull(board.getPieceColorAtIndex(square), squareLabel + " - color reported without piece");
            } else {
                assertTrue(aggregatedOccupancy, squareLabel + " - missing aggregate bit for piece " + piece);
                assertTrue(boardOccupancy, squareLabel + " - occupancy flag missing for piece " + piece);
                assertTrue(whiteBit ^ blackBit,
                        squareLabel + " - piece " + piece + " associated with both colors");
                Color color = whiteBit ? Color.WHITE : Color.BLACK;
                assertEquals(color, board.getPieceColorAtIndex(square),
                        squareLabel + " - piece color mismatch for " + piece);
                long specific = getPieceBitboard(board, piece, color);
                assertTrue((specific & mask) != 0,
                        squareLabel + " - piece " + piece + " missing from specific bitboard");
            }
        }
    }

    private static long getPieceBitboard(BitBoard board, PieceType piece, Color color) {
        return switch (piece) {
            case PAWN -> color == Color.WHITE ? board.getWhitePawns() : board.getBlackPawns();
            case KNIGHT -> color == Color.WHITE ? board.getWhiteKnights() : board.getBlackKnights();
            case BISHOP -> color == Color.WHITE ? board.getWhiteBishops() : board.getBlackBishops();
            case ROOK -> color == Color.WHITE ? board.getWhiteRooks() : board.getBlackRooks();
            case QUEEN -> color == Color.WHITE ? board.getWhiteQueens() : board.getBlackQueens();
            case KING -> color == Color.WHITE ? board.getWhiteKing() : board.getBlackKing();
        };
    }

    private static void waitForCondition(BooleanSupplier condition, long timeoutMillis, String failureMessage)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail(failureMessage);
            }
            Thread.sleep(20L);
        }
    }

    private static final class InstrumentedEngine extends Engine {
        private final String label;
        private final AtomicInteger simulationCounter = new AtomicInteger();

        private InstrumentedEngine(String label) {
            super();
            this.label = label;
            assertBoardConsistent(new BitBoard(getBitBoard()), label + " (init)");
        }

        private InstrumentedEngine(InstrumentedEngine source, String label) {
            super(source);
            this.label = label;
            assertBoardConsistent(new BitBoard(getBitBoard()), label + " (clone)");
        }

        @Override
        public Engine createSimulation() {
            int id = simulationCounter.incrementAndGet();
            return new InstrumentedEngine(this, label + "[sim-" + id + "]");
        }

        @Override
        public void performMove(int move) {
            super.performMove(move);
            assertBoardConsistent(new BitBoard(getBitBoard()), label + " performMove " + describeMove(move));
        }

        @Override
        public void undoLastMove() {
            super.undoLastMove();
            assertBoardConsistent(new BitBoard(getBitBoard()), label + " undoLastMove");
        }

        @Override
        public void undoNullMoveForSearch(int previousDoubleStep) {
            super.undoNullMoveForSearch(previousDoubleStep);
            assertBoardConsistent(new BitBoard(getBitBoard()), label + " undoNullMove");
        }

        @Override
        public void copyFrom(Engine other) {
            super.copyFrom(other);
            assertBoardConsistent(new BitBoard(getBitBoard()), label + " copyFrom");
        }

        @Override
        public void importBoardFromFen(String fen) {
            super.importBoardFromFen(fen);
            assertBoardConsistent(new BitBoard(getBitBoard()), label + " importFEN");
        }
    }

    private static String describeMove(int move) {
        PieceType mover = intToPieceType(derivePieceTypeBits(move));
        String notation = mover + " " + convertIndexToString(deriveFromIndex(move))
                + "-" + convertIndexToString(deriveToIndex(move));
        if (isCapture(move)) {
            notation += "x";
        }
        int promotionBits = derivePromotionPieceTypeBits(move);
        if (promotionBits != 0) {
            notation += "=" + intToPieceType(promotionBits);
        }
        return notation;
    }
}
