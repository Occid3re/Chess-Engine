package julius.game.chessengine.board;

import julius.game.chessengine.ai.AI;
import julius.game.chessengine.ai.OpeningBook;
import julius.game.chessengine.engine.Engine;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Test;

@Log4j2
public class EvalTest {

    @Test
    public void quiencesearch() {
        Engine engine = new Engine();
        AI ai = new AI(engine);
        engine.importBoardFromFen("b4rk1/5ppR/3N4/8/3n4/4B3/5P2/4K3 w - - 0 1");
        log.info(ai.evaluateBoard(engine, true, System.currentTimeMillis(), 10000000));
    }

}
