package julius.game.chessengine.controller;

import julius.game.chessengine.engine.GameState;
import lombok.Data;

@Data
public class BoardState {

    GameState gameState;
    String move;
    double score;
    String lastMove;

}