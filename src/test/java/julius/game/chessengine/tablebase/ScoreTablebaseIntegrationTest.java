package julius.game.chessengine.tablebase;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.syzygy.TestSyzygyTablebaseService;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScoreTablebaseIntegrationTest {

    @Test
    void scoreUsesExactTablebaseProbeWhenAvailable() {
        TablebaseTestSupport.assumeSyzygyConfigured();

        SyzygyProbeResult probe = new SyzygyProbeResult(SyzygyWdl.WIN, OptionalInt.of(3), OptionalInt.of(7), Optional.empty());
        SyzygyTablebaseService service = mock(SyzygyTablebaseService.class);
        when(service.probe(any(BitBoard.class))).thenReturn(Optional.of(probe));

        BitBoard board = FEN.translateFENtoBitBoard("8/8/8/8/8/8/5K2/6k1 w - - 0 1");
        TablebaseResult expected = TablebaseResult.from(probe);
        int expectedCentipawn = Score.tablebaseToCentipawn(expected, true);

        try (TablebaseTestSupport.TablebaseServiceRestorer restorer = TablebaseTestSupport.overrideScoreTablebase(service)) {
            Score score = new Score();
            score.refresh(board, GameStateEnum.PLAY);

            verify(service).probe(board);

            assertThat(score.getTablebaseResult()).contains(expected);
            assertThat(score.getTablebaseCentipawnScore()).hasValue(expectedCentipawn);
            assertThat(score.getBlendedScore()).isEqualTo(expectedCentipawn);
            assertThat(score.getScoreDifference()).isEqualTo(expectedCentipawn / 100.0);
        }
    }

    @Test
    void scoreSkipsProbeWhenBoardExceedsServiceLimit() {
        BitBoard board = FEN.translateFENtoBitBoard("6k1/8/8/8/8/8/PPPP4/6K1 w - - 0 1");
        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(Map.of(), 5);

        try (TablebaseTestSupport.TablebaseServiceRestorer restorer = TablebaseTestSupport.overrideScoreTablebase(service)) {
            Score score = new Score();
            score.refresh(board, GameStateEnum.PLAY);
        }

        assertThat(service.getProbedFens()).isEmpty();
    }

    @Test
    void emptyProbeClearsCachedTablebaseState() {
        String winningFen = "8/8/8/8/8/8/5K2/6k1 w - - 0 1";
        String followUpFen = "8/8/8/8/8/8/5k2/6K1 b - - 0 1";
        BitBoard initial = FEN.translateFENtoBitBoard(winningFen);
        BitBoard followUp = FEN.translateFENtoBitBoard(followUpFen);

        SyzygyProbeResult probe = new SyzygyProbeResult(SyzygyWdl.WIN, OptionalInt.of(3), OptionalInt.of(7), Optional.empty());
        SyzygyTablebaseService service = mock(SyzygyTablebaseService.class);
        when(service.probe(any(BitBoard.class))).thenReturn(Optional.of(probe), Optional.empty());

        TablebaseResult expected = TablebaseResult.from(probe);
        int expectedCentipawn = Score.tablebaseToCentipawn(expected, true);

        try (TablebaseTestSupport.TablebaseServiceRestorer restorer = TablebaseTestSupport.overrideScoreTablebase(service)) {
            Score score = new Score();
            score.refresh(initial, GameStateEnum.PLAY);

            assertThat(score.getTablebaseResult()).contains(expected);
            assertThat(score.getTablebaseCentipawnScore()).hasValue(expectedCentipawn);

            score.refresh(followUp, GameStateEnum.PLAY);

            assertThat(score.getTablebaseResult()).isEmpty();
            assertThat(score.getTablebaseCentipawnScore()).isEmpty();
            assertThat(score.getBlendedScore()).isNotEqualTo(expectedCentipawn);
        }

        verify(service, times(2)).probe(any(BitBoard.class));
    }

    @Test
    void tablebaseCentipawnRespectsSideToMove() {
        TablebaseResult win = new TablebaseResult(SyzygyWdl.WIN, OptionalInt.of(1), OptionalInt.empty(), Optional.empty());
        TablebaseResult loss = new TablebaseResult(SyzygyWdl.LOSS, OptionalInt.of(1), OptionalInt.empty(), Optional.empty());

        assertThat(Score.tablebaseToCentipawn(win, true)).isGreaterThan(0);
        assertThat(Score.tablebaseToCentipawn(win, false)).isLessThan(0);

        assertThat(Score.tablebaseToCentipawn(loss, true)).isLessThan(0);
        assertThat(Score.tablebaseToCentipawn(loss, false)).isGreaterThan(0);
    }

    @Test
    void cursedWinEvaluationShrinksAsFiftyMoveClockExpires() {
        TablebaseResult earlyReset = new TablebaseResult(
                SyzygyWdl.CURSED_WIN, OptionalInt.of(4), OptionalInt.empty(), Optional.empty());
        TablebaseResult desperate = new TablebaseResult(
                SyzygyWdl.CURSED_WIN, OptionalInt.of(60), OptionalInt.empty(), Optional.empty());

        int generousClockScore = Score.tablebaseToCentipawn(earlyReset, true, 10);
        int expiringClockScore = Score.tablebaseToCentipawn(desperate, true, 90);
        int claimedDrawScore = Score.tablebaseToCentipawn(desperate, true, 100);

        assertThat(generousClockScore).isPositive();
        assertThat(expiringClockScore).isPositive();
        assertThat(generousClockScore).isGreaterThan(expiringClockScore);
        assertThat(claimedDrawScore).isZero();
    }

    @Test
    void blessedLossMirrorsCursedWinMagnitude() {
        TablebaseResult cursed = new TablebaseResult(
                SyzygyWdl.CURSED_WIN, OptionalInt.of(8), OptionalInt.of(40), Optional.empty());
        TablebaseResult blessed = new TablebaseResult(
                SyzygyWdl.BLESSED_LOSS, OptionalInt.of(8), OptionalInt.of(40), Optional.empty());

        int whitePerspective = Score.tablebaseToCentipawn(cursed, true, 0);
        int mirrored = Score.tablebaseToCentipawn(blessed, true, 0);

        assertThat(whitePerspective).isGreaterThan(0);
        assertThat(mirrored).isEqualTo(-whitePerspective);
    }

    @Test
    void scoreProbesWhenBoardWithinServiceLimit() {
        String fen = "6k1/8/8/8/8/8/PPP5/6K1 w - - 0 1";
        BitBoard board = FEN.translateFENtoBitBoard(fen);
        SyzygyProbeResult probe = new SyzygyProbeResult(SyzygyWdl.DRAW, OptionalInt.of(0), OptionalInt.empty(), Optional.empty());
        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(Map.of(fen, probe), 6);

        try (TablebaseTestSupport.TablebaseServiceRestorer restorer = TablebaseTestSupport.overrideScoreTablebase(service)) {
            Score score = new Score();
            score.refresh(board, GameStateEnum.PLAY);
        }

        assertThat(service.getProbedFens()).containsExactly(fen);
    }

    @Test
    void evaluationPipelineStaysAlignedAfterTablebaseBypassEnds() {
        String initialFen = "6rk/6pP/8/8/8/8/4B3/1N4K1 w - - 0 1";
        String tablebaseFen = "6Qk/6p1/8/8/8/8/4B3/1N4K1 b - - 0 1";

        BitBoard board = FEN.translateFENtoBitBoard(initialFen);

        SyzygyProbeResult probe = new SyzygyProbeResult(
                SyzygyWdl.WIN,
                OptionalInt.of(5),
                OptionalInt.empty(),
                Optional.empty());
        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(Map.of(tablebaseFen, probe));

        try (TablebaseTestSupport.TablebaseServiceRestorer ignored = TablebaseTestSupport.overrideScoreTablebase(service)) {
            Score score = Score.initializeScore(board);
            score.refresh(board, GameStateEnum.PLAY);

            assertThat(score.getTablebaseResult()).isEmpty();

            int promotionCapture = findMove(board, "h7", "g8", PieceType.QUEEN);
            board.performMove(promotionCapture);
            score.applyMove(board, promotionCapture, GameStateEnum.PLAY);

            TablebaseResult expected = TablebaseResult.from(probe);
            int expectedCentipawn = Score.tablebaseToCentipawn(expected, board.isWhitesTurn(), board.getHalfmoveClock());
            assertThat(score.getTablebaseResult()).contains(expected);
            assertThat(score.getTablebaseCentipawnScore()).hasValue(expectedCentipawn);

            int kingCapture = findMove(board, "h8", "g8", null);
            board.performMove(kingCapture);
            score.applyMove(board, kingCapture, GameStateEnum.PLAY);

            assertThat(score.getTablebaseResult()).isEmpty();
            assertThat(score.getTablebaseCentipawnScore()).isEmpty();

            Score baseline = Score.initializeScore(board);
            baseline.refresh(board, GameStateEnum.PLAY);

            assertThat(score.getMidgameScore()).isEqualTo(baseline.getMidgameScore());
            assertThat(score.getEndgameScore()).isEqualTo(baseline.getEndgameScore());
            assertThat(score.getBlendedScore()).isEqualTo(baseline.getBlendedScore());
        }
    }

    private static int findMove(BitBoard board, String from, String to, PieceType promotion) {
        int fromIndex = MoveHelper.convertStringToIndex(from);
        int toIndex = MoveHelper.convertStringToIndex(to);
        IntArrayList moves = board.getAllCurrentPossibleMoves();
        for (int i = 0; i < moves.size(); i++) {
            int candidate = moves.getInt(i);
            if (MoveHelper.deriveFromIndex(candidate) != fromIndex || MoveHelper.deriveToIndex(candidate) != toIndex) {
                continue;
            }
            int promotionBits = MoveHelper.derivePromotionPieceTypeBits(candidate);
            if (promotion == null && promotionBits == 0) {
                return candidate;
            }
            if (promotion != null && promotionBits == MoveHelper.pieceTypeToInt(promotion)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Move " + from + to + (promotion != null ? "=" + promotion : "") + " not found");
    }
}
