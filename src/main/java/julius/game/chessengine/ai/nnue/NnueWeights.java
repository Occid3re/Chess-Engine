package julius.game.chessengine.ai.nnue;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Holds the quantised NNUE network weights.
 */
public final class NnueWeights {
    public final int F;
    public final int H;
    public final short[] hb;
    public final short[] hw;
    public final int ob;
    public final short[] ow;

    public NnueWeights(int F, int H, short[] hb, short[] hw, int ob, short[] ow) {
        this.F = F;
        this.H = H;
        this.hb = hb;
        this.hw = hw;
        this.ob = ob;
        this.ow = ow;
    }

    private static final byte[] MAGIC = "NNUEV1\0".getBytes(StandardCharsets.US_ASCII);

    public static NnueWeights load(InputStream is) throws IOException {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(is))) {
            byte[] magic = new byte[8];
            dis.readFully(magic);
            for (int i = 0; i < 8; i++) {
                if (magic[i] != MAGIC[i]) {
                    throw new IOException("Invalid NNUE magic");
                }
            }
            int F = Integer.reverseBytes(dis.readInt());
            int H = Integer.reverseBytes(dis.readInt());
            short[] hb = new short[H];
            for (int i = 0; i < H; i++) {
                hb[i] = Short.reverseBytes(dis.readShort());
            }
            short[] hw = new short[F * H];
            for (int i = 0; i < hw.length; i++) {
                hw[i] = Short.reverseBytes(dis.readShort());
            }
            int ob = Integer.reverseBytes(dis.readInt());
            short[] ow = new short[H];
            for (int i = 0; i < H; i++) {
                ow[i] = Short.reverseBytes(dis.readShort());
            }
            return new NnueWeights(F, H, hb, hw, ob, ow);
        }
    }
}
