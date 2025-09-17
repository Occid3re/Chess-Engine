package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.helper.BitHelper;
import julius.game.chessengine.utils.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScoreTest {

    @Test
    void calculateTotalScoreIncludesPawnStructureContribution() {
        BitBoard bitBoard = createBoardWithSinglePawn();

        Score score = new Score();
        score.initializePawnScore(bitBoard.getWhitePawns(), bitBoard.getBlackPawns());
        score.getPawnStructureModule().initialize(bitBoard);

        int expectedWhite = score.getWhitePawnsAmountScore()
                + score.getPawnStructureModule().getMidgameContribution(Color.WHITE);
        int expectedBlack = score.getBlackPawnsAmountScore()
                + score.getPawnStructureModule().getMidgameContribution(Color.BLACK);

        assertEquals(expectedWhite, score.calculateTotalWhiteScore());
        assertEquals(expectedBlack, score.calculateTotalBlackScore());
    }

    private BitBoard createBoardWithSinglePawn() {
        long whitePawn = 1L << BitHelper.bitIndex('e', 4);
        long blackPawn = 1L << BitHelper.bitIndex('d', 5);
        long whiteKing = 1L << BitHelper.bitIndex('e', 1);
        long blackKing = 1L << BitHelper.bitIndex('e', 8);

        long whitePieces = whitePawn | whiteKing;
        long blackPieces = blackPawn | blackKing;

        return new BitBoard(
                true,
                whitePawn,
                blackPawn,
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
}
