package julius.game.chessengine.pgn;

public class PGN {
    private String pgn;

    public PGN() {
        this.pgn = "";
    }

    public PGN(String pgn) {
        this.pgn = pgn;
    }

    public String getPgn() {
        return pgn;
    }

    public void setPgn(String pgn) {
        this.pgn = pgn;
    }

}
