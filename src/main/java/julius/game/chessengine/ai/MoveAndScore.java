package julius.game.chessengine.ai;

import lombok.Getter;

@Getter
public class MoveAndScore {

    int move;
    double score;

    MoveAndScore(int move, double score) {
        this.move = move;
        this.score = score;
    }

}