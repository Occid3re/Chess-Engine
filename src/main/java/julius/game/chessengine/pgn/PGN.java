package julius.game.chessengine.pgn;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class PGN {
    private String pgn;

    public PGN() {
        this.pgn = "";
    }

    public PGN(String pgn) {
        this.pgn = pgn;
    }

}
