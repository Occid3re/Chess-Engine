package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.ZobristTable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Transposition table hashing and move-order integration")
class TranspositionTableZobristTest {

    private static final Logger log = LogManager.getLogger(TranspositionTableZobristTest.class);

    @Test
    @DisplayName("Distinct Zobrist keys map to distinct TT entries even when clustered")
    void zobristKeysIsolateEntriesInsideCluster() {
        PlainFixedSizeTranspositionTable<TranspositionTableEntry> table =
                new PlainFixedSizeTranspositionTable<>(4, TranspositionTableEntry.class);

        List<String> fens = List.of(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
                "rnbqkbnr/pppppppp/8/3P4/8/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 1",
                "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2"
        );

        Map<Long, Integer> expectedMoves = new LinkedHashMap<>();
        AtomicInteger depthCounter = new AtomicInteger(8);

        for (String fen : fens) {
            Engine engine = new Engine();
            engine.importBoardFromFen(fen);
            long zobrist = engine.getBoardStateHash();

            IntArrayList legalMoves = engine.getAllLegalMoves();
            assertFalse(legalMoves.isEmpty(), "Generated position must have at least one legal move");
            int chosenMove = legalMoves.getInt(0);

            log.info("Position FEN: {}", fen);
            log.info("  Zobrist hash: {} (0x{})", zobrist, Long.toHexString(zobrist));
            log.info("  Best-move candidate: {}", describeMove(chosenMove));

            TranspositionTableEntry entry = new TranspositionTableEntry(depthCounter.get(), depthCounter.getAndDecrement(),
                    NodeType.EXACT, chosenMove);
            table.put(zobrist, entry, entry.getDepth());
            expectedMoves.put(zobrist, chosenMove);

            log.info("  Table snapshot after insert:\n{}", dumpTableState(table));
            table.advanceAge();
        }

        assertEquals(fens.size(), table.size(), "Every insertion should increase the stored entry count while the cluster has room");

        expectedMoves.forEach((hash, move) -> {
            TranspositionTableEntry retrieved = table.get(hash);
            log.info("Retrieving hash {} (0x{}): {}", hash, Long.toHexString(hash), retrieved);
            assertNotNull(retrieved, "Entry must be present for stored Zobrist key");
            assertEquals(move, retrieved.getBestMove(), "Zobrist collision handling should not leak stale best moves");
        });
    }

    @Test
    @DisplayName("Changing the board state updates the Zobrist key and avoids stale hash moves")
    void zobristHashTracksBoardEvolution() {
        PlainFixedSizeTranspositionTable<TranspositionTableEntry> table =
                new PlainFixedSizeTranspositionTable<>(8, TranspositionTableEntry.class);

        Engine engine = new Engine();
        long initialHash = engine.getBoardStateHash();
        IntArrayList startingMoves = engine.getAllLegalMoves();
        int e2e4 = findMove(startingMoves, "e2", "e4");
        assertTrue(e2e4 != -1, "Expected to find e2e4 in the initial legal move list");

        table.put(initialHash, new TranspositionTableEntry(42.0, 6, NodeType.EXACT, e2e4), 6);
        log.info("Stored initial entry for hash {} (0x{}): {}", initialHash, Long.toHexString(initialHash), describeMove(e2e4));
        log.info("Table now contains:\n{}", dumpTableState(table));

        engine.performMove(e2e4);
        long afterE4Hash = engine.getBoardStateHash();
        log.info("After e2e4 hash: {} (0x{}). XOR delta with black-turn key: 0x{}", afterE4Hash, Long.toHexString(afterE4Hash),
                Long.toHexString(initialHash ^ afterE4Hash ^ ZobristTable.getBlackTurnHash()));

        assertNotEquals(initialHash, afterE4Hash, "Zobrist key must change after a move is made");
        assertNull(table.get(afterE4Hash), "No entry should exist for the new position yet");

        IntArrayList replyMoves = engine.getAllLegalMoves();
        int c7c5 = findMove(replyMoves, "c7", "c5");
        assertTrue(c7c5 != -1, "Expected to find c7c5 as a reply move");
        table.put(afterE4Hash, new TranspositionTableEntry(-11.0, 7, NodeType.EXACT, c7c5), 7);
        log.info("Stored reply entry for hash {} (0x{}): {}", afterE4Hash, Long.toHexString(afterE4Hash), describeMove(c7c5));

        engine.undoLastMove();
        long revertedHash = engine.getBoardStateHash();
        log.info("Hash after undo: {} (0x{})", revertedHash, Long.toHexString(revertedHash));

        assertEquals(initialHash, revertedHash, "Undoing the move should restore the original Zobrist key");
        TranspositionTableEntry restored = table.get(revertedHash);
        assertNotNull(restored, "Original entry must still be readable after exploring a variation");
        assertEquals(e2e4, restored.getBestMove(), "Restored entry should point to the original best move");
    }

    @Test
    @DisplayName("Cluster replacement prefers deeper entries and expires the stalest hash")
    void replacementPolicyPreventsStaleBestMoves() {
        PlainFixedSizeTranspositionTable<TranspositionTableEntry> table =
                new PlainFixedSizeTranspositionTable<>(4, TranspositionTableEntry.class);

        List<String> fenSequence = List.of(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
                "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
                "r1bqkbnr/pppppppp/2n5/8/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 3"
        );

        Map<Long, Integer> trackedMoves = new LinkedHashMap<>();
        Long oldestHash = null;
        int depth = 16;
        for (String fen : fenSequence) {
            Engine engine = new Engine();
            engine.importBoardFromFen(fen);
            long hash = engine.getBoardStateHash();
            int move = engine.getAllLegalMoves().getInt(0);
            TranspositionTableEntry entry = new TranspositionTableEntry(depth * 1.0, depth, NodeType.EXACT, move);
            table.put(hash, entry, depth);
            trackedMoves.put(hash, move);
            if (oldestHash == null) {
                oldestHash = hash;
            }
            log.info("Inserted depth {} entry for hash {} (0x{}), move {}", depth, hash, Long.toHexString(hash), describeMove(move));
            log.info("Table state:\n{}", dumpTableState(table));
            depth--;
            table.advanceAge();
        }

        assertEquals(4, table.size(), "All cluster slots should be occupied before replacement");

        Engine fresh = new Engine();
        fresh.importBoardFromFen("rnbqkbnr/pp2pppp/2p5/3p4/3PP3/8/PPP2PPP/RNBQKBNR w KQkq - 0 3");
        long newHash = fresh.getBoardStateHash();
        int quietMove = fresh.getAllLegalMoves().getInt(0);
        TranspositionTableEntry shallowEntry = new TranspositionTableEntry(-5.5, 1, NodeType.LOWERBOUND, quietMove);

        table.advanceAge();
        log.info("Inserting new shallow-depth entry to force an age-based replacement");
        table.put(newHash, shallowEntry, shallowEntry.getDepth());
        log.info("Table state after replacement:\n{}", dumpTableState(table));

        assertNotNull(oldestHash, "An oldest hash should have been recorded");
        assertNull(table.get(oldestHash), "Age-based replacement should evict the stalest entry (oldest hash)");

        TranspositionTableEntry inserted = table.get(newHash);
        assertNotNull(inserted, "Newly inserted entry must be present");
        assertEquals(quietMove, inserted.getBestMove(), "Replacement should retain the new move without mixing in stale data");
    }

    @Test
    @DisplayName("Move ordering pins the TT move to the front of the list")
    void moveOrderingPinsTranspositionMove() throws Exception {
        Engine engine = new Engine();
        engine.importBoardFromFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1");

        AI ai = new AI(engine);
        ai.setHashSizeMb(1);

        long hash = engine.getBoardStateHash();
        IntArrayList moves = engine.getAllLegalMoves();
        assertFalse(moves.isEmpty(), "Black must have legal replies");

        int bestReply = findMove(moves, "c7", "c5");
        if (bestReply == -1) {
            bestReply = moves.getInt(0);
        }
        log.info("Selected TT move for ordering test: {}", describeMove(bestReply));

        Field ttField = AI.class.getDeclaredField("transpositionTable");
        ttField.setAccessible(true);
        @SuppressWarnings("unchecked")
        TranspositionTable<TranspositionTableEntry> tt = (TranspositionTable<TranspositionTableEntry>) ttField.get(ai);
        assertNotNull(tt, "AI should expose a usable transposition table");

        tt.put(hash, new TranspositionTableEntry(-0.75, 12, NodeType.EXACT, bestReply), 12);
        log.info("Transposition table seeded with best reply {} for hash {}", describeMove(bestReply), Long.toHexString(hash));

        IntArrayList input = new IntArrayList(moves);
        IntArrayList ordered = ai.sortMovesByEfficiency(input, 4, hash, -1, engine);

        log.info("Ordered move list ({} entries):", ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            int move = ordered.getInt(i);
            log.info("  [{}] {}", i, describeMove(move));
        }

        assertEquals(bestReply, ordered.getInt(0), "TT move must be pinned to the top of the ordered list");
        assertEquals(moves.size(), ordered.size(), "Ordering should not lose or duplicate moves");
    }

    private static int findMove(IntArrayList moves, String from, String to) {
        int fromIdx = MoveHelper.convertStringToIndex(from);
        int toIdx = MoveHelper.convertStringToIndex(to);
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getInt(i);
            if (MoveHelper.deriveFromIndex(move) == fromIdx && MoveHelper.deriveToIndex(move) == toIdx) {
                return move;
            }
        }
        return -1;
    }

    private static String describeMove(int move) {
        int from = MoveHelper.deriveFromIndex(move);
        int to = MoveHelper.deriveToIndex(move);
        String fromStr = MoveHelper.convertIndexToString(from);
        String toStr = MoveHelper.convertIndexToString(to);
        PieceType mover = MoveHelper.intToPieceType(MoveHelper.derivePieceTypeBits(move));
        String color = MoveHelper.isWhitesMove(move) ? "white" : "black";
        boolean capture = MoveHelper.isCapture(move);
        boolean promo = MoveHelper.derivePromotionPieceTypeBits(move) != 0;
        return String.format("%s %s %s%s%s", color, mover, fromStr + toStr,
                capture ? "x" : "", promo ? "=" + MoveHelper.intToPieceType(MoveHelper.derivePromotionPieceTypeBits(move)) : "");
    }

    private static String dumpTableState(PlainFixedSizeTranspositionTable<TranspositionTableEntry> table) {
        try {
            Field tableField = PlainFixedSizeTranspositionTable.class.getDeclaredField("table");
            tableField.setAccessible(true);
            Object rawTable = tableField.get(table);
            int length = Array.getLength(rawTable);
            Class<?> entryClass = Class.forName("julius.game.chessengine.ai.PlainFixedSizeTranspositionTable$Entry");
            Field entryKey = entryClass.getDeclaredField("key");
            Field entryValue = entryClass.getDeclaredField("value");
            Field entryDepth = entryClass.getDeclaredField("depth");
            Field entryAge = entryClass.getDeclaredField("age");
            entryKey.setAccessible(true);
            entryValue.setAccessible(true);
            entryDepth.setAccessible(true);
            entryAge.setAccessible(true);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < length; i++) {
                Object entry = Array.get(rawTable, i);
                sb.append(String.format("slot[%d]: ", i));
                if (entry == null) {
                    sb.append("<empty>\n");
                    continue;
                }
                long key = (long) entryKey.get(entry);
                TranspositionTableEntry value = (TranspositionTableEntry) entryValue.get(entry);
                int depth = (int) entryDepth.get(entry);
                int age = (int) entryAge.get(entry);
                sb.append(String.format("key=0x%s depth=%d age=%d value=%s\n", Long.toHexString(key), depth, age, value));
            }
            return sb.toString();
        } catch (ReflectiveOperationException ex) {
            return "<reflection failed: " + ex.getMessage() + ">";
        }
    }
}
