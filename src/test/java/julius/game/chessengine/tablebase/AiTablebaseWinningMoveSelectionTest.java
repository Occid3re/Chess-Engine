package julius.game.chessengine.tablebase;

import julius.game.chessengine.ai.AI;
import julius.game.chessengine.ai.MoveAndScore;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiTablebaseWinningMoveSelectionTest {

    @Test
    void tablebaseWinningMovePreferredWhenChildTurnsBlack() {
        SyzygyTablebaseService service = mock(SyzygyTablebaseService.class);
        when(service.getEffectiveMaxPieces()).thenReturn(6);

        long d4Mask = 1L << MoveHelper.convertStringToIndex("d4");
        long d5Mask = 1L << MoveHelper.convertStringToIndex("d5");
        long h7Mask = 1L << MoveHelper.convertStringToIndex("h7");
        long h8Mask = 1L << MoveHelper.convertStringToIndex("h8");

        SyzygyProbeResult parentWin = new SyzygyProbeResult(
                SyzygyWdl.WIN,
                OptionalInt.of(1),
                OptionalInt.empty(),
                Optional.empty());
        SyzygyProbeResult winningChild = new SyzygyProbeResult(
                SyzygyWdl.LOSS,
                OptionalInt.of(1),
                OptionalInt.empty(),
                Optional.empty());
        SyzygyProbeResult drawingChild = new SyzygyProbeResult(
                SyzygyWdl.DRAW,
                OptionalInt.empty(),
                OptionalInt.empty(),
                Optional.empty());

        when(service.probe(any(BitBoard.class))).thenAnswer(invocation -> {
            BitBoard board = invocation.getArgument(0);
            long whitePawns = board.getWhitePawns();
            long whiteKing = board.getWhiteKing();
            if ((whitePawns & d4Mask) != 0 && (whiteKing & h7Mask) != 0 && board.isWhitesTurn()) {
                return Optional.of(parentWin);
            }
            if ((whitePawns & d5Mask) != 0 && (whiteKing & h7Mask) != 0 && !board.isWhitesTurn()) {
                return Optional.of(winningChild);
            }
            if ((whiteKing & h8Mask) != 0) {
                return Optional.of(drawingChild);
            }
            return Optional.of(drawingChild);
        });

        try (TablebaseTestSupport.TablebaseServiceRestorer ignored = TablebaseTestSupport.overrideScoreTablebase(service)) {
            Engine engine = new Engine();
            engine.importBoardFromFen("8/7K/8/8/2kP4/8/8/7B w - - 2 61");
            engine.getGameState().refreshScore(engine.getBitBoard());

            AI ai = new AI(engine, service);
            ai.setMaxDepth(1);

            MoveAndScore best = ai.searchBestMoveBlocking(TimeUnit.MILLISECONDS.toMillis(50));

            assertThat(best).as("tablebase best move should be available").isNotNull();

            int moveInt = best.getMove();
            String from = MoveHelper.convertIndexToString(MoveHelper.deriveFromIndex(moveInt));
            String to = MoveHelper.convertIndexToString(MoveHelper.deriveToIndex(moveInt));

            assertThat(from + to).isEqualTo("d4d5");
            assertThat(best.getScore()).isGreaterThan(0);
        }
    }
}

