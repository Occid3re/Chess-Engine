package julius.game.chessengine.tablebase;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.syzygy.SyzygyMove;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TestSyzygySupport;
import julius.game.chessengine.syzygy.bridge.SyzygyBridge;
import julius.game.chessengine.utils.Color;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static julius.game.chessengine.board.MoveHelper.convertIndexToString;
import static julius.game.chessengine.board.MoveHelper.convertStringToIndex;
import static org.assertj.core.api.Assertions.assertThat;

class SyzygyRealIntegrationTest {

    private static final String KNIGHT_BISHOP_VS_KING_PAWN_FEN = "3k4/4p3/8/2K5/8/3BN3/8/8 w - - 0 1";

    @Test
    void knightBishopVsKingPawnProbesThroughRealTables() {
        TablebaseTestSupport.assumeSyzygyConfigured();
        Assumptions.assumeTrue(SyzygyBridge.isLibLoaded(), "Syzygy native library not loaded");

        SyzygyTablebaseService service = TestSyzygySupport.maybeCreateServiceFromConfiguration()
                .orElseThrow(() -> new IllegalStateException("Syzygy service not configured despite assumption"));
        service.ensureReady();

        Assumptions.assumeTrue(service.getEffectiveMaxPieces() > 0,
                "Syzygy tablebases were not loaded for the configured directories");

        Engine engine = new Engine();
        engine.importBoardFromFen(KNIGHT_BISHOP_VS_KING_PAWN_FEN);

        BitBoard board = engine.getBitBoard();
        int pieceCount = Long.bitCount(board.getAllPieces());
        Assumptions.assumeTrue(service.getEffectiveMaxPieces() >= pieceCount,
                () -> "Configured Syzygy tables only cover " + service.getEffectiveMaxPieces() + " pieces");

        SyzygyProbeResult probe = requireProbe(service, board, KNIGHT_BISHOP_VS_KING_PAWN_FEN);
        assertThat(probe.wdl()).isEqualTo(SyzygyWdl.WIN);
        assertThat(probe.dtz()).hasValue(7);
        assertThat(probe.dtm()).isEmpty();

        assertThat(probe.recommendedMove()).hasValueSatisfying(move -> {
            assertThat(move.fromIndex()).isEqualTo(square("c5"));
            assertThat(move.toIndex()).isEqualTo(square("c6"));
            assertThat(move.promotionPieceTypeBits()).isZero();
        });

        Color sideToMove = board.isWhitesTurn() ? Color.WHITE : Color.BLACK;
        Color expectedWinner = winnerFor(probe.wdl(), sideToMove)
                .orElseThrow(() -> new AssertionError("Expected decisive Syzygy outcome for " + KNIGHT_BISHOP_VS_KING_PAWN_FEN));

        followRecommendedLine(service, engine, probe, expectedWinner);

        assertThat(service.getEffectiveMaxPieces())
                .isEqualTo(Math.min(service.getConfiguredMaxPieces(), SyzygyBridge.getSupportedSize()));
    }

    private static int square(String algebraic) {
        return convertStringToIndex(algebraic);
    }

    private static void followRecommendedLine(SyzygyTablebaseService service, Engine engine,
                                              SyzygyProbeResult initialProbe, Color expectedWinner) {
        SyzygyProbeResult probe = initialProbe;
        assertOutcomeMatches(probe, engine.getBitBoard(), expectedWinner);

        int plies = 0;
        while (!engine.getGameState().isTerminal()) {
            SyzygyMove suggestion = probe.recommendedMove()
                    .orElseThrow(() -> new AssertionError("Syzygy recommendation missing before terminal state"));

            IntArrayList legalMoves = engine.getAllLegalMoves();
            int encoded = findMatchingMove(legalMoves, suggestion);

            assertThat(encoded)
                    .describedAs("Syzygy suggestion %s should be legal (fen=%s)",
                            suggestion, engine.translateBoardToFen().getRenderBoard())
                    .isGreaterThanOrEqualTo(0);

            String beforeFen = engine.translateBoardToFen().getRenderBoard();
            String fromSquare = convertIndexToString(suggestion.fromIndex());
            String toSquare = convertIndexToString(suggestion.toIndex());
            String movingPiece = engine.buildRenderBoard().get(fromSquare);

            assertThat(movingPiece)
                    .describedAs("Expected a piece on %s before applying Syzygy move (fen=%s)",
                            fromSquare, beforeFen)
                    .isNotNull();

            engine.performMove(encoded);

            String currentFen = engine.translateBoardToFen().getRenderBoard();
            assertThat(engine.buildRenderBoard().get(toSquare))
                    .describedAs("Expected moved piece to occupy %s after applying Syzygy move (fen=%s)",
                            toSquare, currentFen)
                    .isEqualTo(movingPiece);

            probe = requireProbe(service, engine.getBitBoard(), currentFen);
            assertOutcomeMatches(probe, engine.getBitBoard(), expectedWinner);

            assertThat(++plies)
                    .describedAs("Syzygy recommendation loop exceeded safety bound (fen=%s)", currentFen)
                    .isLessThan(256);
        }
    }

    private static SyzygyProbeResult requireProbe(SyzygyTablebaseService service, BitBoard board, String fen) {
        Optional<SyzygyProbeResult> result = service.probe(board);
        assertThat(result)
                .describedAs("Expected real Syzygy result for %s", fen)
                .isPresent();
        return result.get();
    }

    private static void assertOutcomeMatches(SyzygyProbeResult probe, BitBoard board, Color expectedWinner) {
        Optional<Color> actualWinner = winnerFor(probe.wdl(), board.isWhitesTurn() ? Color.WHITE : Color.BLACK);
        assertThat(actualWinner)
                .describedAs("Syzygy outcome mismatch: expected %s to stay ahead (wdl=%s)", expectedWinner, probe.wdl())
                .contains(expectedWinner);
    }

    private static Optional<Color> winnerFor(SyzygyWdl wdl, Color sideToMove) {
        return switch (wdl) {
            case WIN, CURSED_WIN -> Optional.of(sideToMove);
            case LOSS, BLESSED_LOSS -> Optional.of(sideToMove.opponent());
            case DRAW -> Optional.empty();
            case UNKNOWN -> throw new IllegalStateException("Unexpected Syzygy WDL: " + wdl);
        };
    }

    private static int findMatchingMove(IntArrayList legalMoves, SyzygyMove suggestion) {
        for (int i = 0; i < legalMoves.size(); i++) {
            int candidate = legalMoves.getInt(i);
            if (MoveHelper.deriveFromIndex(candidate) == suggestion.fromIndex()
                    && MoveHelper.deriveToIndex(candidate) == suggestion.toIndex()
                    && MoveHelper.derivePromotionPieceTypeBits(candidate) == suggestion.promotionPieceTypeBits()) {
                return candidate;
            }
        }
        return -1;
    }
}
