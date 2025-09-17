package julius.game.chessengine.utils.eval;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.helper.BitHelper;
import julius.game.chessengine.helper.PawnHelper;
import julius.game.chessengine.helper.RookHelper;
import julius.game.chessengine.utils.Color;
import org.junit.jupiter.api.Test;

import static julius.game.chessengine.helper.PawnHelper.BLACK_PAWN_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.PawnHelper.WHITE_PAWN_POSITIONAL_VALUES;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PawnStructureModuleTest {

    @Test
    void shouldMatchLegacyTotalsForInitialPosition() {
        BitBoard bitBoard = new BitBoard();
        PawnStructureModule module = new PawnStructureModule();
        module.initialize(bitBoard);

        assertEquals(expectedMidgame(bitBoard, Color.WHITE), module.getMidgameContribution(Color.WHITE));
        assertEquals(expectedEndgame(bitBoard, Color.WHITE), module.getEndgameContribution(Color.WHITE));

        assertEquals(expectedMidgame(bitBoard, Color.BLACK), module.getMidgameContribution(Color.BLACK));
        assertEquals(expectedEndgame(bitBoard, Color.BLACK), module.getEndgameContribution(Color.BLACK));
    }

    @Test
    void shouldScalePassedPawnsPerPhase() {
        BitBoard bitBoard = createBoardWithWhitePassedPawn();
        PawnStructureModule module = new PawnStructureModule();
        module.initialize(bitBoard);

        int expectedMidgame = expectedMidgame(bitBoard, Color.WHITE);
        int expectedEndgame = expectedEndgame(bitBoard, Color.WHITE);

        assertEquals(expectedMidgame, module.getMidgameContribution(Color.WHITE));
        assertEquals(expectedEndgame, module.getEndgameContribution(Color.WHITE));

        int expectedDifference = PawnStructureModule.PASSED_PAWN_ENDGAME_BONUS
                - PawnStructureModule.PASSED_PAWN_MIDGAME_BONUS;
        assertEquals(expectedDifference,
                module.getEndgameContribution(Color.WHITE) - module.getMidgameContribution(Color.WHITE));
    }

    private BitBoard createBoardWithWhitePassedPawn() {
        long whitePawn = 1L << BitHelper.bitIndex('e', 5);
        long whiteKing = 1L << BitHelper.bitIndex('e', 1);
        long blackKing = 1L << BitHelper.bitIndex('e', 8);

        long whitePieces = whitePawn | whiteKing;
        long blackPieces = blackKing;

        return new BitBoard(
                true,
                whitePawn,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                whiteKing,
                blackKing,
                whitePieces,
                blackPieces,
                whitePieces | blackPieces,
                0,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false
        );
    }

    private int expectedMidgame(BitBoard bitBoard, Color color) {
        PhaseExpectation expectation = expectation(bitBoard, color);
        return expectation.midgame();
    }

    private int expectedEndgame(BitBoard bitBoard, Color color) {
        PhaseExpectation expectation = expectation(bitBoard, color);
        return expectation.endgame();
    }

    private PhaseExpectation expectation(BitBoard bitBoard, Color color) {
        long pawns = color == Color.WHITE ? bitBoard.getWhitePawns() : bitBoard.getBlackPawns();
        long opponentPawns = color == Color.WHITE ? bitBoard.getBlackPawns() : bitBoard.getWhitePawns();
        long rooks = color == Color.WHITE ? bitBoard.getWhiteRooks() : bitBoard.getBlackRooks();
        long allPawns = bitBoard.getWhitePawns() | bitBoard.getBlackPawns();

        int center = PawnHelper.countCenterPawns(pawns) * PawnStructureModule.CENTER_PAWN_BONUS;
        int doubled = PawnHelper.countDoubledPawns(pawns) * PawnStructureModule.DOUBLED_PAWN_PENALTY;
        int isolated = PawnHelper.countIsolatedPawns(pawns) * PawnStructureModule.ISOLATED_PAWN_PENALTY;
        int positional = applyPositionalValues(pawns,
                color == Color.WHITE ? WHITE_PAWN_POSITIONAL_VALUES : BLACK_PAWN_POSITIONAL_VALUES);
        int halfOpen = RookHelper.countRooksOnHalfOpenFiles(rooks, pawns, opponentPawns)
                * PawnStructureModule.ROOK_HALF_OPEN_FILE_BONUS;
        int open = RookHelper.countRooksOnOpenFiles(rooks, allPawns)
                * PawnStructureModule.ROOK_OPEN_FILE_BONUS;
        int passed = countPassedPawns(pawns, opponentPawns, color);

        int midgame = center + doubled + isolated + positional + halfOpen + open
                + passed * PawnStructureModule.PASSED_PAWN_MIDGAME_BONUS;
        int endgame = center + doubled + isolated + positional + halfOpen + open
                + passed * PawnStructureModule.PASSED_PAWN_ENDGAME_BONUS;
        return new PhaseExpectation(midgame, endgame);
    }

    private int applyPositionalValues(long bitboard, int[] positionalValues) {
        if (bitboard == 0L) {
            return 0;
        }
        int score = 0;
        int start = Long.numberOfTrailingZeros(bitboard);
        int end = 64 - Long.numberOfLeadingZeros(bitboard);
        for (int i = start; i < end; i++) {
            long mask = 1L << i;
            if ((bitboard & mask) != 0) {
                score += positionalValues[i];
            }
        }
        return score;
    }

    private int countPassedPawns(long pawns, long opponentPawns, Color color) {
        int passed = 0;
        long remaining = pawns;
        while (remaining != 0) {
            int square = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            if (isPassedPawn(square, opponentPawns, color)) {
                passed++;
            }
        }
        return passed;
    }

    private boolean isPassedPawn(int square, long opponentPawns, Color color) {
        int file = square % 8;
        int rank = square / 8;
        long mask = 0L;

        if (color == Color.WHITE) {
            for (int r = rank + 1; r < 8; r++) {
                mask |= BitHelper.RankMasks[r] & BitHelper.FileMasks[file];
                if (file > 0) {
                    mask |= BitHelper.RankMasks[r] & BitHelper.FileMasks[file - 1];
                }
                if (file < 7) {
                    mask |= BitHelper.RankMasks[r] & BitHelper.FileMasks[file + 1];
                }
            }
        } else {
            for (int r = rank - 1; r >= 0; r--) {
                mask |= BitHelper.RankMasks[r] & BitHelper.FileMasks[file];
                if (file > 0) {
                    mask |= BitHelper.RankMasks[r] & BitHelper.FileMasks[file - 1];
                }
                if (file < 7) {
                    mask |= BitHelper.RankMasks[r] & BitHelper.FileMasks[file + 1];
                }
            }
        }

        return (opponentPawns & mask) == 0;
    }

    private record PhaseExpectation(int midgame, int endgame) { }
}
