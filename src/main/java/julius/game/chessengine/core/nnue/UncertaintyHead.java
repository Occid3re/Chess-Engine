package julius.game.chessengine.core.nnue;

import julius.game.chessengine.core.Position;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

/**
 * How far the network's static evaluation of a position is likely off: a small head (one hidden layer) on the
 * network's own SCReLU activations predicts how much a ~6000-node search moves the evaluation, as a win probability
 * (0.02 = the network is sure, 0.15 = an unclear position). Trained after the network on its own self-play by
 * {@code scripts/nnue/uncertainty_probe.py --export}; on held-out games the 10 % of positions it rates most uncertain
 * hold 37 % of the large errors (> 10 points), twice as many as simple rules (piece count, size of the evaluation).
 *
 * <p>File {@code core/nnue-unc.bin} (or {@code -Dcore.uncFile}), little endian: "ALNU", version 1, CRC32 of the
 * network file it belongs to, hidden, buckets, size, then float32 w1 [size][2 * hidden + buckets], b1 [size],
 * w2 [size], b2. A head whose CRC does not match the loaded network is ignored.</p>
 */
public final class UncertaintyHead {

    public static final String RESOURCE = "/core/nnue-unc.bin";

    private static volatile UncertaintyHead defaultHead;
    private static volatile boolean defaultLoaded;

    private final Network net;
    private final int hidden, buckets, size, inputs;
    private final float[] w1, b1, w2;
    private final float b2;

    private UncertaintyHead(Network net, int hidden, int buckets, int size, float[] w1, float[] b1, float[] w2, float b2) {
        this.net = net;
        this.hidden = hidden;
        this.buckets = buckets;
        this.size = size;
        this.inputs = 2 * hidden + buckets;
        this.w1 = w1;
        this.b1 = b1;
        this.w2 = w2;
        this.b2 = b2;
    }

    /** The head for the default network, or null if there is none or it belongs to another network. */
    /**
     * How unsure the default network is about p (its expected error against a search, as a win probability): the
     * network's own uncertainty output (version 4), else the head bound to it, else -1.
     */
    public static double estimate(Position p) {
        Network net = Network.loadDefault();
        if (net != null && net.hasUncertainty()) {
            Accumulators a = Accumulators.create(net, 1);
            a.refresh(p, 0);
            return a.uncertainty(p, 0) / 1000.0;
        }
        UncertaintyHead head = loadDefault();
        return head == null ? -1 : head.predict(p);
    }

    /** Whether {@link #estimate} has something to go on for the default network. */
    public static boolean available() {
        Network net = Network.loadDefault();
        return net != null && (net.hasUncertainty() || loadDefault() != null);
    }

    public static UncertaintyHead loadDefault() {
        if (!defaultLoaded) {
            synchronized (UncertaintyHead.class) {
                if (!defaultLoaded) {
                    try {
                        Network net = Network.loadDefault();
                        byte[] netBytes = defaultNetworkBytes();
                        byte[] headBytes = bytes(System.getProperty("core.uncFile"), RESOURCE);
                        if (net != null && netBytes != null && headBytes != null) {
                            CRC32 crc = new CRC32();
                            crc.update(netBytes);
                            defaultHead = read(net, (int) crc.getValue(), headBytes);
                        }
                    } catch (IOException | RuntimeException e) {
                        defaultHead = null;
                    }
                    defaultLoaded = true;
                }
            }
        }
        return defaultHead;
    }

    private static byte[] defaultNetworkBytes() throws IOException {
        String override = System.getProperty("core.nnueFile");
        if (override != null && override.equalsIgnoreCase("none")) return null;
        return bytes(override, Network.RESOURCE);
    }

    private static byte[] bytes(String file, String resource) throws IOException {
        if (file != null && !file.isBlank()) return Files.readAllBytes(Path.of(file));
        try (InputStream in = UncertaintyHead.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /** Parses a head file; null if it belongs to another network (CRC) or does not fit its shape. */
    static UncertaintyHead read(Network net, int netCrc, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (b.get() != 'A' || b.get() != 'L' || b.get() != 'N' || b.get() != 'U') throw new IOException("not a head file");
        if (b.getInt() != 1) throw new IOException("unknown head version");
        int crc = b.getInt();
        int hidden = b.getInt(), buckets = b.getInt(), size = b.getInt();
        if (crc != netCrc || hidden != net.hidden || buckets != net.buckets) return null;
        float[] w1 = floats(b, size * (2 * hidden + buckets));
        float[] b1 = floats(b, size);
        float[] w2 = floats(b, size);
        return new UncertaintyHead(net, hidden, buckets, size, w1, b1, w2, b.getFloat());
    }

    private static float[] floats(ByteBuffer b, int n) {
        float[] f = new float[n];
        b.asFloatBuffer().get(f);
        b.position(b.position() + 4 * n);
        return f;
    }

    /**
     * Expected |search - static evaluation| of the position as a win probability (0..1). Builds both
     * accumulators from scratch (a few microseconds), so it is meant for single positions, not every node.
     */
    public double predict(Position p) {
        float[] x = new float[inputs];
        activations(p, p.side, x, 0);
        activations(p, p.side ^ 1, x, hidden);
        return predict(x, Long.bitCount(p.all));
    }

    /** Input size for {@link #predict(float[], int)}: both perspectives' activations plus the bucket one-hot. */
    public int inputs() {
        return inputs;
    }

    /**
     * From activations the search already has ({@link Accumulators#activations}: x[0, 2 * hidden) filled; the
     * bucket part of x is overwritten here).
     */
    public double predict(float[] x, int pieceCount) {
        java.util.Arrays.fill(x, 2 * hidden, inputs, 0f);
        x[2 * hidden + Network.bucketFor(pieceCount, buckets)] = 1f;
        double out = b2;
        for (int j = 0, row = 0; j < size; j++, row += inputs) {
            double s = b1[j];
            for (int i = 0; i < inputs; i++) s += w1[row + i] * x[i];
            if (s > 0) out += w2[j] * s;
        }
        return Math.max(0, out / 10);  // trained on 10 x the error
    }

    /** SCReLU activations (clamp(acc / QA, 0, 1)^2) of one perspective, as the trainer computes them. */
    private void activations(Position p, int persp, float[] x, int offset) {
        int[] acc = net.l1Bias.clone();
        int king = p.kingSquare(persp);
        for (int piece = 0; piece < 12; piece++) {
            for (long bb = p.pieces[piece]; bb != 0; bb &= bb - 1) {
                net.add(acc, net.feature(persp, piece, Long.numberOfTrailingZeros(bb), king));
            }
        }
        float qa = net.qa;
        for (int i = 0; i < hidden; i++) {
            float v = Math.min(Math.max(acc[i], 0), net.qa) / qa;
            x[offset + i] = v * v;
        }
    }
}
