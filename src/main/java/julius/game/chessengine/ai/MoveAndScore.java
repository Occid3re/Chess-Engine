package julius.game.chessengine.ai;

public class MoveAndScore {

    int move;
    double score;

    MoveAndScore(int move, double score) {
        this.move = move;
        this.score = score;
    }

    public int getMove() {
        return move;
    }

    public double getScore() {
        return score;
    }

}