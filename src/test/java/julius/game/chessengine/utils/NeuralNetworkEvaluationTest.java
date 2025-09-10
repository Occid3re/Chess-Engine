package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.nn.NeuralNetwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class NeuralNetworkEvaluationTest {

    @BeforeEach
    void resetModel() {
        NeuralNetwork.getInstance().reset();
    }

    @Test
    void networkLearnsWhiteWin() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1");
        Score score = new Score();
        score.initializeScore(board);
        NeuralNetwork nn = NeuralNetwork.getInstance();
        nn.train(Collections.singletonList(score.toFeatureVector()), 1);
        assertTrue(nn.evaluate(score.toFeatureVector()) > 0);
    }

    @Test
    void networkLearnsBlackWin() {
        BitBoard board = FEN.translateFENtoBitBoard("4k3/8/8/8/8/8/8/q3K3 w - - 0 1");
        Score score = new Score();
        score.initializeScore(board);
        NeuralNetwork nn = NeuralNetwork.getInstance();
        nn.train(Collections.singletonList(score.toFeatureVector()), -1);
        assertTrue(nn.evaluate(score.toFeatureVector()) < 0);
    }
}
