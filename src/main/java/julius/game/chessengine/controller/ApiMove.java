package julius.game.chessengine.controller;

import julius.game.chessengine.engine.GameStateEnum;
import lombok.Data;

@Data
public class ApiMove {
    GameStateEnum currentState;
    String from;
    String to;

    public ApiMove(GameStateEnum currentState, String from, String to) {
        this.currentState = currentState;
        this.from = from;
        this.to = to;
    }
}
