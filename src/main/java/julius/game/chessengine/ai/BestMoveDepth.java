package julius.game.chessengine.ai;

public final class BestMoveDepth {
    final int move;
    final double score;
    final int depth;
    BestMoveDepth(int move, double score, int depth) {
        this.move = move;
        this.score = score;
        this.depth = depth;
    }
}