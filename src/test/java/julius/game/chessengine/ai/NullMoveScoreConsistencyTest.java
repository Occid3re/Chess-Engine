package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.utils.Score;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NullMoveScoreConsistencyTest {

    private static final String COMPLEX_FEN = "r3k2r/pppb1ppp/2n1bn2/2Pp4/3P4/2N1PN2/PPPB1PPP/R3K2R w KQkq d6 7 12";

    @Test
    void nullMoveSequenceKeepsScoreSynchronized() {
        Engine engine = new Engine();
        engine.importBoardFromFen(COMPLEX_FEN);

        GameState state = engine.getGameState();

        assertScoreMatchesFreshEvaluation(engine, state);

        int previousEp = engine.doNullMoveForSearch();
        assertScoreMatchesFreshEvaluation(engine, state);

        engine.undoNullMoveForSearch(previousEp);
        assertScoreMatchesFreshEvaluation(engine, state);
    }

    private static void assertScoreMatchesFreshEvaluation(Engine engine, GameState state) {
        Score fresh = new Score();
        fresh.refresh(engine.getBitBoard(), state.getState());
        assertEquals(fresh.getBlendedScore(), state.getScore().getBlendedScore(),
                "Score pipeline is out of sync with board after null move simulation");
    }
}
