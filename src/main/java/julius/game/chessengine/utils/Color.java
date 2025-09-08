package julius.game.chessengine.utils;

public enum Color {
    WHITE, BLACK;

    public static Color getOpponentColor(Color color) {
        return color == WHITE ? BLACK : WHITE;
    }

    public static Color fromString(String color) {
        if (color == null) {
            throw new IllegalArgumentException("Color string cannot be null");
        }
        switch (color.toUpperCase()) {
            case "WHITE":
                return WHITE;
            case "BLACK":
                return BLACK;
            default:
                throw new IllegalArgumentException("Unknown color: " + color);
        }
    }

    public Color opponent() {
        return this == WHITE ? BLACK : WHITE;
    }
}
