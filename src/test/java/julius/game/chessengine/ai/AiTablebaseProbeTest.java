package julius.game.chessengine.ai;

import julius.game.chessengine.board.FEN;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.syzygy.TestSyzygyTablebaseService;
import julius.game.chessengine.tuning.AiTuning;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AiTablebaseProbeTest {

    @AfterEach
    void tearDown() {
        Score.clearTablebaseService();
    }

    @Test
    void resolveTablebaseHitUsesSnapshotWhenBoardMutatesConcurrently() throws Exception {
        String fen = "6k1/8/8/8/8/8/5K2/8 w - - 0 1";
        SyzygyProbeResult probe = new SyzygyProbeResult(SyzygyWdl.DRAW, OptionalInt.empty(), OptionalInt.empty(), Optional.empty());
        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(Map.of(fen, probe));

        Engine engine = new Engine();
        engine.importBoardFromFen(fen);
        AI ai = new AI(engine, AiTuning.defaults(), service);
        Score.clearTablebaseService();

        Method resolve = AI.class.getDeclaredMethod("resolveTablebaseHit", Engine.class, boolean.class);
        resolve.setAccessible(true);

        Field clientField = SyzygyTablebaseService.class.getDeclaredField("client");
        clientField.setAccessible(true);
        Object delegateClient = clientField.get(service);

        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);

        Object proxy = Proxy.newProxyInstance(
                delegateClient.getClass().getClassLoader(),
                delegateClient.getClass().getInterfaces(),
                (proxyInstance, method, args) -> {
                    if ("probe".equals(method.getName())) {
                        probeStarted.countDown();
                        try {
                            if (!releaseProbe.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timed out waiting to release probe");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                    }
                    method.setAccessible(true);
                    return method.invoke(delegateClient, args);
                });
        clientField.set(service, proxy);

        String expectedFen = FEN.translateBoardToFEN(engine.getBitBoard(), engine.getGameState()).getRenderBoard();

        CompletableFuture<Optional<?>> future = CompletableFuture.supplyAsync(() -> {
            try {
                return (Optional<?>) resolve.invoke(ai, engine, engine.whitesTurn());
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(probeStarted.await(5, TimeUnit.SECONDS)).isTrue();
        int move = engine.getAllLegalMoves().getInt(0);
        engine.performMove(move);
        releaseProbe.countDown();

        Optional<?> hit;
        try {
            hit = future.get(5, TimeUnit.SECONDS);
        } finally {
            engine.undoLastMove();
            clientField.set(service, delegateClient);
        }

        assertThat(hit).isPresent();
        assertThat(service.getProbedFens()).isNotEmpty();
        assertThat(service.getProbedFens().get(0)).isEqualTo(expectedFen);
    }

    @Test
    void probeMissClearsCachedTablebaseResults() throws Exception {
        String winningFen = "6k1/8/8/8/8/8/5K2/6Q1 w - - 0 1";
        String missFen = "6k1/8/8/8/8/8/5K2/6Q1 b - - 0 1";
        SyzygyProbeResult winProbe = new SyzygyProbeResult(SyzygyWdl.WIN, OptionalInt.of(1), OptionalInt.of(3), Optional.empty());
        TestSyzygyTablebaseService service = TestSyzygyTablebaseService.fromResponses(Map.of(winningFen, winProbe));

        Engine engine = new Engine();
        engine.importBoardFromFen(winningFen);
        AI ai = new AI(engine, AiTuning.defaults(), service);

        TablebaseResult winningResult = TablebaseResult.from(winProbe);
        TablebaseResult staleResult = new TablebaseResult(
                SyzygyWdl.LOSS, OptionalInt.of(5), OptionalInt.empty(), Optional.empty());

        Method resolve = AI.class.getDeclaredMethod("resolveTablebaseHit", Engine.class, boolean.class);
        resolve.setAccessible(true);
        Method probeMove = AI.class.getDeclaredMethod("probeMoveTablebase", Engine.class, int.class);
        probeMove.setAccessible(true);
        Method evaluateChild = AI.class.getDeclaredMethod("evaluateTablebaseChild", Engine.class, boolean.class);
        evaluateChild.setAccessible(true);
        Field tbField = AI.class.getDeclaredField("tbTieBreak");
        tbField.setAccessible(true);
        tbField.setBoolean(ai, true);

        Optional<?> hit = (Optional<?>) resolve.invoke(ai, engine, true);
        assertThat(hit).isPresent();
        assertThat(engine.getGameState().getLastTablebaseResult()).contains(winningResult);

        engine.importBoardFromFen(missFen);
        engine.getGameState().setLastTablebaseResult(staleResult);
        Optional<?> miss = (Optional<?>) resolve.invoke(ai, engine, false);
        assertThat(miss).isEmpty();
        assertThat(engine.getGameState().getLastTablebaseResult()).isEmpty();

        engine.importBoardFromFen(winningFen);
        engine.getGameState().setLastTablebaseResult(staleResult);
        int move = engine.getAllLegalMoves().getInt(0);
        Object info = probeMove.invoke(ai, engine, move);
        assertThat(info).isNull();
        assertThat(engine.getGameState().getLastTablebaseResult()).isEmpty();

        engine.importBoardFromFen(winningFen);
        int childMove = engine.getAllLegalMoves().getInt(0);
        engine.performMove(childMove);
        engine.getGameState().setLastTablebaseResult(staleResult);
        double childEval = (double) evaluateChild.invoke(ai, engine, true);
        engine.undoLastMove();

        assertThat(childEval).isNaN();
        assertThat(engine.getGameState().getLastTablebaseResult()).contains(winningResult);
    }
}
