package julius.game.chessengine.core.nnue;

import julius.game.chessengine.core.Position;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Quantised (768 * K -> H) x 2 -> 1 network with SCReLU activation.
 *
 * <p>Feature index for a perspective: kingBucket * 768 + side(0 = own, 1 = enemy) * 384 + pieceType * 64 +
 * square, where the square is flipped vertically for the black perspective and, when the network mirrors,
 * horizontally while the perspective's own king stands on the e-h files. The king bucket is
 * {@code kingBucketMap[own king square]} (in the perspective's frame); versions 1 and 2 have one bucket and
 * no mirroring.</p>
 *
 * <p>File format (little endian): magic "ALNN", int version, int hidden, int qa, int qb, int scale,
 * [version >= 2: int buckets], [version 3: int kingBuckets, int mirror (0/1), byte[64] kingBucketMap], then
 * short[768*K*H] l1 weights (feature-major), short[H] l1 bias, and per output bucket short[2H] l2 weights,
 * then one int l2 bias per bucket. Version 1 has a single output bucket. Version 4 (otherwise as 3) appends a
 * second output laid out like the first, the network's uncertainty (its expected error against a search, as a
 * win probability; scripts/nnue/train_gpu.py --unc-weight).</p>
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
    /** Input buckets by the perspective's own king square (1 for versions 1 and 2). */
    public final int kingBuckets;
    /** Mirror the board horizontally while the perspective's king is on the e-h files. */
    public final boolean mirror;
    private final byte[] kingBucketMap;
    // Stored widened to int so the JIT can vectorise the accumulator and output loops.
    /** One array per input feature: separate arrays (offset 0) let C2 vectorise the update loops. */
    final int[][] l1Weights;
    final int[] l1Bias;
    /** Row {@code bucket * 2 + half} (half 0 = side to move, 1 = other side), each of length hidden. */
    final int[][] l2Weights;
    final int[] l2Bias;
    /** The uncertainty output (version 4), laid out like l2Weights / l2Bias; null without it. */
    final int[][] uncWeights;
    final int[] uncBias;
    /** int16 copies of the first layer for the Vector API back end, built on first use. */
    private volatile short[][] l1Short;
    private volatile short[] l1BiasShort;

    private Network(int hidden, int qa, int qb, int scale, int buckets, int kingBuckets, boolean mirror,
                    byte[] kingBucketMap, int[] w1, int[] b1, int[] w2, int[] b2, int[] w3, int[] b3) {
        this.hidden = hidden;
        this.qa = qa;
        this.qb = qb;
        this.scale = scale;
        this.buckets = buckets;
        this.kingBuckets = kingBuckets;
        this.mirror = mirror;
        this.kingBucketMap = kingBucketMap;
        int inputs = INPUTS * kingBuckets;
        this.l1Weights = new int[inputs][];
        for (int f = 0; f < inputs; f++) l1Weights[f] = java.util.Arrays.copyOfRange(w1, f * hidden, (f + 1) * hidden);
        this.l1Bias = b1;
        this.l2Weights = new int[2 * buckets][];
        for (int r = 0; r < 2 * buckets; r++) l2Weights[r] = java.util.Arrays.copyOfRange(w2, r * hidden, (r + 1) * hidden);
        this.l2Bias = b2;
        if (w3 != null) {
            this.uncWeights = new int[2 * buckets][];
            for (int r = 0; r < 2 * buckets; r++) uncWeights[r] = java.util.Arrays.copyOfRange(w3, r * hidden, (r + 1) * hidden);
            this.uncBias = b3;
        } else {
            this.uncWeights = null;
            this.uncBias = null;
        }
    }

    /** Whether the network has the uncertainty output (version 4). */
    public boolean hasUncertainty() {
        return uncWeights != null;
    }

    short[][] l1WeightsShort() {
        short[][] w = l1Short;
        if (w == null) {
            synchronized (this) {
                if (l1Short == null) {
                    short[][] s = new short[l1Weights.length][hidden];
                    for (int f = 0; f < l1Weights.length; f++) {
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
            if (version < 1 || version > 4) throw new IOException("Unsupported network version " + version);
            int hidden = le(in.readInt());
            int qa = le(in.readInt());
            int qb = le(in.readInt());
            int scale = le(in.readInt());
            int buckets = version >= 2 ? le(in.readInt()) : 1;
            if (buckets < 1 || buckets > 32) throw new IOException("Bad bucket count " + buckets);
            if (hidden <= 0 || hidden > 4096 || hidden % 32 != 0) throw new IOException("Bad hidden size " + hidden);
            if (qa > 255 || qb > 512) throw new IOException("Unsupported quantisation qa=" + qa + " qb=" + qb);
            int kingBuckets = 1;
            boolean mirror = false;
            byte[] map = new byte[64];
            if (version >= 3) {
                kingBuckets = le(in.readInt());
                mirror = le(in.readInt()) != 0;
                in.readFully(map);
                if (kingBuckets < 1 || kingBuckets > 64) throw new IOException("Bad king bucket count " + kingBuckets);
                for (byte b : map) {
                    if (b < 0 || b >= kingBuckets) throw new IOException("Bad king bucket map entry " + b);
                }
            }
            int[] w1 = readShorts(in, INPUTS * kingBuckets * hidden);
            int[] b1 = readShorts(in, hidden);
            int[] w2 = readShorts(in, 2 * hidden * buckets);
            int[] b2 = new int[buckets];
            for (int b = 0; b < buckets; b++) b2[b] = le(in.readInt());
            int[] w3 = null, b3 = null;
            if (version >= 4) {
                w3 = readShorts(in, 2 * hidden * buckets);
                b3 = new int[buckets];
                for (int b = 0; b < buckets; b++) b3[b] = le(in.readInt());
            }
            // int16 accumulators (Vector API back end) need bias + 32 weights to fit; the trainer clips to +-505
            int maxW = 0, maxB = 0;
            for (int w : w1) maxW = Math.max(maxW, Math.abs(w));
            for (int b : b1) maxB = Math.max(maxB, Math.abs(b));
            if (32L * maxW + maxB > Short.MAX_VALUE) {
                throw new IOException("First-layer weights too large for int16 accumulators: max |w| " + maxW);
            }
            return new Network(hidden, qa, qb, scale, buckets, kingBuckets, mirror, map, w1, b1, w2, b2, w3, b3);
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

    /** Input feature of a piece for a perspective whose own king stands on {@code ownKingSq}. */
    public int feature(int perspective, int piece, int sq, int ownKingSq) {
        int color = Position.color(piece);
        int type = Position.type(piece);
        int s = perspective == Position.WHITE ? sq : sq ^ 56;
        int k = perspective == Position.WHITE ? ownKingSq : ownKingSq ^ 56;
        if (mirror && (k & 7) >= 4) s ^= 7;
        return kingBucketMap[k] * INPUTS + (color == perspective ? 0 : 384) + type * 64 + s;
    }

    /** Whether the perspective's features stay the same frame (bucket and mirroring) when its king moves. */
    public boolean sameFrame(int perspective, int kingFrom, int kingTo) {
        int a = perspective == Position.WHITE ? kingFrom : kingFrom ^ 56;
        int b = perspective == Position.WHITE ? kingTo : kingTo ^ 56;
        return kingBucketMap[a] == kingBucketMap[b] && (!mirror || ((a & 7) >= 4) == ((b & 7) >= 4));
    }

    /** Number of feature frames (king bucket, board half) a perspective can be in. */
    public int frames() {
        return kingBuckets * (mirror ? 2 : 1);
    }

    /** The feature frame of a perspective whose own king stands on {@code ownKingSq}. */
    public int frame(int perspective, int ownKingSq) {
        int k = perspective == Position.WHITE ? ownKingSq : ownKingSq ^ 56;
        return mirror ? kingBucketMap[k] * 2 + ((k & 7) >= 4 ? 1 : 0) : kingBucketMap[k];
    }

    void add(int[] acc, int feature) {
        int[] w = l1Weights[feature];
        for (int i = 0; i < acc.length; i++) acc[i] += w[i];
    }

    void sub(int[] acc, int feature) {
        int[] w = l1Weights[feature];
        for (int i = 0; i < acc.length; i++) acc[i] -= w[i];
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

    /** The uncertainty output in thousandths of a win probability (0 or more); needs {@link #hasUncertainty}. */
    public int uncertainty(int[] us, int[] them, int bucket) {
        long sum = (long) screluDot(us, uncWeights[2 * bucket]) + screluDot(them, uncWeights[2 * bucket + 1]);
        return uncertaintyOf(sum, bucket);
    }

    /** The uncertainty output from the activations' dot products (sum of both halves, each / 256). */
    int uncertaintyOf(long sum, int bucket) {
        long out = sum * 256 / qa + uncBias[bucket];
        return (int) Math.max(0, out * 1000 / ((long) qa * qb));
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
