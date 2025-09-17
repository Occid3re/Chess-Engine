package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.evaluation.PawnStructureModule;
import julius.game.chessengine.evaluation.PawnStructureModule.PawnStructureView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PassedPawnScoreTest {

    @Test
    void whitePassedPawnGetsBonus() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/4P3/8/8/8/4K3 w - - 0 1");
        PawnStructureView view = pawnStructure(board);
        assertEquals(PawnStructureModule.PASSED_PAWN_BONUS * 4,
                view.whitePassed().blend(board.getPhase()));
        assertEquals(0, view.blackPassed().blend(board.getPhase()));
    }

    @Test
    void blackPassedPawnGetsBonus() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/8/3p4/8/8/4K3 w - - 0 1");
        PawnStructureView view = pawnStructure(board);
        assertEquals(PawnStructureModule.PASSED_PAWN_BONUS * 4,
                view.blackPassed().blend(board.getPhase()));
        assertEquals(0, view.whitePassed().blend(board.getPhase()));
    }

    private static PawnStructureView pawnStructure(BitBoard board) {
        PawnStructureModule module = new PawnStructureModule();
        return module.getView(board);
    }
}
