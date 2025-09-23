package julius.game.chessengine.board;

import java.util.Map;

public class FrontendBoard {
    private Map<String, String> renderBoard;  // { "e2":"wP", ... }
    private String fen;                       // convenient if you need it
    private String enPassantTarget;           // e.g. "e3" or null

    public Map<String, String> getRenderBoard() {
        return renderBoard;
    }

    public void setRenderBoard(Map<String, String> renderBoard) {
        this.renderBoard = renderBoard;
    }

    public String getFen() {
        return fen;
    }

    public void setFen(String fen) {
        this.fen = fen;
    }

    public String getEnPassantTarget() {
        return enPassantTarget;
    }

    public void setEnPassantTarget(String enPassantTarget) {
        this.enPassantTarget = enPassantTarget;
    }

    @Override
    public String toString() {
        return "FrontendBoard{" +
                "renderBoard=" + renderBoard +
                ", fen='" + fen + '\'' +
                ", enPassantTarget='" + enPassantTarget + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FrontendBoard that)) return false;
        if (renderBoard != null ? !renderBoard.equals(that.renderBoard) : that.renderBoard != null) return false;
        if (fen != null ? !fen.equals(that.fen) : that.fen != null) return false;
        return enPassantTarget != null ? enPassantTarget.equals(that.enPassantTarget) : that.enPassantTarget == null;
    }

    @Override
    public int hashCode() {
        int result = renderBoard != null ? renderBoard.hashCode() : 0;
        result = 31 * result + (fen != null ? fen.hashCode() : 0);
        result = 31 * result + (enPassantTarget != null ? enPassantTarget.hashCode() : 0);
        return result;
    }
}