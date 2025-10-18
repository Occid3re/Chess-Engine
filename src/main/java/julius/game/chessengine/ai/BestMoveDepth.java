package julius.game.chessengine.ai;

public final class BestMoveDepth {
    final int move;
    final double score;
    final int depth;
    final boolean tablebaseExact;

    BestMoveDepth(int move, double score, int depth) {
        this(move, score, depth, false);
    }

    BestMoveDepth(int move, double score, int depth, boolean tablebaseExact) {
        this.move = move;
        this.score = score;
        this.depth = depth;
        this.tablebaseExact = tablebaseExact;
    }
}
