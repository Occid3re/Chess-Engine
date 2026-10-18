package julius.game.chessengine.core.nnue;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import julius.game.chessengine.core.Position;

/**
 * int16 accumulators with explicit SIMD (Vector API, {@code --add-modules jdk.incubator.vector}): 16 lanes per
 * AVX2 register (32 with AVX-512), half the memory traffic of int32, and the 768 x 256 first layer (393 KB)
 * fits a 512 KB L2 cache. The JIT cannot vectorise int16 loops that widen to int32 (the output layer), which
 * is why this is written against the Vector API. Results are bit-identical to {@link ScalarAccumulators}: the
 * trainer clips weights to +-505, so accumulators stay within int16 (checked in {@link Network#read}).
 */
final class VectorAccumulators extends Accumulators {

    private static final VectorSpecies<Short> S = ShortVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Integer> I = IntVector.SPECIES_PREFERRED;
    private static final int PARTS = S.length() / I.length();

    private final short[][] white;
    private final short[][] black;
    private final short[][] w1;
    private final short[] b1;

    /** Finny table accumulators [perspective][frame]. */
    private final short[][][] cache;

    VectorAccumulators(Network net, int maxPly) {
        super(net, maxPly);
        white = new short[maxPly][net.hidden];
        black = new short[maxPly][net.hidden];
        w1 = net.l1WeightsShort();
        b1 = net.l1BiasShort();
        cache = new short[2][net.frames()][];
        for (short[][] side : cache) {
            for (int f = 0; f < side.length; f++) side[f] = b1.clone();
        }
    }

    static int shortLanes() {
        return S.length();
    }

    @Override
    protected void cacheUpdate(int persp, int frame, int[] subs, int ns, int[] adds, int na) {
        short[] acc = cache[persp][frame];
        for (int i = 0; i < ns; i++) sub(acc, w1[subs[i]]);
        for (int i = 0; i < na; i++) add(acc, w1[adds[i]]);
    }

    @Override
    protected void cacheToPly(int persp, int frame, int ply) {
        System.arraycopy(cache[persp][frame], 0, persp == Position.WHITE ? white[ply] : black[ply], 0, net.hidden);
    }

    private static void add(short[] acc, short[] w) {
        for (int i = 0; i < acc.length; i += S.length()) {
            ShortVector.fromArray(S, acc, i).add(ShortVector.fromArray(S, w, i)).intoArray(acc, i);
        }
    }

    private static void sub(short[] acc, short[] w) {
        for (int i = 0; i < acc.length; i += S.length()) {
            ShortVector.fromArray(S, acc, i).sub(ShortVector.fromArray(S, w, i)).intoArray(acc, i);
        }
    }

    @Override
    protected void copyPly(int persp, int from, int to) {
        short[][] s = persp == Position.WHITE ? white : black;
        System.arraycopy(s[from], 0, s[to], 0, net.hidden);
    }

    @Override
    protected void updatePly(int persp, int from, int to, int sub1, int sub2, int add1, int add2) {
        short[][] s = persp == Position.WHITE ? white : black;
        update(s[from], s[to], sub1, sub2, add1, add2);
    }

    private void update(short[] src, short[] dst, int sub1, int sub2, int add1, int add2) {
        short[] s1 = w1[sub1], a1 = w1[add1];
        if (sub2 < 0) {
            for (int i = 0; i < dst.length; i += S.length()) {
                ShortVector.fromArray(S, src, i).add(ShortVector.fromArray(S, a1, i))
                        .sub(ShortVector.fromArray(S, s1, i)).intoArray(dst, i);
            }
        } else if (add2 < 0) {
            short[] s2 = w1[sub2];
            for (int i = 0; i < dst.length; i += S.length()) {
                ShortVector.fromArray(S, src, i).add(ShortVector.fromArray(S, a1, i))
                        .sub(ShortVector.fromArray(S, s1, i)).sub(ShortVector.fromArray(S, s2, i)).intoArray(dst, i);
            }
        } else {
            short[] s2 = w1[sub2], a2 = w1[add2];
            for (int i = 0; i < dst.length; i += S.length()) {
                ShortVector.fromArray(S, src, i).add(ShortVector.fromArray(S, a1, i))
                        .add(ShortVector.fromArray(S, a2, i)).sub(ShortVector.fromArray(S, s1, i))
                        .sub(ShortVector.fromArray(S, s2, i)).intoArray(dst, i);
            }
        }
    }

    @Override
    protected int evaluatePly(int ply, boolean whiteToMove, int bucket) {
        short[] us = whiteToMove ? white[ply] : black[ply];
        short[] them = whiteToMove ? black[ply] : white[ply];
        long sum = (long) screluDot(us, net.l2Weights[2 * bucket]) + screluDot(them, net.l2Weights[2 * bucket + 1]);
        long out = sum * 256 / net.qa + net.l2Bias[bucket];
        return (int) (out * net.scale / ((long) net.qa * net.qb));
    }

    /** Sum of (v * w * v) >> 8 per neuron with v = clamp(acc, 0, QA), exactly as {@link Network#evaluate}. */
    private int screluDot(short[] acc, int[] w) {
        short qa = (short) net.qa;
        IntVector sum = IntVector.zero(I);
        for (int i = 0; i < acc.length; i += S.length()) {
            ShortVector v = ShortVector.fromArray(S, acc, i).max((short) 0).min(qa);
            for (int part = 0; part < PARTS; part++) {
                IntVector vi = (IntVector) v.convertShape(VectorOperators.S2I, I, part);
                IntVector wv = IntVector.fromArray(I, w, i + part * I.length());
                sum = sum.add(vi.mul(wv).mul(vi).lanewise(VectorOperators.ASHR, 8));
            }
        }
        return sum.reduceLanes(VectorOperators.ADD);
    }
}
