package julius.game.chessengine.tablebase;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.ai.AI;
import julius.game.chessengine.ai.MoveAndScore;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TestSyzygyTablebaseService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
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

    @Test
    void tablebaseWinningMoveAvoidsImmediateThreefoldDraw() throws Exception {
        String rootFen = "6K1/8/4B3/6P1/4P3/3P4/8/k7 w - - 19 69";

        Engine engine = new Engine();
        engine.importBoardFromFen(rootFen);
        engine.getGameState().refreshScore(engine.getBitBoard());

        IntArrayList legalMoves = engine.getAllLegalMoves();
        int kingShuffle = findMove(legalMoves, "g8", "h8");
        int pawnPush = findMove(legalMoves, "g5", "g6");

        Engine preview = engine.createSimulation();
        preview.performMove(kingShuffle);
        String kh8Fen = preview.translateBoardToFen().getRenderBoard();
        long kh8Hash = preview.getBoardStateHash();
        preview.undoLastMove();

        preview.performMove(pawnPush);
        String g6Fen = preview.translateBoardToFen().getRenderBoard();
        preview.undoLastMove();

        String normalisedRootFen = engine.translateBoardToFen().getRenderBoard();

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

        Map<String, SyzygyProbeResult> responses = new HashMap<>();
        responses.put(normalisedRootFen, parentWin);
        responses.put(kh8Fen, winningChild);
        responses.put(g6Fen, winningChild);

        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(responses);

        try (TablebaseTestSupport.TablebaseServiceRestorer ignored = TablebaseTestSupport.overrideScoreTablebase(service)) {
            engine.getGameState().getHashHistory().add(kh8Hash);
            engine.getGameState().getHashHistory().add(kh8Hash);
            engine.getGameState().getRepetition().put(kh8Hash, 2);
            engine.getGameState().setLastZobrist(engine.getBoardStateHash());

            Engine verification = engine.createSimulation();
            verification.performMove(kingShuffle);
            assertThat(verification.getBoardStateHash()).isEqualTo(kh8Hash);
            assertThat(verification.getGameState().isThreefoldRepetition()).isTrue();
            assertThat(verification.getGameState().getState()).isEqualTo(GameStateEnum.DRAW);
            verification.undoLastMove();

            AI ai = new AI(engine, service);
            ai.setMaxDepth(1);

            Engine probeEngine = engine.createSimulation();
            Method evalMethod = AI.class.getDeclaredMethod("evaluateTablebaseContinuation", Engine.class, int.class);
            evalMethod.setAccessible(true);
            Optional<?> continuationOpt = (Optional<?>) evalMethod.invoke(ai, probeEngine, kingShuffle);
            assertThat(continuationOpt).isPresent();
            Object continuation = continuationOpt.orElseThrow();
            Method claimableAccessor = continuation.getClass().getDeclaredMethod("claimableDraw");
            claimableAccessor.setAccessible(true);
            boolean claimable = (boolean) claimableAccessor.invoke(continuation);
            assertThat(claimable).isTrue();

            Engine nonDrawProbe = engine.createSimulation();
            Optional<?> g6Opt = (Optional<?>) evalMethod.invoke(ai, nonDrawProbe, pawnPush);
            assertThat(g6Opt).isPresent();
            Object g6Continuation = g6Opt.orElseThrow();
            boolean g6Claimable = (boolean) claimableAccessor.invoke(g6Continuation);
            assertThat(g6Claimable).isFalse();

            Method resolveHit = AI.class.getDeclaredMethod("resolveTablebaseHit", Engine.class, boolean.class);
            resolveHit.setAccessible(true);
            Optional<?> hitOpt = (Optional<?>) resolveHit.invoke(ai, engine.createSimulation(), true);
            assertThat(hitOpt).isPresent();
            Object hit = hitOpt.orElseThrow();
            Method bestMoveAccessor = hit.getClass().getDeclaredMethod("bestMove");
            bestMoveAccessor.setAccessible(true);
            int tbBestMove = (int) bestMoveAccessor.invoke(hit);
            String tbBest = MoveHelper.convertIndexToString(MoveHelper.deriveFromIndex(tbBestMove))
                    + MoveHelper.convertIndexToString(MoveHelper.deriveToIndex(tbBestMove));
            assertThat(tbBest).isEqualTo("g5g6");

            MoveAndScore best = ai.searchBestMoveBlocking(TimeUnit.MILLISECONDS.toMillis(50));

            assertThat(best).as("tablebase move should avoid immediate threefold").isNotNull();
            String from = MoveHelper.convertIndexToString(MoveHelper.deriveFromIndex(best.getMove()));
            String to = MoveHelper.convertIndexToString(MoveHelper.deriveToIndex(best.getMove()));
            assertThat(from + to).isEqualTo("g5g6");
            assertThat(best.getScore()).isGreaterThan(0);
        }
    }

    private static int findMove(IntArrayList moves, String from, String to) {
        int fromIndex = MoveHelper.convertStringToIndex(from);
        int toIndex = MoveHelper.convertStringToIndex(to);
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getInt(i);
            if (MoveHelper.deriveFromIndex(move) == fromIndex && MoveHelper.deriveToIndex(move) == toIndex) {
                return move;
            }
        }
        throw new IllegalStateException("Expected move " + from + to + " to be legal");
    }
}

