package julius.game.chessengine.figures;

import lombok.Getter;

@Getter
public enum PieceType {
    PAWN('P'), KNIGHT('N'), BISHOP('B'), ROOK('R'), QUEEN('Q'), KING('K');

    private final char notation;

    PieceType(char notation) {
        this.notation = notation;
    }

}
