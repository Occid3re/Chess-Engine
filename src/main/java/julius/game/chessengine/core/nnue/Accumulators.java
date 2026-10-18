package julius.game.chessengine.core.nnue;

import julius.game.chessengine.core.Move;
import julius.game.chessengine.core.Position;

import java.lang.reflect.Constructor;

import static julius.game.chessengine.core.Position.*;

/**
 * Per-ply accumulator stack for a search thread, updated lazily: {@link #push} only records which features
 * the move changes, and the accumulators are brought up to date when a position is evaluated. Nodes that never
 * evaluate (TT cutoffs, draws, nodes in check) cost no accumulator work at all.
 * {@link #push} must be called before the move is made on the position (it needs the pre-move board),
 * {@link #pushNull} before a null move.
 *
 * <p>Two storage back ends: {@link VectorAccumulators} (int16, explicit SIMD through the Vector API; needs
 * {@code --add-modules jdk.incubator.vector}) and {@link ScalarAccumulators} (int32 loops the JIT vectorises).
 * {@link #create} picks the vector one when the JVM has the module, unless {@code -Dcore.simd=false}. Both
 * compute exactly the same evaluations.</p>
 */
public abstract class Accumulators {

    protected final Network net;
    /** Whether the accumulators of a ply are up to date. */
    private final boolean[] computed;
    /**
     * Feature changes leading to a ply: [white sub1, sub2, add1, add2, black sub1, sub2, add1, add2], -1 = none;
     * all -1 for a null move (the accumulators are copied unchanged).
     */
    private final int[][] delta;

    protected Accumulators(Network net, int maxPly) {
        this.net = net;
        computed = new boolean[maxPly];
        delta = new int[maxPly][8];
    }

    /** Constructor of the Vector API back end, or null; looked up reflectively so this class never links it. */
    private static final Constructor<? extends Accumulators> VECTOR;
    /** int16 lanes per vector of that back end (0 without it). */
    private static final int LANES;

    static {
        Constructor<? extends Accumulators> ctor = null;
        int lanes = 0;
        if (!"false".equalsIgnoreCase(System.getProperty("core.simd"))
                && ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent()) {
            try {
                Class<? extends Accumulators> c = Class.forName("julius.game.chessengine.core.nnue.VectorAccumulators")
                        .asSubclass(Accumulators.class);
                lanes = (Integer) c.getDeclaredMethod("shortLanes").invoke(null);
                ctor = c.getDeclaredConstructor(Network.class, int.class);
            } catch (Throwable e) { // class missing or the module not resolved: use the scalar back end
                ctor = null;
                lanes = 0;
            }
        }
        VECTOR = ctor;
        LANES = lanes;
    }

    /** Accumulators for a network: the Vector API back end when available and the layer size fits it. */
    public static Accumulators create(Network net, int maxPly) {
        if (VECTOR != null && net.hidden % LANES == 0) {
            try {
                return VECTOR.newInstance(net, maxPly);
            } catch (ReflectiveOperationException | LinkageError e) {
                // fall through to the scalar back end
            }
        }
        return new ScalarAccumulators(net, maxPly);
    }

    /** Which back end {@link #create} uses, for diagnostics. */
    public static String backend() {
        return VECTOR != null ? "Vector API int16, " + LANES + " lanes" : "scalar int32";
    }

    public final void refresh(Position p, int ply) {
        refreshPly(p, ply);
        computed[ply] = true;
    }

    public final void pushNull(int ply) {
        java.util.Arrays.fill(delta[ply + 1], -1);
        computed[ply + 1] = false;
    }

    public final void push(Position p, int m, int ply) {
        int from = Move.from(m), to = Move.to(m), flag = Move.flag(m);
        int pc = p.board[from];
        int us = color(pc);
        int subPc2 = NO_PIECE, subSq2 = -1, addPc2 = NO_PIECE, addSq2 = -1;
        int addPc1 = pc;
        if ((flag & Move.PROMO_N) != 0) addPc1 = piece(us, KNIGHT + (flag & 3));
        if (flag == Move.EP_CAPTURE) {
            subSq2 = to ^ 8;
            subPc2 = p.board[subSq2];
        } else if ((flag & Move.CAPTURE) != 0) {
            subSq2 = to;
            subPc2 = p.board[to];
        } else if (flag == Move.KING_CASTLE || flag == Move.QUEEN_CASTLE) {
            int rookFrom = flag == Move.KING_CASTLE ? to + 1 : to - 2;
            int rookTo = flag == Move.KING_CASTLE ? to - 1 : to + 1;
            subSq2 = rookFrom;
            subPc2 = p.board[rookFrom];
            addSq2 = rookTo;
            addPc2 = subPc2;
        }
        int[] d = delta[ply + 1];
        for (int persp = 0; persp < 2; persp++) {
            int o = persp * 4;
            d[o] = Network.featureIndex(persp, pc, from);
            d[o + 1] = subPc2 == NO_PIECE ? -1 : Network.featureIndex(persp, subPc2, subSq2);
            d[o + 2] = Network.featureIndex(persp, addPc1, to);
            d[o + 3] = addPc2 == NO_PIECE ? -1 : Network.featureIndex(persp, addPc2, addSq2);
        }
        computed[ply + 1] = false;
    }

    /** Brings the accumulators of ply up to date from the nearest computed ancestor. */
    private void ensure(int ply) {
        if (computed[ply]) return;
        int k = ply - 1;
        while (!computed[k]) k--;
        for (int j = k + 1; j <= ply; j++) {
            int[] d = delta[j];
            if (d[0] < 0) copyPly(j - 1, j);
            else updatePly(j - 1, j, d);
            computed[j] = true;
        }
    }

    /** Evaluation in centipawns from the side to move's perspective. */
    public final int evaluate(Position p, int ply) {
        ensure(ply);
        int bucket = Network.bucketFor(Long.bitCount(p.all), net.buckets);
        return evaluatePly(ply, p.side == WHITE, bucket);
    }

    /** Both perspectives of ply from scratch. */
    protected abstract void refreshPly(Position p, int ply);

    /** Copies ply {@code from} into ply {@code to} (null move). */
    protected abstract void copyPly(int from, int to);

    /** Ply {@code to} = ply {@code from} with the feature changes d (see {@link #delta}). */
    protected abstract void updatePly(int from, int to, int[] d);

    /** Network output for ply, side to move White or Black. */
    protected abstract int evaluatePly(int ply, boolean whiteToMove, int bucket);
}
