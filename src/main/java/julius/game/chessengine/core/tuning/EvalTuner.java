package julius.game.chessengine.core.tuning;

import julius.game.chessengine.core.Eval;
import julius.game.chessengine.core.EvalParams;
import julius.game.chessengine.core.Move;
import julius.game.chessengine.core.MoveGen;
import julius.game.chessengine.core.Position;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Fits {@link EvalParams} to labelled positions with Adam on a sigmoid-MSE loss.
 *
 * <p>Input lines: {@code fen;whiteCentipawns;wdl} where wdl is 2 = white won, 1 = draw, 0 = black won.
 * The target blends the game result with the logistic of the reference evaluation.</p>
 *
 * <p>Usage: {@code EvalTuner <data> <out> [epochs] [lambda] [maxPositions]}</p>
 */
public final class EvalTuner {

    private static final int N = EvalParams.COUNT;

    // Compact sparse trace storage
    private int count;
    private int[] start;
    private char[] featIdx;
    private byte[] featCoef;
    private byte[] phase;
    private byte[] scale;
    private float[] target;
    private float[] refEval;
    private float[] result;

    public static void main(String[] args) throws IOException {
        Path data = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int epochs = args.length > 2 ? Integer.parseInt(args[2]) : 600;
        double lambda = args.length > 3 ? Double.parseDouble(args[3]) : 0.3;
        int max = args.length > 4 ? Integer.parseInt(args[4]) : Integer.MAX_VALUE;
        EvalTuner t = new EvalTuner();
        t.load(data, max);
        double k = t.fitK();
        System.out.printf("fitted K = %.2f%n", k);
        t.buildTargets(k, lambda);
        t.train(k, epochs, out);
    }

    private void load(Path data, int max) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(data, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null && lines.size() < max) lines.add(line);
        }
        int n = lines.size();
        int threads = Runtime.getRuntime().availableProcessors();
        int chunk = (n + threads - 1) / threads;
        // Each worker produces its own buffers; merged afterwards.
        List<Part> parts = IntStream.range(0, threads).parallel().mapToObj(w -> {
            Part part = new Part();
            Eval eval = new Eval(false);
            int[] trace = new int[N];
            int[] moves = new int[256];
            Position p = new Position();
            for (int i = w * chunk; i < Math.min(n, (w + 1) * chunk); i++) {
                String[] f = lines.get(i).split(";");
                try {
                    p.setFen(f[0]);
                } catch (RuntimeException e) {
                    continue;
                }
                if (p.inCheck() || !isQuiet(p, moves)) continue;
                java.util.Arrays.fill(trace, 0);
                eval.trace = trace;
                eval.evaluateWhite(p);
                part.add(trace, eval.tracePhase, eval.traceScale, Integer.parseInt(f[1]), Integer.parseInt(f[2]));
            }
            return part;
        }).toList();
        int total = 0, feats = 0;
        for (Part part : parts) {
            total += part.count;
            feats += part.featCount;
        }
        count = total;
        start = new int[total + 1];
        featIdx = new char[feats];
        featCoef = new byte[feats];
        phase = new byte[total];
        scale = new byte[total];
        refEval = new float[total];
        result = new float[total];
        target = new float[total];
        int pi = 0, fi = 0;
        for (Part part : parts) {
            for (int j = 0; j < part.count; j++) {
                start[pi] = fi;
                int s = part.start[j], e = j + 1 < part.count ? part.start[j + 1] : part.featCount;
                for (int q = s; q < e; q++) {
                    featIdx[fi] = part.idx[q];
                    featCoef[fi] = part.coef[q];
                    fi++;
                }
                phase[pi] = part.phase[j];
                scale[pi] = part.scale[j];
                refEval[pi] = part.ref[j];
                result[pi] = part.res[j];
                pi++;
            }
        }
        start[total] = fi;
        System.out.printf("loaded %d quiet positions of %d (%d features, %.1f per position)%n",
                total, n, feats, feats / (double) Math.max(1, total));
    }

    private static boolean isQuiet(Position p, int[] moves) {
        int n = MoveGen.generateNoisy(p, moves, 0);
        for (int i = 0; i < n; i++) {
            int m = moves[i];
            if (!p.isLegal(m)) continue;
            if (Move.isPromotion(m) || p.seeGE(m, 1)) return false;
        }
        return true;
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    /** Fits the logistic scale of the reference evaluation against game results. */
    private double fitK() {
        double best = 0, bestErr = Double.MAX_VALUE;
        for (double k = 100; k <= 600; k += 5) {
            final double kk = k;
            double err = IntStream.range(0, count).parallel()
                    .mapToDouble(i -> {
                        double d = sigmoid(refEval[i] / kk) - result[i];
                        return d * d;
                    }).sum() / count;
            if (err < bestErr) {
                bestErr = err;
                best = k;
            }
        }
        return best;
    }

    private void buildTargets(double k, double lambda) {
        for (int i = 0; i < count; i++) {
            target[i] = (float) (lambda * result[i] + (1 - lambda) * sigmoid(refEval[i] / k));
        }
    }

    private double evaluate(double[] mg, double[] eg, int i) {
        double m = 0, e = 0;
        for (int q = start[i]; q < start[i + 1]; q++) {
            int f = featIdx[q];
            int c = featCoef[q];
            m += mg[f] * c;
            e += eg[f] * c;
        }
        int ph = phase[i];
        return (m * ph + e * (24 - ph) * (scale[i] & 0xFF) / 128.0) / 24.0;
    }

    private void train(double k, int epochs, Path out) throws IOException {
        double[] mg = new double[N], eg = new double[N];
        for (int i = 0; i < N; i++) {
            mg[i] = EvalParams.mg(EvalParams.W[i]);
            eg[i] = EvalParams.eg(EvalParams.W[i]);
        }
        double[] mMg = new double[N], vMg = new double[N], mEg = new double[N], vEg = new double[N];
        double lr = 2.0, b1 = 0.9, b2 = 0.999, eps = 1e-8;
        int threads = Runtime.getRuntime().availableProcessors();
        int validationStart = count - count / 20;
        for (int epoch = 1; epoch <= epochs; epoch++) {
            final int vs = validationStart;
            int chunk = (vs + threads - 1) / threads;
            double[][] gMgParts = new double[threads][];
            double[][] gEgParts = new double[threads][];
            double[] lossParts = new double[threads];
            IntStream.range(0, threads).parallel().forEach(w -> {
                double[] gm = new double[N], ge = new double[N];
                double loss = 0;
                for (int i = w * chunk; i < Math.min(vs, (w + 1) * chunk); i++) {
                    double e = evaluate(mg, eg, i);
                    double s = sigmoid(e / k);
                    double d = s - target[i];
                    loss += d * d;
                    double g = 2 * d * s * (1 - s) / k;
                    int ph = phase[i];
                    double wm = g * ph / 24.0;
                    double we = g * (24 - ph) * (scale[i] & 0xFF) / (128.0 * 24.0);
                    for (int q = start[i]; q < start[i + 1]; q++) {
                        int f = featIdx[q];
                        int c = featCoef[q];
                        gm[f] += wm * c;
                        ge[f] += we * c;
                    }
                }
                gMgParts[w] = gm;
                gEgParts[w] = ge;
                lossParts[w] = loss;
            });
            double loss = 0;
            double[] gMg = new double[N], gEg = new double[N];
            for (int w = 0; w < threads; w++) {
                loss += lossParts[w];
                for (int f = 0; f < N; f++) {
                    gMg[f] += gMgParts[w][f];
                    gEg[f] += gEgParts[w][f];
                }
            }
            double scaleG = 1.0 / vs;
            double bc1 = 1 - Math.pow(b1, epoch), bc2 = 1 - Math.pow(b2, epoch);
            for (int f = 0; f < N; f++) {
                double gm = gMg[f] * scaleG, ge = gEg[f] * scaleG;
                mMg[f] = b1 * mMg[f] + (1 - b1) * gm;
                vMg[f] = b2 * vMg[f] + (1 - b2) * gm * gm;
                mEg[f] = b1 * mEg[f] + (1 - b1) * ge;
                vEg[f] = b2 * vEg[f] + (1 - b2) * ge * ge;
                mg[f] -= lr * (mMg[f] / bc1) / (Math.sqrt(vMg[f] / bc2) + eps);
                eg[f] -= lr * (mEg[f] / bc1) / (Math.sqrt(vEg[f] / bc2) + eps);
            }
            if (epoch % 50 == 0 || epoch == 1 || epoch == epochs) {
                double vloss = IntStream.range(vs, count).parallel().mapToDouble(i -> {
                    double d = sigmoid(evaluate(mg, eg, i) / k) - target[i];
                    return d * d;
                }).sum() / Math.max(1, count - vs);
                System.out.printf("epoch %d train %.6f valid %.6f%n", epoch, loss / vs, vloss);
                write(mg, eg, out);
            }
            if (epoch == epochs * 2 / 3) lr *= 0.3;
        }
    }

    private static void write(double[] mg, double[] eg, Path out) throws IOException {
        int[] w = new int[N];
        for (int i = 0; i < N; i++) w[i] = EvalParams.S((int) Math.round(mg[i]), (int) Math.round(eg[i]));
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            pw.println("# Alieknek core evaluation weights (name index mg eg), generated by EvalTuner");
            EvalParams.save(w, pw);
        }
    }

    private static final class Part {
        int count;
        int featCount;
        char[] idx = new char[1 << 20];
        byte[] coef = new byte[1 << 20];
        int[] start = new int[1 << 14];
        byte[] phase = new byte[1 << 14];
        byte[] scale = new byte[1 << 14];
        float[] ref = new float[1 << 14];
        float[] res = new float[1 << 14];

        void add(int[] trace, int ph, int sc, int refEval, int wdl) {
            if (count == start.length) {
                int n = count * 2;
                start = java.util.Arrays.copyOf(start, n);
                phase = java.util.Arrays.copyOf(phase, n);
                scale = java.util.Arrays.copyOf(scale, n);
                ref = java.util.Arrays.copyOf(ref, n);
                res = java.util.Arrays.copyOf(res, n);
            }
            start[count] = featCount;
            for (int f = 0; f < trace.length; f++) {
                int c = trace[f];
                if (c == 0) continue;
                if (featCount == idx.length) {
                    idx = java.util.Arrays.copyOf(idx, idx.length * 2);
                    coef = java.util.Arrays.copyOf(coef, coef.length * 2);
                }
                idx[featCount] = (char) f;
                coef[featCount] = (byte) Math.max(-127, Math.min(127, c));
                featCount++;
            }
            phase[count] = (byte) ph;
            scale[count] = (byte) sc;
            ref[count] = refEval;
            res[count] = wdl / 2.0f;
            count++;
        }
    }
}
