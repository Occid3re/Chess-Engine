package julius.game.chessengine.ai.nnue.tests;

import julius.game.chessengine.ai.nnue.HalfKPFeature;
import julius.game.chessengine.ai.nnue.NnueEvaluator;
import julius.game.chessengine.ai.nnue.NnueWeights;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.nnue.train.NnueExporter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class NnueBinaryFormatTest {
    @Test
    public void loadAndEvaluate() throws Exception {
        int F = HalfKPFeature.NUM_FEATURES;
        int H = 8;
        float[] hb = new float[H];
        float[] hw = new float[F * H];
        float[] ow = new float[H];
        float ob = 0f;
        Path tmp = Files.createTempFile("nnue", ".nnuev1");
        NnueExporter.export(F, H, hb, hw, ob, ow, tmp);
        NnueWeights w = NnueWeights.load(Files.newInputStream(tmp));
        NnueEvaluator e = new NnueEvaluator();
        e.load(w);
        BitBoard b = FEN.translateFENtoBitBoard("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        int cp1 = e.evalCp(b);
        int cp2 = e.evalCp(b);
        assertEquals(cp1, cp2);
    }
}
