package julius.game.chessengine.engine;

import julius.game.chessengine.board.MoveList;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class EngineConcurrencyTest {

    @Test
    void concurrentAccessDoesNotCorruptInitialPosition() throws InterruptedException {
        Engine reference = new Engine();
        MoveList referenceMoves = reference.getAllLegalMoves();
        int expectedMoveCount = referenceMoves.size();
        String expectedFen = reference.translateBoardToFen().getRenderBoard();
        long expectedHash = reference.getBoardStateHash();

        Engine engine = new Engine();
        engine.startNewGame();

        Assertions.assertEquals(expectedFen, engine.translateBoardToFen().getRenderBoard());
        Assertions.assertEquals(expectedHash, engine.getBoardStateHash());

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        final int iterations = 500;

        Thread legalMovesThread = new Thread(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    if (failure.get() != null) {
                        break;
                    }
                    MoveList moves = engine.getAllLegalMoves();
                    if (moves.size() != expectedMoveCount) {
                        failure.compareAndSet(null, new AssertionError("Unexpected legal move count"));
                        break;
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                doneLatch.countDown();
            }
        }, "legalMovesThread");

        Thread renderThread = new Thread(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    if (failure.get() != null) {
                        break;
                    }
                    Map<String, String> board = engine.buildRenderBoard();
                    if (board.size() != 32) {
                        failure.compareAndSet(null, new AssertionError("Unexpected piece count"));
                        break;
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                doneLatch.countDown();
            }
        }, "renderThread");

        legalMovesThread.start();
        renderThread.start();

        startLatch.countDown();

        Assertions.assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Timed out waiting for workers");

        Throwable workerFailure = failure.get();
        if (workerFailure != null) {
            Assertions.fail("Worker thread failed", workerFailure);
        }

        MoveList finalMoves = engine.getAllLegalMoves();
        Assertions.assertEquals(expectedMoveCount, finalMoves.size());
        Assertions.assertEquals(expectedFen, engine.translateBoardToFen().getRenderBoard());
        Assertions.assertEquals(expectedHash, engine.getBoardStateHash());
        Map<String, String> finalBoard = engine.buildRenderBoard();
        Assertions.assertEquals(32, finalBoard.size());
    }
}
