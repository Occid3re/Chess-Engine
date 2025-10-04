package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.ZobristTable;
import julius.game.chessengine.utils.Color;
import julius.game.chessengine.utils.MoveStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
        AI.MoveOrderingResult ordering = ai.sortMovesByEfficiency(input, 4, hash, -1, engine);
        IntArrayList ordered = ordering.moves();

        log.info("Ordered move list ({} entries):", ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            int move = ordered.getInt(i);
            log.info("  [{}] {}", i, describeMove(move));
        }

        assertEquals(bestReply, ordered.getInt(0), "TT move must be pinned to the top of the ordered list");
        assertEquals(moves.size(), ordered.size(), "Ordering should not lose or duplicate moves");
    }

    @Test
    @DisplayName("Zobrist hash is stable under make/undo of all legal moves")
    void zobristHashStableUnderMakeUndo() {
        List<String> fens = List.of(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
                "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
                "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2",
                "rnbq1rk1/ppppbppp/5n2/4p3/2B1P3/2NP1N2/PPP2PPP/R1BQ1RK1 w - - 5 7",
                "rnbq1rk1/ppppbppp/5n2/4p3/2B1P3/2NP1N2/PPP2PPP/R1BQ1RK1 b - - 5 7",
                "r2q1rk1/ppp2ppp/2n1bn2/3p4/3P4/2NBPN2/PP3PPP/R1BQR1K1 w - - 0 12",
                "r2q1rk1/ppp2ppp/2n1bn2/3p4/3P4/2NBPN2/PP3PPP/R1BQR1K1 b - - 0 12",
                "r4rk1/pp1b1ppp/2n1pn2/2qp4/3P4/2N1PN2/PP2QPPP/R2R2K1 w - - 2 16",
                "2r2rk1/pp2bppp/2n1pn2/2qp4/3P4/2N1PN2/PPQ2PPP/2RR2K1 w - - 0 15",
                "r1bq1rk1/pp1nbppp/2n1p3/2ppP3/3P1P2/2PBBN2/PP3PPP/RN1Q1RK1 w - - 0 10",
                "r2q1rk1/pb1nbppp/1pn1p3/2ppP3/3P1P2/2PBBN2/PP3PPP/RN1Q1RK1 w - - 2 11",
                "r3k2r/ppp2ppp/2n1bn2/3p4/3P4/2N1PN2/PPQ1BPPP/R3K2R w KQkq - 0 12",
                "r4rk1/1bqnbppp/p2ppn2/1p4B1/3PP3/2N1BN2/PPQ1BPPP/2RR2K1 w - - 0 13",
                "3r2k1/pp3ppp/2n1pn2/2qp4/3P4/2N1PN2/PPQ2PPP/2RR2K1 w - - 0 18",
                "r2q1rk1/pp3ppp/2n1bn2/3pp3/3P4/2NBPN2/PP3PPP/R1BQR1K1 w - - 0 13",
                "8/5pkp/6p1/3p4/3P4/2P3P1/5PK1/8 w - - 0 30",
                "8/1p3pp1/3kp3/2p1n3/2P1P3/1P1K2P1/6P1/8 w - - 0 35",
                "4k3/1pp2ppp/p1n1pn2/3p4/3P4/1PN1PN2/P1P2PPP/2K4R w K - 0 10",
                "r1bq1rk1/pppp1ppp/2n2n2/2b1p3/2B1P3/2NP1N2/PPPQ1PPP/R3KB1R w KQ - 4 6",
                "2kr3r/ppp2ppp/2n1bn2/3p4/3P4/2N1PN2/PPQ1BPPP/2RR2K1 w - - 0 14",
                "r1bqk2r/pp1n1ppp/2p1pn2/2bp4/2PP4/2N1PN2/PP1QBPPP/R3KB1R w KQkq - 6 8",
                "4rrk1/1bp2ppp/p1np1q2/1p1N4/3P4/2N1P3/PPQ2PPP/3RR1K1 w - - 0 19"
        );

        Engine engine = new Engine();
        Random random = new Random(0xC0FFEE);

        for (String fen : fens) {
            engine.importBoardFromFen(fen);
            verifySingleMoveUndoRestoresState(engine, fen);

            engine.importBoardFromFen(fen);
            verifyRandomSequenceUndoRestoresState(engine, fen, random, 3, 5);
        }
    }

    @Test
    @DisplayName("No stale capture flags on generated moves (single-ply)")
    void noStaleCaptureFlagsSinglePly() {
        List<String> fens = buildFenCorpus();
        Engine engine = new Engine();

        for (String fen : fens) {
            engine.importBoardFromFen(fen);
            BitBoard board = engine.getBitBoard();
            long initialHash = engine.getBoardStateHash();
            PieceType[] referencePieceBoard = Arrays.copyOf(board.getPieceBoard(), board.getPieceBoard().length);
            Map<String, Long> referenceBitboards = snapshotBitboards(board);

            IntArrayList moves = engine.getAllLegalMoves();
            for (int i = 0; i < moves.size(); i++) {
                int move = moves.getInt(i);
                verifyCaptureIntegrity(engine, fen, move, "single-ply", null, referencePieceBoard, referenceBitboards, initialHash);
            }
        }
    }

    @Test
    @DisplayName("Capture flag/type remains consistent across make/undo sequences")
    void captureFlagsRemainConsistentAcrossMakeUndoSequences() {
        List<String> fens = buildFenCorpus();
        Random random = new Random(0x5A77F00DL);

        for (String fen : fens) {
            Engine engine = new Engine();
            engine.importBoardFromFen(fen);

            BitBoard board = engine.getBitBoard();
            long initialHash = engine.getBoardStateHash();
            PieceType[] initialPieceBoard = Arrays.copyOf(board.getPieceBoard(), board.getPieceBoard().length);
            Map<String, Long> initialBitboards = snapshotBitboards(board);

            IntArrayList rootMoves = engine.getAllLegalMoves();
            Map<MoveSignature, CaptureExpectation> rootExpectations = new LinkedHashMap<>();
            for (int i = 0; i < rootMoves.size(); i++) {
                int move = rootMoves.getInt(i);
                CaptureExpectation expectation = verifyCaptureIntegrity(engine, fen, move, "root", null, null, null, initialHash);
                rootExpectations.put(new MoveSignature(
                        MoveHelper.deriveFromIndex(move),
                        MoveHelper.deriveToIndex(move),
                        MoveHelper.derivePromotionPieceTypeBits(move)), expectation);
            }

            PlainFixedSizeTranspositionTable<TranspositionTableEntry> table =
                    new PlainFixedSizeTranspositionTable<>(32, TranspositionTableEntry.class);
            int seededMove = rootMoves.isEmpty() ? -1 : rootMoves.getInt(0);
            if (seededMove != -1) {
                table.put(initialHash, new TranspositionTableEntry(0.0, 1, NodeType.EXACT, seededMove), 1);
            }

            for (int depth = 1; depth <= 4; depth++) {
                for (int sequenceIndex = 0; sequenceIndex < 8; sequenceIndex++) {
                    List<Integer> sequence = new ArrayList<>();

                    for (int ply = 0; ply < depth; ply++) {
                        IntArrayList moves = engine.getAllLegalMoves();
                        if (moves.isEmpty()) {
                            break;
                        }
                        int choice = random.nextInt(moves.size());
                        int move = moves.getInt(choice);
                        verifyCaptureIntegrity(engine, fen,
                                move,
                                "depth " + depth + " seq " + sequenceIndex + " ply " + ply,
                                sequence,
                                null,
                                null,
                                initialHash);
                        sequence.add(move);
                        int before = lineSize(engine);
                        engine.performMove(move);
                        int after  = lineSize(engine);

                        int finalSequenceIndex = sequenceIndex;
                        int finalDepth = depth;
                        int finalPly = ply;
                        assertEquals(before + 1, after,
                                () -> "HISTORY UNDERFLOW SOURCE: performMove did not push for " + describeMove(move) +
                                        " at depth " + finalDepth + " seq " + finalSequenceIndex + " ply " + finalPly +
                                        " | FEN: " + fen);
                    }

                    String sequenceDescription = describeSequence(sequence);
                    for (int i = sequence.size() - 1; i >= 0; i--) {
                        int expectedMove = sequence.get(i);
                        int lastMove = engine.getLastMove();
                        String undoContext = String.format("depth %d seq %d undoIndex %d", depth, sequenceIndex, i);
                        if (lastMove == -1) {
                            log.error("Undo stack empty before popping expected move {} ({}). Sequence: {}", expectedMove,
                                    describeMove(expectedMove), sequenceDescription);
                            logEngineMoveStacks(engine, undoContext + " pre-pop empty stack");
                        } else if (lastMove != expectedMove) {
                            log.warn("Undo stack top {} ({}) differs from expected move {} ({}) at {}. Sequence: {}", lastMove,
                                    describeMove(lastMove), expectedMove, describeMove(expectedMove), undoContext,
                                    sequenceDescription);
                            logEngineMoveStacks(engine, undoContext + " pre-pop mismatch");
                        }
                        try {
                            engine.undoLastMove();
                        } catch (RuntimeException undoError) {
                            log.error("Undo failed while processing {}. Last move before failure: {}. Sequence: {}", undoContext,
                                    describeMaybeMove(lastMove), sequenceDescription, undoError);
                            logEngineMoveStacks(engine, undoContext + " failure");
                            throw undoError;
                        }
                    }

                    assertStateRestored(fen, initialHash, initialPieceBoard, initialBitboards, engine, null, sequence);

                    long restoredHash = engine.getBoardStateHash();
                    assertEquals(initialHash, restoredHash, "Zobrist hash should restore after undo for FEN: " + fen);

                    Engine roundTrip = new Engine();
                    roundTrip.importBoardFromFen(engine.translateBoardToFen().getRenderBoard());
                    assertEquals(restoredHash, roundTrip.getBoardStateHash(),
                            "Round-trip FEN import must reproduce hash for FEN: " + fen);

                    if (seededMove != -1) {
                        TranspositionTableEntry stored = table.get(initialHash);
                        assertNotNull(stored, "TT entry for root hash must remain accessible for FEN: " + fen);
                        assertEquals(seededMove, stored.getBestMove(),
                                "Stored TT best move must remain unchanged for root hash in FEN: " + fen);
                    }

                    IntArrayList restoredMoves = engine.getAllLegalMoves();
                    Map<MoveSignature, CaptureExpectation> restoredExpectations = new LinkedHashMap<>();
                    for (int i = 0; i < restoredMoves.size(); i++) {
                        int move = restoredMoves.getInt(i);
                        CaptureExpectation expectation = verifyCaptureIntegrity(engine, fen, move, "post-undo", sequence,
                                initialPieceBoard, initialBitboards, initialHash);
                        restoredExpectations.put(new MoveSignature(
                                MoveHelper.deriveFromIndex(move),
                                MoveHelper.deriveToIndex(move),
                                MoveHelper.derivePromotionPieceTypeBits(move)), expectation);
                    }

                    assertEquals(rootExpectations.size(), restoredExpectations.size(),
                            "Root move count drift after undo for FEN: " + fen);

                    rootExpectations.forEach((signature, expected) -> {
                        CaptureExpectation restored = restoredExpectations.get(signature);
                        assertNotNull(restored, () -> "Missing restored move for signature " + signature + " in FEN: " + fen);
                        assertEquals(expected.capture, restored.capture,
                                () -> "Capture flag drift for move signature " + signature + " in FEN: " + fen);
                        assertEquals(expected.enPassant, restored.enPassant,
                                () -> "En passant flag drift for move signature " + signature + " in FEN: " + fen);
                        assertEquals(expected.expectedCapturedPieceBits, restored.expectedCapturedPieceBits,
                                () -> "Captured piece type bits drift for move signature " + signature + " in FEN: " + fen);
                    });
                }
            }

            if (seededMove != -1) {
                TranspositionTableEntry stored = table.get(initialHash);
                assertNotNull(stored, "TT entry should persist after all sequences for FEN: " + fen);
                assertEquals(seededMove, stored.getBestMove(),
                        "Best move stored in TT must remain intact after all sequences for FEN: " + fen);
            }
        }
    }

    private static List<String> buildFenCorpus() {
        List<String> curated = new ArrayList<>(List.of(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
                "rnbqkbnr/ppp1pppp/8/3p4/2P5/8/PP1PPPPP/RNBQKBNR b KQkq c3 0 2",
                "rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 2",
                "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
                "rnbqkbnr/pppp1ppp/5n2/4p3/3PP3/5N2/PPP2PPP/RNBQKB1R w KQkq - 2 3",
                "r1bqkbnr/pppp1ppp/2n5/4p3/3PP1b1/2N2N2/PPP2PPP/R1BQKB1R w KQkq - 3 4",
                "r1bq1rk1/ppppbppp/2n5/4p3/3PP1b1/2N2N2/PPP1BPPP/R1BQ1RK1 w - - 6 7",
                "rnbq1rk1/ppppbppp/5n2/4p3/2BPP1b1/2N2N2/PPPQ1PPP/R3K1NR w KQ - 5 7",
                "r1bq1rk1/1pp1bppp/p1np1n2/4p3/3P4/1PN1PN2/PBP1BPPP/R2Q1RK1 w - - 6 9",
                "r2q1rk1/pp2bppp/2n1pn2/3p4/3P4/2N1PN2/PPQ1BPPP/R1B2RK1 w - - 0 10",
                "r2q1rk1/pp3ppp/2n1bn2/3pp3/3P4/2NBPN2/PP3PPP/R1BQR1K1 w - - 0 13",
                "rnbq1rk1/pp3ppp/2p1pn2/2bp4/2PP4/2N1PN2/PP1QBPPP/R3KB1R w KQ - 4 8",
                "r1bq1rk1/pppp1ppp/2n2n2/2b1p3/2B1P3/2NP1N2/PPPQ1PPP/R3KB1R w KQ - 4 6",
                "r1bq1rk1/pp1nbppp/2n1p3/2ppP3/3P1P2/2PBBN2/PP3PPP/RN1Q1RK1 w - - 0 10",
                "r3k2r/ppp2ppp/2n1bn2/3p4/3P4/2N1PN2/PPQ1BPPP/R3K2R w KQkq - 0 12",
                "r4rk1/1bqnbppp/p2ppn2/1p4B1/3PP3/2N1BN2/PPQ1BPPP/2RR2K1 w - - 0 13",
                "3r2k1/pp3ppp/2n1pn2/2qp4/3P4/2N1PN2/PPQ2PPP/2RR2K1 w - - 0 18",
                "r4rk1/pp1b1ppp/2n1pn2/2qp4/3P4/2N1PN2/PP2QPPP/R2R2K1 w - - 2 16",
                "r2q1rk1/ppp2ppp/2n1bn2/3p4/3P4/2NBPN2/PP3PPP/R1BQR1K1 w - - 0 12",
                "8/5pkp/6p1/3p4/3P4/2P3P1/5PK1/8 w - - 0 30",
                "8/1p3pp1/3kp3/2p1n3/2P1P3/1P1K2P1/6P1/8 w - - 0 35",
                "4k3/1pp2ppp/p1n1pn2/3p4/3P4/1PN1PN2/P1P2PPP/2K4R w K - 0 10",
                "2kr3r/ppp2ppp/2n1bn2/3p4/3P4/2N1PN2/PPQ1BPPP/2RR2K1 w - - 0 14",
                "4rrk1/1bp2ppp/p1np1q2/1p1N4/3P4/2N1P3/PPQ2PPP/3RR1K1 w - - 0 19",
                "rnbqk2r/pp2ppbp/2pp1np1/4P3/1PPP4/2N2N2/PB1P1PPP/R2QKB1R b KQkq - 0 9",
                "r1bq1rk1/ppp1bppp/2np1n2/3Pp3/2P1P3/2N2N2/PP2BPPP/R1BQ1RK1 w - - 0 9",
                "rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq e6 0 2",
                "rnbqkbnr/ppp1pppp/8/3p4/2P5/8/PP1PPPPP/RNBQKBNR b KQkq c3 0 2",
                "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1",
                "r3k2r/8/8/8/8/8/8/R3K2R w KQ - 0 1",
                "r3k2r/8/8/8/8/8/8/R3K2R w K - 0 1",
                "r3k2r/8/8/8/8/8/8/R3K2R w Q - 0 1",
                "r3k2r/8/8/8/8/8/8/R3K2R b kq - 0 1",
                "r3k2r/8/8/8/8/8/8/R3K2R w - - 0 1",
                "8/5k2/8/8/8/8/5K2/8 w - - 0 1",
                "6k1/5ppp/8/1p1P4/1P6/6PP/6K1/8 w - - 0 40",
                "4k3/P6P/8/8/8/8/8/4K3 w - - 0 1",
                "4k3/8/8/8/8/8/p6p/4K3 b - - 0 1",
                "1r2k3/P5pp/8/8/8/8/6PP/4K3 w - - 0 1",
                "4k3/pp5P/8/8/8/8/P6p/4K1R1 b - - 0 1",
                "r3r1k1/pp3ppp/8/3B4/8/8/PP3PPP/3QR1K1 w - - 0 20",
                "rnbqkb1r/ppp2ppp/3p1n2/4p3/3PP1b1/2N2N2/PPP2PPP/R1BQKB1R w KQkq - 2 5",
                "rnbqkb1r/pppppppp/5n2/8/3P4/5N2/PPP1PPPP/RNBQKB1R b KQkq - 1 2",
                "rnbq1rk1/pppp1ppp/5n2/4p3/3PP1b1/2N2N2/PPPB1PPP/R2QK2R w KQ - 6 7",
                "r2q1rk1/ppp2ppp/2n1bn2/3p4/2PP4/2N2N2/PP2BPPP/R1BQR1K1 w - - 4 11",
                "8/6pk/6pp/8/1P3P2/6P1/6K1/8 w - - 0 44",
                "2r2rk1/pp2bppp/2n1pn2/2qp4/3P4/2N1PN2/PPQ2PPP/2RR2K1 w - - 0 15",
                "rnbq1rk1/ppp2ppp/3bp3/3p4/3P4/2N1PN2/PPQ1BPPP/R3K2R w KQ - 0 10",
                "r4rk1/ppp2ppp/2n1bn2/3p4/3P4/2N1PN2/PPQ2PPP/R4RK1 w - - 0 14",
                "8/5p2/5Ppk/8/4P3/8/6K1/8 w - - 0 52",
                "4k3/8/8/3K4/8/8/8/8 w - - 0 1",
                "4k3/8/8/8/8/8/3K4/8 b - - 0 1",
                "4k3/3r4/8/8/8/8/4R3/4K3 w - - 0 1",
                "8/8/8/8/2k5/8/8/4K3 w - - 0 1"
        ));

        Set<String> unique = new LinkedHashSet<>(curated);
        Random rng = new Random(0xC0FFEE42L);
        unique.addAll(generateRandomFens(rng, 200));
        return new ArrayList<>(unique);
    }

    private static List<String> generateRandomFens(Random rng, int desiredCount) {
        Set<String> results = new LinkedHashSet<>();
        int attempts = 0;
        while (results.size() < desiredCount && attempts < desiredCount * 20) {
            attempts++;
            Engine engine = new Engine();
            int plies = 6 + rng.nextInt(7);
            for (int ply = 0; ply < plies; ply++) {
                IntArrayList moves = engine.getAllLegalMoves();
                if (moves.isEmpty()) {
                    break;
                }
                int choice = rng.nextInt(moves.size());
                engine.performMove(moves.getInt(choice));
            }
            String fen = engine.translateBoardToFen().getRenderBoard();
            results.add(fen);
        }
        if (results.size() < desiredCount) {
            throw new IllegalStateException("Unable to generate the requested number of random FEN positions");
        }
        return new ArrayList<>(results);
    }

    private static CaptureExpectation verifyCaptureIntegrity(Engine engine, String fen, int move, String phase,
                                                             List<Integer> sequence,
                                                             PieceType[] referencePieceBoard,
                                                             Map<String, Long> referenceBitboards,
                                                             long initialHash) {
        BitBoard board = engine.getBitBoard();
        int toIndex = MoveHelper.deriveToIndex(move);
        PieceType destinationPiece = board.getPieceTypeAtIndex(toIndex);
        boolean isCapture = MoveHelper.isCapture(move);
        boolean isEnPassant = MoveHelper.isEnPassantMove(move);
        int capturedBits = MoveHelper.deriveCapturedPieceTypeBits(move);
        Color moverColor = MoveHelper.isWhitesMove(move) ? Color.WHITE : Color.BLACK;

        int captureSquare = -1;
        PieceType expectedCapturedPiece = null;
        PieceType actualPieceAtCaptureSquare = null;
        PieceType enPassantVictimPiece = null;

        if (isCapture) {
            if (isEnPassant) {
                captureSquare = toIndex + (MoveHelper.isWhitesMove(move) ? -8 : 8);
                if (captureSquare < 0 || captureSquare >= 64) {
                    reportCaptureFailure(fen, engine, move, phase, "En passant capture square out of bounds",
                            captureSquare, PieceType.PAWN, null, destinationPiece, null,
                            sequence, referencePieceBoard, referenceBitboards, initialHash);
                }
                expectedCapturedPiece = PieceType.PAWN;
                actualPieceAtCaptureSquare = board.getPieceTypeAtIndex(captureSquare);
                enPassantVictimPiece = actualPieceAtCaptureSquare;
                if (destinationPiece != null) {
                    reportCaptureFailure(fen, engine, move, phase, "Destination square occupied on en passant move",
                            captureSquare, expectedCapturedPiece, actualPieceAtCaptureSquare, destinationPiece,
                            enPassantVictimPiece, sequence, referencePieceBoard, referenceBitboards, initialHash);
                }
            } else {
                captureSquare = toIndex;
                actualPieceAtCaptureSquare = destinationPiece;
                expectedCapturedPiece = destinationPiece;
            }

            if (actualPieceAtCaptureSquare == null) {
                reportCaptureFailure(fen, engine, move, phase, "Capture flag set but target square empty",
                        captureSquare, expectedCapturedPiece, null, destinationPiece, enPassantVictimPiece,
                        sequence, referencePieceBoard, referenceBitboards, initialHash);
            }

            if (actualPieceAtCaptureSquare == PieceType.KING) {
                reportCaptureFailure(fen, engine, move, phase, "Capture claims a king piece",
                        captureSquare, expectedCapturedPiece, actualPieceAtCaptureSquare, destinationPiece,
                        enPassantVictimPiece, sequence, referencePieceBoard, referenceBitboards, initialHash);
            }

            Color victimColor = board.getPieceColorAtIndex(captureSquare);
            if (victimColor == moverColor) {
                reportCaptureFailure(fen, engine, move, phase, "Capture targets same-color piece",
                        captureSquare, expectedCapturedPiece, actualPieceAtCaptureSquare, destinationPiece,
                        enPassantVictimPiece, sequence, referencePieceBoard, referenceBitboards, initialHash);
            }

            int expectedBits = MoveHelper.pieceTypeToInt(expectedCapturedPiece);
            if (capturedBits != expectedBits) {
                reportCaptureFailure(fen, engine, move, phase,
                        "Captured piece type bits mismatch (expected=" + expectedBits + ", actual=" + capturedBits + ")",
                        captureSquare, expectedCapturedPiece, actualPieceAtCaptureSquare, destinationPiece,
                        enPassantVictimPiece, sequence, referencePieceBoard, referenceBitboards, initialHash);
            }
        } else {
            if (capturedBits != 0) {
                reportCaptureFailure(fen, engine, move, phase, "Quiet move encodes captured piece bits",
                        -1, null, null, destinationPiece, null,
                        sequence, referencePieceBoard, referenceBitboards, initialHash);
            }
            if (destinationPiece != null) {
                Color destinationColor = board.getPieceColorAtIndex(toIndex);
                if (destinationColor != moverColor) {
                    reportCaptureFailure(fen, engine, move, phase,
                            "Quiet move lands on enemy piece without capture flag",
                            toIndex, null, destinationPiece, destinationPiece, null,
                            sequence, referencePieceBoard, referenceBitboards, initialHash);
                }
            }
        }

        return new CaptureExpectation(isCapture, isEnPassant, captureSquare,
                expectedCapturedPiece, isCapture ? MoveHelper.pieceTypeToInt(expectedCapturedPiece) : 0);
    }

    private static void reportCaptureFailure(String fen, Engine engine, int move, String phase, String message,
                                             int expectedCaptureSquare, PieceType expectedCapturedPiece,
                                             PieceType actualCapturedPiece, PieceType destinationPiece,
                                             PieceType enPassantVictimPiece, List<Integer> sequence,
                                             PieceType[] referencePieceBoard, Map<String, Long> referenceBitboards,
                                             long initialHash) {
        BitBoard board = engine.getBitBoard();
        long currentHash = engine.getBoardStateHash();
        List<String> pieceDrift = referencePieceBoard != null
                ? detectPieceBoardDrift(referencePieceBoard, board.getPieceBoard())
                : List.of();
        List<String> bitboardDrift = referenceBitboards != null
                ? detectBitboardDrift(referenceBitboards, snapshotBitboards(board))
                : List.of();
        List<Integer> sequenceSnapshot = sequence == null ? List.of() : List.copyOf(sequence);

        log.error("Capture integrity failure during {}: {}", phase, message);
        log.error("  FEN: {}", fen);
        log.error("  Side to move: {}", board.isWhitesTurn() ? "white" : "black");
        log.error("  Move: {}", describeMove(move));
        log.error("  Fields: from={}, to={}, isCapture={}, isEnPassant={}, promo={}, capturedPieceTypeBits={} ({})",
                MoveHelper.deriveFromIndex(move), MoveHelper.deriveToIndex(move),
                MoveHelper.isCapture(move), MoveHelper.isEnPassantMove(move),
                MoveHelper.derivePromotionPieceTypeBits(move),
                MoveHelper.deriveCapturedPieceTypeBits(move),
                capturedBitsToString(MoveHelper.deriveCapturedPieceTypeBits(move)));
        if (expectedCaptureSquare >= 0) {
            log.error("  Expected capture square: {} ({})", expectedCaptureSquare,
                    MoveHelper.convertIndexToString(expectedCaptureSquare));
        } else {
            log.error("  Expected capture square: <none>");
        }
        log.error("  Expected captured piece: {}", pieceTypeToString(expectedCapturedPiece));
        log.error("  Actual piece at expected square: {}", pieceTypeToString(actualCapturedPiece));
        log.error("  pieceBoard[to]: {}", pieceTypeToString(destinationPiece));
        if (enPassantVictimPiece != null) {
            log.error("  En-passant victim square piece: {}", pieceTypeToString(enPassantVictimPiece));
        }
        if (!sequenceSnapshot.isEmpty()) {
            log.error("  Sequence: {}", sequenceSnapshot.stream()
                    .map(TranspositionTableZobristTest::describeMove)
                    .collect(Collectors.joining(", ")));
        }
        log.error("  Zobrist initial/restored: 0x{} / 0x{}", Long.toHexString(initialHash), Long.toHexString(currentHash));
        log.error("  Piece board mismatches: {}", pieceDrift);
        log.error("  Bitboard mismatches: {}", bitboardDrift);
        fail(message + " (phase=" + phase + ", fen=" + fen + ")");
    }

    private record MoveSignature(int from, int to, int promotionBits) {
    }

    private record CaptureExpectation(boolean capture, boolean enPassant, int captureSquare,
                                      PieceType expectedCapturedPiece, int expectedCapturedPieceBits) {
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

    private static String describeMaybeMove(int move) {
        return move == -1 ? "<none>" : describeMove(move);
    }

    private static String describeSequence(List<Integer> moves) {
        if (moves == null || moves.isEmpty()) {
            return "<empty>";
        }
        return moves.stream()
                .map(TranspositionTableZobristTest::describeMove)
                .collect(Collectors.joining(", "));
    }

    private static void logEngineMoveStacks(Engine engine, String context) {
        try {
            Field lineField = Engine.class.getDeclaredField("line");
            Field redoField = Engine.class.getDeclaredField("redoLine");
            lineField.setAccessible(true);
            redoField.setAccessible(true);
            MoveStack lineStack = (MoveStack) lineField.get(engine);
            MoveStack redoStack = (MoveStack) redoField.get(engine);
            log.error("[{}] line stack size={} moves=[{}]", context, lineStack.size(), describeMoveStack(lineStack));
            log.error("[{}] redo stack size={} moves=[{}]", context, redoStack.size(), describeMoveStack(redoStack));
        } catch (ReflectiveOperationException reflectionError) {
            log.error("Failed to log Engine move stacks for context {}", context, reflectionError);
        }
    }

    private static String describeMoveStack(MoveStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "<empty>";
        }
        return stack.stream()
                .mapToObj(TranspositionTableZobristTest::describeMove)
                .collect(Collectors.joining(", "));
    }

    private static void verifySingleMoveUndoRestoresState(Engine engine, String fen) {
        long initialHash = engine.getBoardStateHash();
        BitBoard board = engine.getBitBoard();
        PieceType[] initialPieceBoard = Arrays.copyOf(board.getPieceBoard(), board.getPieceBoard().length);
        Map<String, Long> initialBitboards = snapshotBitboards(board);

        IntArrayList legalMoves = engine.getAllLegalMoves();
        assertFalse(legalMoves.isEmpty(), "Generated position must have at least one legal move for FEN: " + fen);

        for (int i = 0; i < legalMoves.size(); i++) {
            int move = legalMoves.getInt(i);
            engine.performMove(move);
            engine.undoLastMove();
            assertStateRestored(fen, initialHash, initialPieceBoard, initialBitboards, engine, move, null);
        }
    }

    private static void verifyRandomSequenceUndoRestoresState(Engine engine, String fen, Random random, int depth, int sequences) {
        long initialHash = engine.getBoardStateHash();
        BitBoard board = engine.getBitBoard();
        PieceType[] initialPieceBoard = Arrays.copyOf(board.getPieceBoard(), board.getPieceBoard().length);
        Map<String, Long> initialBitboards = snapshotBitboards(board);

        for (int s = 0; s < sequences; s++) {
            List<Integer> movesMade = new ArrayList<>();
            for (int ply = 0; ply < depth; ply++) {
                IntArrayList moves = engine.getAllLegalMoves();
                if (moves.isEmpty()) {
                    break;
                }
                int choice = random.nextInt(moves.size());
                int move = moves.getInt(choice);
                movesMade.add(move);
                int before = lineSize(engine);
                engine.performMove(move);
                int after  = lineSize(engine);
                assertEquals(before + 1, after,
                        () -> "performMove failed to push history for " + describeMove(move) +
                                " in FEN: " + fen + " (before=" + before + ", after=" + after + ")");
            }

            for (int i = movesMade.size() - 1; i >= 0; i--) {
                engine.undoLastMove();
            }

            assertStateRestored(fen, initialHash, initialPieceBoard, initialBitboards, engine, null, movesMade);
        }
    }

    private static Map<String, Long> snapshotBitboards(BitBoard board) {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put("whitePawns", board.getWhitePawns());
        snapshot.put("blackPawns", board.getBlackPawns());
        snapshot.put("whiteKnights", board.getWhiteKnights());
        snapshot.put("blackKnights", board.getBlackKnights());
        snapshot.put("whiteBishops", board.getWhiteBishops());
        snapshot.put("blackBishops", board.getBlackBishops());
        snapshot.put("whiteRooks", board.getWhiteRooks());
        snapshot.put("blackRooks", board.getBlackRooks());
        snapshot.put("whiteQueens", board.getWhiteQueens());
        snapshot.put("blackQueens", board.getBlackQueens());
        snapshot.put("whiteKing", board.getWhiteKing());
        snapshot.put("blackKing", board.getBlackKing());
        snapshot.put("whitePieces", board.getWhitePieces());
        snapshot.put("blackPieces", board.getBlackPieces());
        snapshot.put("allPieces", board.getAllPieces());
        return snapshot;
    }

    private static void assertStateRestored(String fen, long initialHash, PieceType[] initialPieceBoard,
                                            Map<String, Long> initialBitboards, Engine engine, Integer singleMove,
                                            List<Integer> sequence) {
        long restoredHash = engine.getBoardStateHash();
        BitBoard restoredBoard = engine.getBitBoard();
        PieceType[] restoredPieceBoard = restoredBoard.getPieceBoard();
        Map<String, Long> restoredBitboards = snapshotBitboards(restoredBoard);

        List<String> squareDrift = detectPieceBoardDrift(initialPieceBoard, restoredPieceBoard);
        List<String> bitboardDrift = detectBitboardDrift(initialBitboards, restoredBitboards);

        if (initialHash != restoredHash || !squareDrift.isEmpty() || !bitboardDrift.isEmpty()) {
            log.error("Zobrist stability failure for FEN: {}", fen);
            log.error("Initial hash: 0x{} | Restored hash: 0x{}", Long.toHexString(initialHash), Long.toHexString(restoredHash));
            if (singleMove != null) {
                log.error("Move: {}", describeMove(singleMove));
            }
            if (sequence != null && !sequence.isEmpty()) {
                log.error("Sequence: {}", sequence.stream().map(TranspositionTableZobristTest::describeMove).collect(Collectors.joining(", ")));
            }
            if (!squareDrift.isEmpty()) {
                log.error("Piece board mismatches: {}", squareDrift);
            }
            if (!bitboardDrift.isEmpty()) {
                log.error("Bitboard mismatches: {}", bitboardDrift);
            }
            fail("Board state drift detected after undo");
        }
    }

    private static List<String> detectPieceBoardDrift(PieceType[] initial, PieceType[] restored) {
        List<String> mismatches = new ArrayList<>();
        int len = Math.min(initial.length, restored.length);
        for (int i = 0; i < len; i++) {
            PieceType before = initial[i];
            PieceType after = restored[i];
            if (!Objects.equals(before, after)) {
                mismatches.add(MoveHelper.convertIndexToString(i) + ':' + pieceTypeToString(before) + "->" + pieceTypeToString(after));
            }
        }
        if (initial.length != restored.length) {
            mismatches.add("pieceBoard length " + initial.length + "->" + restored.length);
        }
        return mismatches;
    }

    private static List<String> detectBitboardDrift(Map<String, Long> initial, Map<String, Long> restored) {
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, Long> entry : initial.entrySet()) {
            String key = entry.getKey();
            long expected = entry.getValue();
            long actual = restored.getOrDefault(key, Long.MIN_VALUE);
            if (expected != actual) {
                mismatches.add(key + ":0x" + Long.toHexString(expected) + "->0x" + Long.toHexString(actual));
            }
        }
        if (initial.size() != restored.size()) {
            mismatches.add("bitboard map size " + initial.size() + "->" + restored.size());
        }
        return mismatches;
    }

    private static String capturedBitsToString(int bits) {
        if (bits == 0) {
            return "none";
        }
        try {
            return MoveHelper.intToPieceType(bits).name();
        } catch (IllegalArgumentException ex) {
            return "invalid(" + bits + ")";
        }
    }

    private static String pieceTypeToString(PieceType pieceType) {
        return pieceType == null ? "empty" : pieceType.name();
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

    private static int lineSize(Engine e) {
        try {
            Field f = Engine.class.getDeclaredField("line");
            f.setAccessible(true);
            MoveStack s = (MoveStack) f.get(e);
            return (s == null) ? -1 : s.size();
        } catch (Exception ex) { return -1; }
    }

    private static String topOfLine(Engine e) {
        try {
            Field f = Engine.class.getDeclaredField("line");
            f.setAccessible(true);
            MoveStack s = (MoveStack) f.get(e);
            if (s == null || s.isEmpty()) return "<empty>";
            return TranspositionTableZobristTest.describeMove(s.peek()); // assuming MoveStack.peek()
        } catch (Exception ex) { return "<unavailable>"; }
    }

}
