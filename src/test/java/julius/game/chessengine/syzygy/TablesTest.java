package julius.game.chessengine.syzygy;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.syzygy.bridge.SyzygyBridge;
import julius.game.chessengine.syzygy.bridge.SyzygyConstants;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TablesTest {

    @Test
    void shouldPassZeroBasedEnPassantSquareToBridge() throws Exception {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1");
        Tables tables = instantiateTables();

        long expectedWhite = board.getWhitePieces();
        long expectedBlack = board.getBlackPieces();
        long expectedKings = board.getWhiteKing() | board.getBlackKing();
        long expectedQueens = board.getWhiteQueens() | board.getBlackQueens();
        long expectedRooks = board.getWhiteRooks() | board.getBlackRooks();
        long expectedBishops = board.getWhiteBishops() | board.getBlackBishops();
        long expectedKnights = board.getWhiteKnights() | board.getBlackKnights();
        long expectedPawns = board.getWhitePawns() | board.getBlackPawns();
        int expectedEp = board.getEnPassantTargetIndex();

        try (MockedStatic<SyzygyBridge> bridge = Mockito.mockStatic(SyzygyBridge.class)) {
            bridge.when(() -> SyzygyBridge.probeSyzygyWDL(expectedWhite, expectedBlack, expectedKings, expectedQueens,
                    expectedRooks, expectedBishops, expectedKnights, expectedPawns, expectedEp, board.isWhitesTurn()))
                    .thenReturn(SyzygyConstants.TB_WIN);
            bridge.when(() -> SyzygyBridge.probeSyzygyDTZ(expectedWhite, expectedBlack, expectedKings, expectedQueens,
                    expectedRooks, expectedBishops, expectedKnights, expectedPawns, board.getHalfmoveClock(), expectedEp,
                    board.isWhitesTurn())).thenReturn(SyzygyConstants.TB_RESULT_FAILED);

            Optional<SyzygyProbeResult> result = tables.probe(board);
            assertThat(result).isPresent();

            bridge.verify(() -> SyzygyBridge.probeSyzygyWDL(expectedWhite, expectedBlack, expectedKings, expectedQueens,
                    expectedRooks, expectedBishops, expectedKnights, expectedPawns, expectedEp, board.isWhitesTurn()));
        }
    }

    @Test
    void shouldDecodeRecommendedMoveUsingZeroBasedIndices() throws Exception {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/3pP3/8/8/8/4K3 w - - 0 1");
        Tables tables = instantiateTables();

        long white = board.getWhitePieces();
        long black = board.getBlackPieces();
        long kings = board.getWhiteKing() | board.getBlackKing();
        long queens = board.getWhiteQueens() | board.getBlackQueens();
        long rooks = board.getWhiteRooks() | board.getBlackRooks();
        long bishops = board.getWhiteBishops() | board.getBlackBishops();
        long knights = board.getWhiteKnights() | board.getBlackKnights();
        long pawns = board.getWhitePawns() | board.getBlackPawns();
        int halfmoveClock = board.getHalfmoveClock();
        boolean whiteToMove = board.isWhitesTurn();

        int from = 9; // c2
        int to = 17;  // b3
        int dtzValue = 7;
        int dtzRaw = (SyzygyConstants.TB_WIN << SyzygyConstants.TB_RESULT_WDL_SHIFT)
                | (to << SyzygyConstants.TB_RESULT_TO_SHIFT)
                | (from << SyzygyConstants.TB_RESULT_FROM_SHIFT)
                | (dtzValue << SyzygyConstants.TB_RESULT_DTZ_SHIFT);

        try (MockedStatic<SyzygyBridge> bridge = Mockito.mockStatic(SyzygyBridge.class)) {
            bridge.when(() -> SyzygyBridge.probeSyzygyWDL(white, black, kings, queens, rooks, bishops, knights, pawns, 0,
                    whiteToMove)).thenReturn(SyzygyConstants.TB_WIN);
            bridge.when(() -> SyzygyBridge.probeSyzygyDTZ(white, black, kings, queens, rooks, bishops, knights, pawns,
                    halfmoveClock, 0, whiteToMove)).thenReturn(dtzRaw);

            Optional<SyzygyProbeResult> result = tables.probe(board);
            assertThat(result).isPresent();
            SyzygyProbeResult probeResult = result.get();
            assertThat(probeResult.dtz()).hasValue(dtzValue);
            assertThat(probeResult.recommendedMove()).isPresent();
            SyzygyMove move = probeResult.recommendedMove().orElseThrow();
            assertThat(move.fromIndex()).isEqualTo(from);
            assertThat(move.toIndex()).isEqualTo(to);
            assertThat(move.promotionPieceTypeBits()).isEqualTo(0);
        }
    }

    @Test
    void shouldTreatCheckmateSentinelAsLossWithoutRecommendation() throws Exception {
        BitBoard board = FEN.translateFENtoBitBoard("7k/7p/8/8/8/8/7K/7Q b - - 0 1");
        Tables tables = instantiateTables();

        long white = board.getWhitePieces();
        long black = board.getBlackPieces();
        long kings = board.getWhiteKing() | board.getBlackKing();
        long queens = board.getWhiteQueens() | board.getBlackQueens();
        long rooks = board.getWhiteRooks() | board.getBlackRooks();
        long bishops = board.getWhiteBishops() | board.getBlackBishops();
        long knights = board.getWhiteKnights() | board.getBlackKnights();
        long pawns = board.getWhitePawns() | board.getBlackPawns();
        int halfmoveClock = board.getHalfmoveClock();
        boolean whiteToMove = board.isWhitesTurn();

        try (MockedStatic<SyzygyBridge> bridge = Mockito.mockStatic(SyzygyBridge.class)) {
            bridge.when(() -> SyzygyBridge.probeSyzygyWDL(white, black, kings, queens, rooks, bishops, knights, pawns, 0,
                    whiteToMove)).thenReturn(SyzygyConstants.TB_LOSS);
            bridge.when(() -> SyzygyBridge.probeSyzygyDTZ(white, black, kings, queens, rooks, bishops, knights, pawns,
                    halfmoveClock, 0, whiteToMove)).thenReturn(SyzygyConstants.TB_RESULT_CHECKMATE);

            Optional<SyzygyProbeResult> result = tables.probe(board);
            assertThat(result).isPresent();
            SyzygyProbeResult probeResult = result.get();
            assertThat(probeResult.wdl()).isEqualTo(SyzygyWdl.LOSS);
            assertThat(probeResult.dtz()).isEmpty();
            assertThat(probeResult.recommendedMove()).isEmpty();
        }
    }

    @Test
    void shouldTreatStalemateSentinelAsDrawWithoutRecommendation() throws Exception {
        BitBoard board = FEN.translateFENtoBitBoard("7k/5Q2/7K/8/8/8/8/8 b - - 0 1");
        Tables tables = instantiateTables();

        long white = board.getWhitePieces();
        long black = board.getBlackPieces();
        long kings = board.getWhiteKing() | board.getBlackKing();
        long queens = board.getWhiteQueens() | board.getBlackQueens();
        long rooks = board.getWhiteRooks() | board.getBlackRooks();
        long bishops = board.getWhiteBishops() | board.getBlackBishops();
        long knights = board.getWhiteKnights() | board.getBlackKnights();
        long pawns = board.getWhitePawns() | board.getBlackPawns();
        int halfmoveClock = board.getHalfmoveClock();
        boolean whiteToMove = board.isWhitesTurn();

        try (MockedStatic<SyzygyBridge> bridge = Mockito.mockStatic(SyzygyBridge.class)) {
            bridge.when(() -> SyzygyBridge.probeSyzygyWDL(white, black, kings, queens, rooks, bishops, knights, pawns, 0,
                    whiteToMove)).thenReturn(SyzygyConstants.TB_DRAW);
            bridge.when(() -> SyzygyBridge.probeSyzygyDTZ(white, black, kings, queens, rooks, bishops, knights, pawns,
                    halfmoveClock, 0, whiteToMove)).thenReturn(SyzygyConstants.TB_RESULT_STALEMATE);

            Optional<SyzygyProbeResult> result = tables.probe(board);
            assertThat(result).isPresent();
            SyzygyProbeResult probeResult = result.get();
            assertThat(probeResult.wdl()).isEqualTo(SyzygyWdl.DRAW);
            assertThat(probeResult.dtz()).isEmpty();
            assertThat(probeResult.recommendedMove()).isEmpty();
        }
    }

    private static Tables instantiateTables() throws Exception {
        Constructor<Tables> constructor = Tables.class.getDeclaredConstructor(String.class, int.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance("paths", 6, 6);
    }
}
