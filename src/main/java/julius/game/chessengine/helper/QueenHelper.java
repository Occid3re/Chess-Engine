package julius.game.chessengine.helper;

public class QueenHelper {

    public static final int[] QUEEN_MIDGAME_POSITIONAL_VALUES = {
            // R1
            -2, -1, -1, -0, -0, -1, -1, -2,
            // R2
            -1, 0, 0, 0, 0, 0, 0, -1,
            // R3
            -1, 0, 0, 0, 0, 0, 0, -1,
            // R4
            -0, 0, 0, 0, 0, 0, 0, -0,
            // R0
            0, 0, 0, 0, 0, 0, 0, -0,
            // R6
            -1, 0, 0, 0, 0, 0, 0, -1,
            // R7
            -1, 0, 0, 0, 0, 0, 0, -1,
            // R8
            -2, -1, -1, -0, -0, -1, -1, -2
    };

    public static final int[] QUEEN_ENDGAME_POSITIONAL_VALUES = {
            // R1
            -2, -1, -1, -0, -0, -1, -1, -2,
            // R2
            -1, 0, 0, 0, 0, 0, 0, -1,
            // R3
            -1, 0, 0, 0, 0, 0, 0, -1,
            // R4
            -0, 0, 0, 0, 0, 0, 0, -0,
            // R0
            0, 0, 0, 0, 0, 0, 0, -0,
            // R6
            -1, 0, 0, 0, 0, 0, 0, -1,
            // R7
            -1, 0, 0, 0, 0, 0, 0, -1,
            // R8
            -2, -1, -1, -0, -0, -1, -1, -2
    };

}
