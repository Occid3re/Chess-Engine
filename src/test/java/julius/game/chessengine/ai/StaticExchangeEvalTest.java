package julius.game.chessengine.ai;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

public class StaticExchangeEvalTest {

    @Test
    void bishopSacrificeIsDeprioritized() {
        Engine engine = new Engine();
        engine.importBoardFromFen("4k3/5p2/8/8/2B5/8/8/4K3 w - - 0 1");
        BitBoard board = engine.getBitBoard();

        MoveList moves = engine.getAllLegalMoves();
        int from = MoveHelper.convertStringToIndex("c4");
        int to = MoveHelper.convertStringToIndex("f7");
        int sacrifice = -1;
        for (int i = 0; i < moves.size(); i++) {
            int m = moves.getMove(i);
            if (MoveHelper.deriveFromIndex(m) == from && MoveHelper.deriveToIndex(m) == to) {
                sacrifice = m;
                break;
            }
        }
        assertNotEquals(-1, sacrifice, "Sacrifice move not found");

        int see = Score.staticExchangeEval(board, sacrifice);
        assertTrue(see < 0, "SEE should detect material loss");

        AI ai = new AI(engine);
        ArrayList<Integer> ordered = ai.sortMovesByEfficiency(moves, 0, engine.getBoardStateHash(), board);
        assertEquals(sacrifice, ordered.get(ordered.size() - 1), "Losing capture should be last");
        assertNotEquals(sacrifice, ordered.get(0), "Engine should keep material parity");
    }
}
