package julius.game.chessengine.ai;

/**
 * Container for the outcome of a single root-level search attempt.
 * Indicates whether the iteration finished evaluating all scheduled
 * root moves (within the aspiration/alpha-beta framework) and keeps
 * the best candidate move, if any was produced.
 */
final class RootSearchResult {

    private final MoveAndScore bestMove;
    private final boolean completed;

    private RootSearchResult(MoveAndScore bestMove, boolean completed) {
        this.bestMove = bestMove;
        this.completed = completed;
    }

    static RootSearchResult completed(MoveAndScore bestMove) {
        return new RootSearchResult(bestMove, true);
    }

    static RootSearchResult aborted(MoveAndScore bestMove) {
        return new RootSearchResult(bestMove, false);
    }

    MoveAndScore bestMove() {
        return bestMove;
    }

    boolean hasCandidate() {
        return bestMove != null;
    }

    boolean isCompleted() {
        return completed;
    }
}

