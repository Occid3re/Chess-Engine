package julius.game.chessengine.figures;

import julius.game.chessengine.board.Position;
import julius.game.chessengine.utils.Color;

public class Figure {
    private PieceType pieceType;
    private Color color;
    private Position position;

    public Figure(PieceType pieceType, Color color, Position position) {
        this.pieceType = pieceType;
        this.color = color;
        this.position = position;
    }

    public PieceType getPieceType() {
        return pieceType;
    }

    public void setPieceType(PieceType pieceType) {
        this.pieceType = pieceType;
    }

    public Color getColor() {
        return color;
    }

    public void setColor(Color color) {
        this.color = color;
    }

    public Position getPosition() {
        return position;
    }

    public void setPosition(Position position) {
        this.position = position;
    }

    @Override
    public String toString() {
        return "Figure{" +
                "pieceType=" + pieceType +
                ", color=" + color +
                ", position=" + position +
                '}';
    }
}
