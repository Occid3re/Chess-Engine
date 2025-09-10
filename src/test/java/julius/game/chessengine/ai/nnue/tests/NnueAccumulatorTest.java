package julius.game.chessengine.ai.nnue.tests;

import julius.game.chessengine.ai.nnue.HalfKPFeature;
import julius.game.chessengine.ai.nnue.NnueAccumulator;
import julius.game.chessengine.ai.nnue.NnueEvaluator;
import julius.game.chessengine.ai.nnue.NnueWeights;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.nnue.train.NnueExporter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class NnueAccumulatorTest {
    @Test
    public void incrementalMatchesFull() throws Exception {
        int F = HalfKPFeature.NUM_FEATURES;
        int H = 8;
        float[] hb = new float[H];
        float[] hw = new float[F * H];
        float[] ow = new float[H];
        float ob = 0f;
        Path tmp = Files.createTempFile("nnue", ".nnuev1");
        NnueExporter.export(F, H, hb, hw, ob, ow, tmp);
        NnueWeights w = NnueWeights.load(Files.newInputStream(tmp));
        NnueEvaluator eval = new NnueEvaluator();
        eval.load(w);

        Engine engine = new Engine();
        BitBoard b = new BitBoard(engine.getBitBoard());
        NnueAccumulator acc = new NnueAccumulator();
        acc.init(b, w);
        Random rnd = new Random(1);
        for (int i = 0; i < 5; i++) {
            MoveList moves = engine.getAllLegalMoves();
            int move = moves.getMove(rnd.nextInt(moves.size()));
            BitBoard before = new BitBoard(engine.getBitBoard());
            engine.performMove(move);
            BitBoard after = engine.getBitBoard();
            acc.applyMove(before, move, after, w);
            int full = eval.evalCp(after);
            int inc = acc.evalCp(w);
            assertEquals(full, inc);
        }
    }
}
