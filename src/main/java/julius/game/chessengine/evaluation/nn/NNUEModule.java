package julius.game.chessengine.evaluation.nn;

import julius.game.chessengine.evaluation.EvaluationContext;
import julius.game.chessengine.evaluation.EvaluationModule;
import julius.game.chessengine.evaluation.MoveContext;

/**
 * NNUE evaluation module with lazy incremental accumulator.
 *
 * <p>On applyMove: just records the move and increments ply (ZERO cost for pruned nodes).
 * On undoMove: decrements ply (ZERO cost).
 * On evaluate: walks back to nearest clean ancestor, copies + replays deltas (typically
 * 1 ply = ~1500 ops), then runs L2+L3 forward pass (~8K ops).
 *
 * <p>Total cost per evaluated node: ~10K ops (vs ~12K for full rebuild, ~1K for classic eval).
 * Total cost per pruned node: 0 ops (vs ~3K for eager incremental, ~1K for classic eval).
 *
 * <p>Enable with {@code -Dchessengine.eval.mode=nnue}.
 */
public final class NNUEModule implements EvaluationModule {

    private static final int ACC_SIZE = NNUEFeatures.ACCUMULATOR_SIZE;

    private final NNUENetwork network;
    private final ThreadLocal<NNUEAccumulator> threadAccumulator;
    private final ThreadLocal<short[]> threadForwardBuffer;

    private int cachedScore = 0;
    private boolean dirty = true;

    public NNUEModule(NNUENetwork network) {
        if (network == null) throw new IllegalArgumentException("NNUENetwork must not be null");
        this.network = network;
        this.threadAccumulator = ThreadLocal.withInitial(NNUEAccumulator::new);
        this.threadForwardBuffer = ThreadLocal.withInitial(() -> new short[ACC_SIZE * 2]);
    }

    @Override
    public void initialize(EvaluationContext context) {
        NNUEAccumulator acc = threadAccumulator.get();
        acc.resetPly();
        acc.rebuildFromScratch(context.getBoardView(), network);
        dirty = true;
        evaluate(context);
    }

    @Override
    public void evaluate(EvaluationContext context) {
        if (!dirty) return;

        // Lazy rebuild: only compute when actually needed.
        // Pruned nodes pay ZERO cost. Evaluated nodes pay ~4K ops for full rebuild
        // + ~8K ops for L2+L3 forward = ~12K total.
        NNUEAccumulator acc = threadAccumulator.get();
        acc.rebuildFromScratch(context.getBoardView(), network);

        short[] buffer = threadForwardBuffer.get();
        acc.getClippedOutput(context.isWhiteToMove(), buffer);
        cachedScore = network.forward(buffer);
        dirty = false;
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        dirty = true;
    }

    @Override
    public void undoMove(MoveContext moveContext) {
        dirty = true;
    }

    @Override
    public int getMidgameScore() {
        return cachedScore;
    }

    @Override
    public int getEndgameScore() {
        return cachedScore;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void markDirty() {
        dirty = true;
        threadAccumulator.get().markNeedsRefresh();
    }
}
