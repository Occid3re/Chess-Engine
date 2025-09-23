package julius.game.chessengine.board;

import julius.game.chessengine.engine.Engine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FENExportTest {

    @Test
    void exportFenReflectsCompleteGameState() {
        Engine engine = new Engine();
        String expectedFen = "r3k2r/pppb1ppp/2n1bn2/2Pp4/3P4/2N1PN2/PPPB1PPP/R3K2R w KQkq d6 7 12";

        engine.importBoardFromFen(expectedFen);

        FEN exportedFen = engine.translateBoardToFen();

        assertEquals(expectedFen, exportedFen.renderBoard());
    }
}
