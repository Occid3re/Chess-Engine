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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class NnueParityTest {
    private static int floatForward(BitBoard b, int H, float[] hb, float[] hw, float[] ow, float ob) {
        float[] hidden = new float[H];
        System.arraycopy(hb, 0, hidden, 0, H);
        List<Integer> feats = HalfKPFeature.featuresFor(b);
        for (int f : feats) {
            int base = f * H;
            for (int h = 0; h < H; h++) hidden[h] += hw[base + h];
        }
        for (int h = 0; h < H; h++) if (hidden[h] < 0) hidden[h] = 0;
        float sum = ob;
        for (int h = 0; h < H; h++) sum += hidden[h] * ow[h];
        if (sum > 30000) sum = 30000;
        if (sum < -30000) sum = -30000;
        return Math.round(sum);
    }

    @Test
    public void parity() throws Exception {
        int F = HalfKPFeature.NUM_FEATURES;
        int H = 8;
        float[] hb = new float[H];
        float[] hw = new float[F * H];
        float[] ow = new float[H];
        for (int i = 0; i < H; i++) {
            hb[i] = i + 1;
            ow[i] = (i % 2 == 0 ? 1 : -1);
        }
        for (int i = 0; i < F * H; i++) hw[i] = (i % 7) - 3; // small values
        float ob = 0f;
        Path tmp = Files.createTempFile("nnue", ".nnuev1");
        NnueExporter.export(F, H, hb, hw, ob, ow, tmp);
        NnueWeights w = NnueWeights.load(Files.newInputStream(tmp));
        NnueEvaluator e = new NnueEvaluator();
        e.load(w);
        String[] fens = {
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                "rnbqkbnr/pppp1ppp/4p3/8/8/4P3/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
        };
        for (String fen : fens) {
            BitBoard b = FEN.translateFENtoBitBoard(fen);
            int fEval = floatForward(b, H, hb, hw, ow, ob);
            int qEval = e.evalCp(b);
            assertTrue(Math.abs(fEval - qEval) <= 10, "parity diff too large");
        }
    }
}
