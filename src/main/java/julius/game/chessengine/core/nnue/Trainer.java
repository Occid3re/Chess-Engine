package julius.game.chessengine.core.nnue;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Multithreaded CPU trainer for {@link Network}. Reads {@code fen;whiteScore;wdl} files, trains a
 * (768 -> H) x 2 -> 1 SCReLU network with Adam on a sigmoid-MSE loss and writes a quantised network.
 *
 * <p>Usage: {@code Trainer <out.bin> <hidden> <epochs> <lambda> <K> <file1> [file2 ...]}.
 * {@code -Dtrain.buckets=8} trains material-bucketed output layers (file version 2).
 * A file argument may carry a sample-weight-free limit as {@code path@maxLines}.</p>
 */
public final class Trainer {

    static final int QA = 255, QB = 256, SCALE = 400;
    static final float CLIP = 1.98f;

    final int hidden;
    final int buckets;
    final float lambda;
    final float k;

    // samples
    int count;
    int[] offset;
    short[] pieces; // piece << 6 | square
    short[] score;  // side-to-move perspective
    byte[] wdl;     // side-to-move perspective: 0 loss, 1 draw, 2 win
    byte[] stm;

    // parameters
    final float[] w1, b1, w2, b2;
    final float[] mW1, vW1, mB1, vB1, mW2, vW2, mB2, vB2;

    Trainer(int hidden, int buckets, float lambda, float k) {
        this.hidden = hidden;
        this.buckets = buckets;
        this.lambda = lambda;
        this.k = k;
        w1 = new float[768 * hidden];
        b1 = new float[hidden];
        w2 = new float[2 * hidden * buckets];
        b2 = new float[buckets];
        mB2 = new float[buckets];
        vB2 = new float[buckets];
        mW1 = new float[w1.length];
        vW1 = new float[w1.length];
        mB1 = new float[hidden];
        vB1 = new float[hidden];
        mW2 = new float[w2.length];
        vW2 = new float[w2.length];
        SplittableRandom r = new SplittableRandom(12345);
        float a1 = (float) (1.0 / Math.sqrt(32));
        for (int i = 0; i < w1.length; i++) w1[i] = (float) ((r.nextDouble() * 2 - 1) * a1 * 0.5);
        float a2 = (float) (1.0 / Math.sqrt(2 * hidden));
        for (int i = 0; i < w2.length; i++) w2[i] = (float) ((r.nextDouble() * 2 - 1) * a2);
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        int hidden = Integer.parseInt(args[1]);
        int epochs = Integer.parseInt(args[2]);
        float lambda = Float.parseFloat(args[3]);
        float k = Float.parseFloat(args[4]);
        Trainer t = new Trainer(hidden, Integer.getInteger("train.buckets", 1), lambda, k);
        List<String> files = new ArrayList<>();
        for (int i = 5; i < args.length; i++) files.add(args[i]);
        t.load(files);
        t.train(epochs, 16384, 0.001f, out);
    }

    // ------------------------------------------------------------------ data

    void load(List<String> specs) throws IOException {
        List<short[]> pieceChunks = new ArrayList<>();
        int total = 0;
        long totalPieces = 0;
        // Two passes would double I/O; instead grow arrays.
        int cap = 1 << 22;
        offset = new int[cap + 1];
        score = new short[cap];
        wdl = new byte[cap];
        stm = new byte[cap];
        short[] pcs = new short[cap * 24];
        for (String spec : specs) {
            String path = spec;
            long limit = Long.MAX_VALUE;
            int at = spec.lastIndexOf('@');
            if (at > 2) {
                path = spec.substring(0, at);
                limit = Long.parseLong(spec.substring(at + 1));
            }
            long lines = 0;
            try (BufferedReader r = Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null && lines < limit) {
                    lines++;
                    int s1 = line.indexOf(';');
                    int s2 = line.indexOf(';', s1 + 1);
                    if (s1 < 0 || s2 < 0) continue;
                    if (total == score.length) {
                        int nc = score.length * 2;
                        offset = java.util.Arrays.copyOf(offset, nc + 1);
                        score = java.util.Arrays.copyOf(score, nc);
                        wdl = java.util.Arrays.copyOf(wdl, nc);
                        stm = java.util.Arrays.copyOf(stm, nc);
                    }
                    if (totalPieces + 32 >= pcs.length) {
                        pcs = java.util.Arrays.copyOf(pcs, (int) Math.min(Integer.MAX_VALUE - 16L, pcs.length * 2L));
                    }
                    offset[total] = (int) totalPieces;
                    int sq = 56;
                    int i = 0;
                    for (; i < s1; i++) {
                        char c = line.charAt(i);
                        if (c == ' ') break;
                        if (c == '/') {
                            sq -= 16;
                        } else if (c >= '1' && c <= '8') {
                            sq += c - '0';
                        } else {
                            int idx = "PNBRQKpnbrqk".indexOf(c);
                            pcs[(int) totalPieces++] = (short) (idx << 6 | sq);
                            sq++;
                        }
                    }
                    boolean white = line.charAt(i + 1) == 'w';
                    int sc = Integer.parseInt(line.substring(s1 + 1, s2).trim());
                    int res = Integer.parseInt(line.substring(s2 + 1).trim());
                    sc = Math.max(-4000, Math.min(4000, sc));
                    stm[total] = (byte) (white ? 0 : 1);
                    score[total] = (short) (white ? sc : -sc);
                    wdl[total] = (byte) (white ? res : 2 - res);
                    total++;
                }
            }
            System.out.printf("loaded %s: %d lines (total %d)%n", path, lines, total);
        }
        offset[total] = (int) totalPieces;
        pieces = pcs;
        count = total;
        pieceChunks.clear();
    }

    // ------------------------------------------------------------------ training

    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    final class Worker {
        final float[] gW1 = new float[w1.length];
        final float[] gB1 = new float[hidden];
        final float[] gW2 = new float[w2.length];
        final float[] gB2 = new float[buckets];
        final boolean[] touched = new boolean[768];
        final float[] accW = new float[hidden], accB = new float[hidden];
        final float[] dW = new float[hidden], dB = new float[hidden];
        final int[] fw = new int[32], fb = new int[32];
        double loss;

        void clear() {
            for (int f = 0; f < 768; f++) {
                if (touched[f]) {
                    java.util.Arrays.fill(gW1, f * hidden, (f + 1) * hidden, 0f);
                    touched[f] = false;
                }
            }
            java.util.Arrays.fill(gB1, 0f);
            java.util.Arrays.fill(gW2, 0f);
            java.util.Arrays.fill(gB2, 0f);
            loss = 0;
        }

        /** Forward pass; returns the raw output. Fills accW/accB and feature lists. */
        float forward(int s, int[] nOut) {
            int h = hidden;
            System.arraycopy(b1, 0, accW, 0, h);
            System.arraycopy(b1, 0, accB, 0, h);
            int n = 0;
            for (int q = offset[s]; q < offset[s + 1]; q++) {
                int v = pieces[q];
                int pc = v >> 6, sq = v & 63;
                int color = pc >= 6 ? 1 : 0, type = pc >= 6 ? pc - 6 : pc;
                int a = (color == 0 ? 0 : 384) + type * 64 + sq;
                int b = (color == 1 ? 0 : 384) + type * 64 + (sq ^ 56);
                fw[n] = a;
                fb[n] = b;
                n++;
                int oa = a * h, ob = b * h;
                for (int j = 0; j < h; j++) {
                    accW[j] += w1[oa + j];
                    accB[j] += w1[ob + j];
                }
            }
            nOut[0] = n;
            float[] us = stm[s] == 0 ? accW : accB;
            float[] them = stm[s] == 0 ? accB : accW;
            int bucket = Network.bucketFor(n, buckets);
            int o = bucket * 2 * h;
            float out = b2[bucket];
            for (int j = 0; j < h; j++) {
                float u = Math.min(Math.max(us[j], 0f), 1f);
                float t = Math.min(Math.max(them[j], 0f), 1f);
                out += u * u * w2[o + j] + t * t * w2[o + h + j];
            }
            return out;
        }

        void sample(int s) {
            int h = hidden;
            int[] nn = new int[1];
            float out = forward(s, nn);
            int n = nn[0];
            float pred = sigmoid(out * SCALE / k);
            float target = lambda * (wdl[s] / 2f) + (1 - lambda) * sigmoid(score[s] / k);
            float err = pred - target;
            loss += err * err;
            float g = 2f * err * pred * (1 - pred) * SCALE / k;
            int bucket = Network.bucketFor(n, buckets);
            int o = bucket * 2 * h;
            gB2[bucket] += g;
            boolean whiteStm = stm[s] == 0;
            float[] us = whiteStm ? accW : accB;
            float[] them = whiteStm ? accB : accW;
            float[] dUs = whiteStm ? dW : dB;
            float[] dThem = whiteStm ? dB : dW;
            for (int j = 0; j < h; j++) {
                float u = us[j], t = them[j];
                float cu = Math.min(Math.max(u, 0f), 1f), ct = Math.min(Math.max(t, 0f), 1f);
                gW2[o + j] += g * cu * cu;
                gW2[o + h + j] += g * ct * ct;
                dUs[j] = (u > 0f && u < 1f) ? g * w2[o + j] * 2f * u : 0f;
                dThem[j] = (t > 0f && t < 1f) ? g * w2[o + h + j] * 2f * t : 0f;
            }
            for (int j = 0; j < h; j++) gB1[j] += dW[j] + dB[j];
            for (int i = 0; i < n; i++) {
                int oa = fw[i] * h, ob = fb[i] * h;
                touched[fw[i]] = true;
                touched[fb[i]] = true;
                for (int j = 0; j < h; j++) {
                    gW1[oa + j] += dW[j];
                    gW1[ob + j] += dB[j];
                }
            }
        }
    }

    void train(int epochs, int batch, float lr0, Path out) throws Exception {
        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Worker[] workers = new Worker[threads];
        for (int i = 0; i < threads; i++) workers[i] = new Worker();
        int valid = Math.max(1000, count / 100);
        int trainCount = count - valid;
        int[] perm = new int[trainCount];
        for (int i = 0; i < trainCount; i++) perm[i] = i;
        // Shuffle the whole set once so validation samples are spread over all sources.
        SplittableRandom rng = new SplittableRandom(7);
        int[] all = new int[count];
        for (int i = 0; i < count; i++) all[i] = i;
        for (int i = count - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = all[i];
            all[i] = all[j];
            all[j] = tmp;
        }
        System.arraycopy(all, 0, perm, 0, trainCount);
        int[] validIdx = java.util.Arrays.copyOfRange(all, trainCount, count);
        int step = 0;
        for (int epoch = 1; epoch <= epochs; epoch++) {
            long t0 = System.currentTimeMillis();
            for (int i = trainCount - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int tmp = perm[i];
                perm[i] = perm[j];
                perm[j] = tmp;
            }
            float lr = (float) (lr0 * Math.pow(0.1, (epoch - 1) / (double) Math.max(1, epochs - 1)));
            double epochLoss = 0;
            for (int bs = 0; bs + batch <= trainCount; bs += batch) {
                final int base = bs;
                List<Future<?>> fs = new ArrayList<>();
                int per = (batch + threads - 1) / threads;
                for (int w = 0; w < threads; w++) {
                    final Worker wk = workers[w];
                    final int lo = base + w * per, hi = Math.min(base + batch, lo + per);
                    fs.add(pool.submit(() -> {
                        wk.clear();
                        for (int i = lo; i < hi; i++) wk.sample(perm[i]);
                    }));
                }
                for (Future<?> f : fs) f.get();
                step++;
                epochLoss += applyGradients(workers, pool, threads, batch, lr, step);
            }
            double vloss = validationLoss(validIdx, workers[0]);
            save(out);
            System.out.printf("epoch %d lr %.6f train %.6f valid %.6f parity %.2fcp (%d s)%n", epoch, lr,
                    epochLoss / Math.max(1, trainCount / batch), vloss, parity(out, validIdx, workers[0]),
                    (System.currentTimeMillis() - t0) / 1000);
        }
        pool.shutdown();
    }

    private double applyGradients(Worker[] workers, ExecutorService pool, int threads, int batch, float lr, int step)
            throws Exception {
        final float b1c = 0.9f, b2c = 0.999f, eps = 1e-8f;
        final float bc1 = (float) (1 - Math.pow(b1c, step));
        final float bc2 = (float) (1 - Math.pow(b2c, step));
        final float scale = 1f / batch;
        // W1 rows in parallel
        List<Future<?>> fs = new ArrayList<>();
        int rowsPer = (768 + threads - 1) / threads;
        for (int t = 0; t < threads; t++) {
            final int r0 = t * rowsPer, r1 = Math.min(768, r0 + rowsPer);
            fs.add(pool.submit(() -> {
                int h = hidden;
                for (int f = r0; f < r1; f++) {
                    boolean any = false;
                    for (Worker w : workers) {
                        if (w.touched[f]) {
                            any = true;
                            break;
                        }
                    }
                    if (!any) continue;
                    for (int j = 0; j < h; j++) {
                        int idx = f * h + j;
                        float g = 0f;
                        for (Worker w : workers) g += w.gW1[idx];
                        g *= scale;
                        mW1[idx] = b1c * mW1[idx] + (1 - b1c) * g;
                        vW1[idx] = b2c * vW1[idx] + (1 - b2c) * g * g;
                        float nw = w1[idx] - lr * (mW1[idx] / bc1) / ((float) Math.sqrt(vW1[idx] / bc2) + eps);
                        w1[idx] = Math.max(-CLIP, Math.min(CLIP, nw));
                    }
                }
            }));
        }
        for (Future<?> f : fs) f.get();
        double loss = 0;
        for (Worker w : workers) loss += w.loss;
        for (int j = 0; j < hidden; j++) {
            float g = 0f;
            for (Worker w : workers) g += w.gB1[j];
            g *= scale;
            mB1[j] = b1c * mB1[j] + (1 - b1c) * g;
            vB1[j] = b2c * vB1[j] + (1 - b2c) * g * g;
            b1[j] = Math.max(-CLIP, Math.min(CLIP, b1[j] - lr * (mB1[j] / bc1) / ((float) Math.sqrt(vB1[j] / bc2) + eps)));
        }
        for (int j = 0; j < w2.length; j++) {
            float g = 0f;
            for (Worker w : workers) g += w.gW2[j];
            g *= scale;
            mW2[j] = b1c * mW2[j] + (1 - b1c) * g;
            vW2[j] = b2c * vW2[j] + (1 - b2c) * g * g;
            w2[j] = Math.max(-CLIP, Math.min(CLIP, w2[j] - lr * (mW2[j] / bc1) / ((float) Math.sqrt(vW2[j] / bc2) + eps)));
        }
        for (int b = 0; b < buckets; b++) {
            float g = 0f;
            for (Worker w : workers) g += w.gB2[b];
            g *= scale;
            mB2[b] = b1c * mB2[b] + (1 - b1c) * g;
            vB2[b] = b2c * vB2[b] + (1 - b2c) * g * g;
            b2[b] -= lr * (mB2[b] / bc1) / ((float) Math.sqrt(vB2[b] / bc2) + eps);
        }
        return loss / batch;
    }

    private double validationLoss(int[] idx, Worker w) {
        double loss = 0;
        int[] nn = new int[1];
        for (int s : idx) {
            float out = w.forward(s, nn);
            float pred = sigmoid(out * SCALE / k);
            float target = lambda * (wdl[s] / 2f) + (1 - lambda) * sigmoid(score[s] / k);
            loss += (pred - target) * (pred - target);
        }
        return loss / idx.length;
    }

    /** Mean absolute difference (cp) between the float network and the saved quantised network. */
    private double parity(Path file, int[] idx, Worker w) throws IOException {
        Network net = Network.read(Files.newInputStream(file));
        Accumulators acc = Accumulators.create(net, 1);
        int[] nn = new int[1];
        double diff = 0;
        int n = Math.min(2000, idx.length);
        for (int i = 0; i < n; i++) {
            int s = idx[i];
            float out = w.forward(s, nn) * SCALE;
            julius.game.chessengine.core.Position p = new julius.game.chessengine.core.Position(sampleFen(s));
            acc.refresh(p, 0);
            diff += Math.abs(acc.evaluate(p, 0) - out);
        }
        return diff / n;
    }

    private String sampleFen(int s) {
        char[] board = new char[64];
        java.util.Arrays.fill(board, '.');
        for (int q = offset[s]; q < offset[s + 1]; q++) {
            int v = pieces[q];
            board[v & 63] = "PNBRQKpnbrqk".charAt(v >> 6);
        }
        StringBuilder sb = new StringBuilder();
        for (int r = 7; r >= 0; r--) {
            int empty = 0;
            for (int f = 0; f < 8; f++) {
                char c = board[r * 8 + f];
                if (c == '.') {
                    empty++;
                } else {
                    if (empty > 0) sb.append(empty);
                    empty = 0;
                    sb.append(c);
                }
            }
            if (empty > 0) sb.append(empty);
            if (r > 0) sb.append('/');
        }
        return sb.append(stm[s] == 0 ? " w - - 0 1" : " b - - 0 1").toString();
    }

    void save(Path out) throws IOException {
        try (OutputStream os = Files.newOutputStream(out);
             DataOutputStream d = new DataOutputStream(new java.io.BufferedOutputStream(os))) {
            d.writeBytes("ALNN");
            writeInt(d, buckets > 1 ? 2 : 1);
            writeInt(d, hidden);
            writeInt(d, QA);
            writeInt(d, QB);
            writeInt(d, SCALE);
            if (buckets > 1) writeInt(d, buckets);
            for (float v : w1) writeShort(d, Math.round(v * QA));
            for (float v : b1) writeShort(d, Math.round(v * QA));
            for (float v : w2) writeShort(d, Math.round(v * QB));
            for (float v : b2) writeInt(d, Math.round(v * QA * QB));
        }
    }

    private static void writeInt(DataOutputStream d, int v) throws IOException {
        d.writeInt(Integer.reverseBytes(v));
    }

    private static void writeShort(DataOutputStream d, int v) throws IOException {
        v = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
        d.writeByte(v & 0xFF);
        d.writeByte((v >> 8) & 0xFF);
    }
}
