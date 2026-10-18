package julius.game.chessengine.core.nnue;

import julius.game.chessengine.core.Position;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Quantised (768 -> H) x 2 -> 1 network with SCReLU activation.
 *
 * <p>Feature index for a perspective: side(0 = own, 1 = enemy) * 384 + pieceType * 64 + square,
 * where the square is flipped vertically for the black perspective.</p>
 *
 * <p>File format (little endian): magic "ALNN", int version, int hidden, int qa, int qb, int scale,
 * [version 2: int buckets], then short[768*H] l1 weights (feature-major), short[H] l1 bias, and per output
 * bucket short[2H] l2 weights, then one int l2 bias per bucket. Version 1 has a single bucket.</p>
 *
 * <p>Output buckets: the output layer is chosen by the number of pieces on the board, so endgames get
 * their own weights ({@link #bucketFor}).</p>
 */
public final class Network {

    public static final String RESOURCE = "/core/nnue.bin";
    public static final int INPUTS = 768;

    public final int hidden;
    public final int qa;
    public final int qb;
    public final int scale;
    public final int buckets;
    // Stored widened to int so the JIT can vectorise the accumulator and output loops.
    /** One array per input feature: separate arrays (offset 0) let C2 vectorise the update loops. */
    final int[][] l1Weights;
    final int[] l1Bias;
    /** Row {@code bucket * 2 + half} (half 0 = side to move, 1 = other side), each of length hidden. */
    final int[][] l2Weights;
    final int[] l2Bias;
    /** int16 copies of the first layer for the Vector API back end, built on first use. */
    private volatile short[][] l1Short;
    private volatile short[] l1BiasShort;

    private Network(int hidden, int qa, int qb, int scale, int buckets, int[] w1, int[] b1, int[] w2, int[] b2) {
        this.hidden = hidden;
        this.qa = qa;
        this.qb = qb;
        this.scale = scale;
        this.buckets = buckets;
        this.l1Weights = new int[INPUTS][];
        for (int f = 0; f < INPUTS; f++) l1Weights[f] = java.util.Arrays.copyOfRange(w1, f * hidden, (f + 1) * hidden);
        this.l1Bias = b1;
        this.l2Weights = new int[2 * buckets][];
        for (int r = 0; r < 2 * buckets; r++) l2Weights[r] = java.util.Arrays.copyOfRange(w2, r * hidden, (r + 1) * hidden);
        this.l2Bias = b2;
    }

    short[][] l1WeightsShort() {
        short[][] w = l1Short;
        if (w == null) {
            synchronized (this) {
                if (l1Short == null) {
                    short[][] s = new short[INPUTS][hidden];
                    for (int f = 0; f < INPUTS; f++) {
                        for (int i = 0; i < hidden; i++) s[f][i] = (short) l1Weights[f][i];
                    }
                    l1Short = s;
                }
                w = l1Short;
            }
        }
        return w;
    }

    short[] l1BiasShort() {
        short[] b = l1BiasShort;
        if (b == null) {
            b = new short[hidden];
            for (int i = 0; i < hidden; i++) b[i] = (short) l1Bias[i];
            l1BiasShort = b;
        }
        return b;
    }

    /** Output bucket for a piece count (2..32): eight buckets of four pieces each, or 0 with one bucket. */
    public static int bucketFor(int pieceCount, int buckets) {
        if (buckets <= 1) return 0;
        return Math.min(buckets - 1, Math.max(0, (pieceCount - 2) * buckets / 32));
    }

    private static volatile Network defaultNet;
    private static volatile boolean defaultLoaded;

    /** The network bundled as a resource (or given by -Dcore.nnueFile), or null if none. */
    public static Network loadDefault() {
        if (!defaultLoaded) {
            synchronized (Network.class) {
                if (!defaultLoaded) {
                    try {
                        String override = System.getProperty("core.nnueFile");
                        if (override != null && !override.isBlank()) {
                            defaultNet = override.equalsIgnoreCase("none") ? null : read(Files.newInputStream(Path.of(override)));
                        } else {
                            InputStream in = Network.class.getResourceAsStream(RESOURCE);
                            defaultNet = in == null ? null : read(in);
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to load NNUE network", e);
                    }
                    defaultLoaded = true;
                }
            }
        }
        return defaultNet;
    }

    public static Network read(InputStream raw) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'A' || magic[1] != 'L' || magic[2] != 'N' || magic[3] != 'N') {
                throw new IOException("Not an Alieknek network file");
            }
            int version = le(in.readInt());
            if (version != 1 && version != 2) throw new IOException("Unsupported network version " + version);
            int hidden = le(in.readInt());
            int qa = le(in.readInt());
            int qb = le(in.readInt());
            int scale = le(in.readInt());
            int buckets = version >= 2 ? le(in.readInt()) : 1;
            if (buckets < 1 || buckets > 32) throw new IOException("Bad bucket count " + buckets);
            if (hidden <= 0 || hidden > 4096 || hidden % 32 != 0) throw new IOException("Bad hidden size " + hidden);
            if (qa > 255 || qb > 512) throw new IOException("Unsupported quantisation qa=" + qa + " qb=" + qb);
            int[] w1 = readShorts(in, INPUTS * hidden);
            int[] b1 = readShorts(in, hidden);
            int[] w2 = readShorts(in, 2 * hidden * buckets);
            int[] b2 = new int[buckets];
            for (int b = 0; b < buckets; b++) b2[b] = le(in.readInt());
            // int16 accumulators (Vector API back end) need bias + 32 weights to fit; the trainer clips to +-505
            int maxW = 0, maxB = 0;
            for (int w : w1) maxW = Math.max(maxW, Math.abs(w));
            for (int b : b1) maxB = Math.max(maxB, Math.abs(b));
            if (32L * maxW + maxB > Short.MAX_VALUE) {
                throw new IOException("First-layer weights too large for int16 accumulators: max |w| " + maxW);
            }
            return new Network(hidden, qa, qb, scale, buckets, w1, b1, w2, b2);
        }
    }

    private static int le(int v) {
        return Integer.reverseBytes(v);
    }

    private static int[] readShorts(DataInputStream in, int n) throws IOException {
        int[] a = new int[n];
        byte[] buf = new byte[n * 2];
        in.readFully(buf);
        for (int i = 0; i < n; i++) a[i] = (short) ((buf[2 * i] & 0xFF) | (buf[2 * i + 1] << 8));
        return a;
    }

    public static int featureIndex(int perspective, int piece, int sq) {
        int color = Position.color(piece);
        int type = Position.type(piece);
        if (perspective == Position.WHITE) {
            return (color == Position.WHITE ? 0 : 384) + type * 64 + sq;
        }
        return (color == Position.BLACK ? 0 : 384) + type * 64 + (sq ^ 56);
    }

    /** Full accumulator refresh for both perspectives. */
    public void refresh(Position p, int[] accWhite, int[] accBlack) {
        System.arraycopy(l1Bias, 0, accWhite, 0, hidden);
        System.arraycopy(l1Bias, 0, accBlack, 0, hidden);
        long occ = p.all;
        while (occ != 0L) {
            int sq = Long.numberOfTrailingZeros(occ);
            occ &= occ - 1;
            int pc = p.board[sq];
            add(accWhite, featureIndex(Position.WHITE, pc, sq));
            add(accBlack, featureIndex(Position.BLACK, pc, sq));
        }
    }

    void add(int[] acc, int feature) {
        int[] w = l1Weights[feature];
        for (int i = 0; i < acc.length; i++) acc[i] += w[i];
    }

    /** Copies src into dst then applies up to two removals and two additions. */
    void update(int[] src, int[] dst, int sub1, int sub2, int add1, int add2) {
        int[][] w = l1Weights;
        if (sub2 < 0) {
            addSub(src, dst, w[add1], w[sub1]);
        } else if (add2 < 0) {
            addSubSub(src, dst, w[add1], w[sub1], w[sub2]);
        } else {
            addAddSubSub(src, dst, w[add1], w[add2], w[sub1], w[sub2]);
        }
    }

    private static void addSub(int[] src, int[] dst, int[] a1, int[] s1) {
        for (int i = 0; i < dst.length; i++) dst[i] = src[i] + a1[i] - s1[i];
    }

    private static void addSubSub(int[] src, int[] dst, int[] a1, int[] s1, int[] s2) {
        for (int i = 0; i < dst.length; i++) dst[i] = src[i] + a1[i] - s1[i] - s2[i];
    }

    private static void addAddSubSub(int[] src, int[] dst, int[] a1, int[] a2, int[] s1, int[] s2) {
        for (int i = 0; i < dst.length; i++) dst[i] = src[i] + a1[i] + a2[i] - s1[i] - s2[i];
    }

    /** Network output in centipawns for the side whose accumulator is {@code us}. */
    public int evaluate(int[] us, int[] them, int bucket) {
        // screluDot returns sum(v^2 * w) / 256
        long sum = (long) screluDot(us, l2Weights[2 * bucket]) + screluDot(them, l2Weights[2 * bucket + 1]);
        long out = sum * 256 / qa + l2Bias[bucket];
        return (int) (out * scale / ((long) qa * qb));
    }

    /**
     * Sum of clamp(acc, 0, QA)^2 * w / 256 over one half of the hidden layer. Each term is at most
     * 255 * 507 * 255 before the shift, so the int reduction cannot overflow and the loop vectorises.
     */
    private int screluDot(int[] acc, int[] w) {
        int q = qa;
        int s = 0;
        for (int i = 0; i < w.length; i++) {
            int v = Math.min(Math.max(acc[i], 0), q);
            s += (v * w[i]) * v >> 8;
        }
        return s;
    }
}
