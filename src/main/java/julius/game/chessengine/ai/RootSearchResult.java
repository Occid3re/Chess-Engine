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
    private final NodeType boundType;

    private RootSearchResult(MoveAndScore bestMove, boolean completed, NodeType boundType) {
        this.bestMove = bestMove;
        this.completed = completed;
        this.boundType = boundType;
    }

    static RootSearchResult completed(MoveAndScore bestMove) {
        return new RootSearchResult(bestMove, true, NodeType.EXACT);
    }

    static RootSearchResult completed(MoveAndScore bestMove, NodeType boundType) {
        return new RootSearchResult(bestMove, true, boundType);
    }

    static RootSearchResult aborted(MoveAndScore bestMove) {
        return new RootSearchResult(bestMove, false, null);
    }

    MoveAndScore bestMove() {
        return bestMove;
    }

    boolean hasCandidate() {
        return bestMove != null;
    }

    boolean hasExactCandidate() {
        return completed && bestMove != null && boundType == NodeType.EXACT;
    }

    boolean isCompleted() {
        return completed;
    }

    NodeType boundType() {
        return boundType;
    }
}

