package julius.game.chessengine.ai;

import lombok.Getter;

@Getter
public class MoveAndScore {

    int move;
    double score;
    boolean tablebaseExact;

    MoveAndScore(int move, double score) {
        this(move, score, false);
    }

    MoveAndScore(int move, double score, boolean tablebaseExact) {
        this.move = move;
        this.score = score;
        this.tablebaseExact = tablebaseExact;
    }

}
