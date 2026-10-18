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
    /** Whether the accumulator of a perspective (0 White, 1 Black) at a ply is up to date. */
    private final boolean[][] computed;
    /**
     * Feature changes leading to a ply: [white sub1, sub2, add1, add2, black sub1, sub2, add1, add2], -1 = none;
     * first entry COPY for a null move (accumulator unchanged), REFRESH when the perspective's king changed its
     * input bucket or board half (the accumulator is rebuilt from the position).
     */
    private final int[][] delta;
    private static final int COPY = -1, REFRESH = -2;
    /**
     * "Finny tables": per perspective and feature frame, the piece bitboards an accumulator in the back end's
     * cache was last built from. A refresh starts from that cached accumulator and only applies the pieces
     * that changed since, usually a handful instead of all ~30.
     */
    private final long[][][] cachedPieces;
    private final int[] adds = new int[32];
    private final int[] subs = new int[32];

    protected Accumulators(Network net, int maxPly) {
        this.net = net;
        computed = new boolean[2][maxPly];
        delta = new int[maxPly][8];
        cachedPieces = new long[2][net.frames()][12]; // empty boards: the back ends start each cache at the bias
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
        refreshPerspective(p, WHITE, ply);
        refreshPerspective(p, BLACK, ply);
    }

    private void refreshPerspective(Position p, int persp, int ply) {
        int king = Long.numberOfTrailingZeros(p.pieces(persp, KING));
        int frame = net.frame(persp, king);
        long[] cached = cachedPieces[persp][frame];
        int na = 0, ns = 0;
        for (int pc = 0; pc < 12; pc++) {
            long now = p.pieces[pc];
            long gone = cached[pc] & ~now;
            long come = now & ~cached[pc];
            while (gone != 0L) {
                int sq = Long.numberOfTrailingZeros(gone);
                gone &= gone - 1;
                subs[ns++] = net.feature(persp, pc, sq, king);
            }
            while (come != 0L) {
                int sq = Long.numberOfTrailingZeros(come);
                come &= come - 1;
                adds[na++] = net.feature(persp, pc, sq, king);
            }
            cached[pc] = now;
        }
        cacheUpdate(persp, frame, subs, ns, adds, na);
        cacheToPly(persp, frame, ply);
        computed[persp][ply] = true;
    }

    public final void pushNull(int ply) {
        java.util.Arrays.fill(delta[ply + 1], COPY);
        computed[WHITE][ply + 1] = false;
        computed[BLACK][ply + 1] = false;
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
        boolean kingMove = type(pc) == KING;
        for (int persp = 0; persp < 2; persp++) {
            int o = persp * 4;
            int king = Long.numberOfTrailingZeros(p.pieces(persp, KING));
            if (kingMove && us == persp && !net.sameFrame(persp, from, to)) {
                d[o] = REFRESH; // every feature of this perspective changes: rebuild it when needed
                continue;
            }
            if (kingMove && us == persp) {
                // same bucket and board half: the king's own feature moves like any other piece
                d[o] = net.feature(persp, pc, from, from);
                d[o + 1] = subPc2 == NO_PIECE ? -1 : net.feature(persp, subPc2, subSq2, from);
                d[o + 2] = net.feature(persp, addPc1, to, to);
                d[o + 3] = addPc2 == NO_PIECE ? -1 : net.feature(persp, addPc2, addSq2, to);
                continue;
            }
            d[o] = net.feature(persp, pc, from, king);
            d[o + 1] = subPc2 == NO_PIECE ? -1 : net.feature(persp, subPc2, subSq2, king);
            d[o + 2] = net.feature(persp, addPc1, to, king);
            d[o + 3] = addPc2 == NO_PIECE ? -1 : net.feature(persp, addPc2, addSq2, king);
        }
        computed[WHITE][ply + 1] = false;
        computed[BLACK][ply + 1] = false;
    }

    /**
     * Brings one perspective's accumulator at ply up to date: incrementally from the nearest computed ancestor,
     * or rebuilt from the current position when a king bucket change lies on the way.
     */
    private void ensure(Position p, int persp, int ply) {
        boolean[] done = computed[persp];
        if (done[ply]) return;
        int o = persp * 4;
        int k = ply - 1;
        boolean rebuild = delta[ply][o] == REFRESH;
        while (!done[k]) {
            if (delta[k][o] == REFRESH) rebuild = true;
            k--;
        }
        if (rebuild) {
            refreshPerspective(p, persp, ply);
            return;
        }
        for (int j = k + 1; j <= ply; j++) {
            int[] d = delta[j];
            if (d[o] == COPY) copyPly(persp, j - 1, j);
            else updatePly(persp, j - 1, j, d[o], d[o + 1], d[o + 2], d[o + 3]);
            done[j] = true;
        }
    }

    /** Evaluation in centipawns from the side to move's perspective. */
    public final int evaluate(Position p, int ply) {
        ensure(p, WHITE, ply);
        ensure(p, BLACK, ply);
        int bucket = Network.bucketFor(Long.bitCount(p.all), net.buckets);
        return evaluatePly(ply, p.side == WHITE, bucket);
    }

    /** Cached accumulator of a perspective's frame: minus the ns features in subs, plus the na in adds. */
    protected abstract void cacheUpdate(int persp, int frame, int[] subs, int ns, int[] adds, int na);

    /** Copies the cached accumulator of a perspective's frame into its accumulator at ply. */
    protected abstract void cacheToPly(int persp, int frame, int ply);

    /** Copies one perspective's accumulator from ply {@code from} to ply {@code to} (null move). */
    protected abstract void copyPly(int persp, int from, int to);

    /** One perspective at ply {@code to} = ply {@code from} minus sub1/sub2 plus add1/add2 (-1 = none). */
    protected abstract void updatePly(int persp, int from, int to, int sub1, int sub2, int add1, int add2);

    /** Network output for ply, side to move White or Black. */
    protected abstract int evaluatePly(int ply, boolean whiteToMove, int bucket);
}
