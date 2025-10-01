package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static julius.game.chessengine.utils.Score.CHECKMATE;

/**
 * One search "generation": workers share this while searching the same root position.
 * Tracks best-so-far and provides stop/await controls.
 */
public final class SearchTask {
    private final long id;
    private final long boardHash;
    private final boolean whiteToMove;
    private final long deadline;
    private final CountDownLatch completion;
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private final AtomicReference<BestMoveDepth> best;
    private final AtomicInteger iterationDepth = new AtomicInteger(0);
    private final Engine rootSnapshot;

    SearchTask(long id, long boardHash, boolean whiteToMove, long deadline, int threadCount, Engine rootSnapshot) {
        this.id = id;
        this.boardHash = boardHash;
        this.whiteToMove = whiteToMove;
        this.deadline = deadline;
        this.completion = new CountDownLatch(Math.max(1, threadCount));
        double initialScore = whiteToMove ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        this.best = new AtomicReference<>(new BestMoveDepth(-1, initialScore, 0));
        this.rootSnapshot = rootSnapshot;
    }

    long getId() { return id; }
    long getBoardHash() { return boardHash; }
    boolean isWhiteToMove() { return whiteToMove; }
    long getDeadline() { return deadline; }
    BestMoveDepth getBest() { return best.get(); }
    Engine getRootSnapshot() { return rootSnapshot; }

    void workerDone() { completion.countDown(); }
    void requestStop() { stop.set(true); }
    boolean isStopRequested() { return stop.get(); }

    boolean beginIteration(int depth) {
        while (true) {
            int current = iterationDepth.get();
            if (current >= depth) {
                return false;
            }
            if (iterationDepth.compareAndSet(current, depth)) {
                return true;
            }
        }
    }

    void awaitCompletion() {
        try {
            // Wake periodically so we can notice interrupts or deadline expiry
            while (!completion.await(50, TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted()) return;
                if (System.nanoTime() >= deadline) { stop.set(true); return; }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Called by a worker after finishing an ID iteration at some depth.
     * If it's better (or equal score but deeper), publish it.
     * Returns true if best was updated.
     */
    boolean publishBest(MoveAndScore ms, int depth, Engine simulator /*not used; kept for API compatibility*/) {
        if (ms == null || ms.move == -1) return false;

        while (true) {
            BestMoveDepth cur = best.get();
            if (depth < cur.depth) return false;

            boolean deeperIteration = depth > cur.depth;
            boolean betterAtSameDepth = depth == cur.depth && isBetterScore(whiteToMove, ms.score, cur.score);
            if (!deeperIteration && !betterAtSameDepth) return false;

            BestMoveDepth next = new BestMoveDepth(ms.move, ms.score, depth);
            if (best.compareAndSet(cur, next)) {
                if (isFailHardMate(ms.score)) requestStop();
                return true;
            }
        }
    }

    // --- Local helpers (no dependency on AI) ---
    private static boolean isBetterScore(boolean whiteToMove, double score, double bestScore) {
        return whiteToMove ? score > bestScore : score < bestScore;
    }
    private static boolean isFailHardMate(double score) {
        return Math.abs(score) >= CHECKMATE - 50;
    }
}
