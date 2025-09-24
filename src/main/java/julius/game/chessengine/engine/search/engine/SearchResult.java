package julius.game.chessengine.engine.search.engine;

import julius.game.chessengine.ai.MoveAndScore;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of search progress/results produced by {@link SearchEngine}.
 */
public final class SearchResult {

    public static final SearchResult EMPTY = new SearchResult(-1, 0.0, List.of(), 0, 0L, 0L, List.of());

    private final int bestMove;
    private final double score;
    private final List<MoveAndScore> principalVariation;
    private final int depth;
    private final long nodes;
    private final long timeMillis;
    private final List<String> infoLines;

    public SearchResult(int bestMove,
                        double score,
                        List<MoveAndScore> principalVariation,
                        int depth,
                        long nodes,
                        long timeMillis,
                        List<String> infoLines) {
        this.bestMove = bestMove;
        this.score = score;
        this.principalVariation = Collections.unmodifiableList(principalVariation != null ? principalVariation : List.of());
        this.depth = Math.max(0, depth);
        this.nodes = Math.max(0L, nodes);
        this.timeMillis = Math.max(0L, timeMillis);
        this.infoLines = Collections.unmodifiableList(infoLines != null ? infoLines : List.of());
    }

    public int getBestMove() {
        return bestMove;
    }

    public double getScore() {
        return score;
    }

    public List<MoveAndScore> getPrincipalVariation() {
        return principalVariation;
    }

    public int getDepth() {
        return depth;
    }

    public long getNodes() {
        return nodes;
    }

    public long getTimeMillis() {
        return timeMillis;
    }

    public List<String> getInfoLines() {
        return infoLines;
    }

    public SearchResult withInfoLines(List<String> updatedInfoLines) {
        return new SearchResult(bestMove, score, principalVariation, depth, nodes, timeMillis, updatedInfoLines);
    }

    public SearchResult withStatistics(long nodes, long timeMillis, int depth) {
        return new SearchResult(bestMove, score, principalVariation, depth, nodes, timeMillis, infoLines);
    }

    public SearchResult withBest(MoveAndScore best, int depth, List<MoveAndScore> pv) {
        Objects.requireNonNull(best, "best");
        return new SearchResult(best.getMove(), best.getScore(), pv, depth, nodes, timeMillis, infoLines);
    }
}
