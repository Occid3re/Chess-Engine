package julius.game.chessengine.nnue.train;

import julius.game.chessengine.ai.nnue.NnueWeights;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exports float weights to the NNUEV1 binary format using naive rounding
 * quantisation.
 */
public final class NnueExporter {
    private NnueExporter() {}

    public static void export(int F, int H, float[] hb, float[] hw, float ob, float[] ow, Path out) throws IOException {
        short[] hbQ = new short[H];
        for (int i = 0; i < H; i++) hbQ[i] = (short) Math.round(hb[i]);
        short[] hwQ = new short[F * H];
        for (int i = 0; i < hwQ.length; i++) hwQ[i] = (short) Math.round(hw[i]);
        short[] owQ = new short[H];
        for (int i = 0; i < H; i++) owQ[i] = (short) Math.round(ow[i]);
        int obQ = Math.round(ob);
        ByteBuffer bb = ByteBuffer.allocate(8 + 4 + 4 + hbQ.length * 2 + hwQ.length * 2 + 4 + owQ.length * 2);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.put("NNUEV1\0".getBytes());
        bb.putInt(F);
        bb.putInt(H);
        for (short s : hbQ) bb.putShort(s);
        for (short s : hwQ) bb.putShort(s);
        bb.putInt(obQ);
        for (short s : owQ) bb.putShort(s);
        Files.createDirectories(out.getParent());
        Files.write(out, bb.array());
    }
}
