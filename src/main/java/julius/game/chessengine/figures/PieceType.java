package julius.game.chessengine.figures;

public enum PieceType {
    PAWN('P'), KNIGHT('N'), BISHOP('B'), ROOK('R'), QUEEN('Q'), KING('K');

    private final char notation;

    PieceType(char notation) {
        this.notation = notation;
    }

    public char getNotation() {
        return notation;
    }

}
