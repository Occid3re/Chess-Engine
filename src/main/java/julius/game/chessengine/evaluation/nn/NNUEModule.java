package julius.game.chessengine.evaluation.nn;

import julius.game.chessengine.evaluation.EvaluationContext;
import julius.game.chessengine.evaluation.EvaluationModule;
import julius.game.chessengine.evaluation.MoveContext;

/**
 * NNUE evaluation module that integrates with the existing EvaluationPipeline.
 *
 * <p>Uses a HalfKP feature scheme with an incrementally-updatable first-layer
 * accumulator. The L2+L3 forward pass runs only when {@link #evaluate} is called
 * (typically once per search node that needs a static evaluation).
 *
 * <p>Thread safety: each thread gets its own {@link NNUEAccumulator} via ThreadLocal.
 * The shared {@link NNUENetwork} weights are immutable.
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
        acc.rebuildFromScratch(context.getBoardView(), network);
        dirty = true;
        evaluate(context);
    }

    @Override
    public void evaluate(EvaluationContext context) {
        if (!dirty) return;

        // Lazy rebuild: rebuild accumulator from scratch only when evaluation
        // is actually needed. This avoids per-make/unmake overhead for nodes
        // that get pruned without ever calling evaluate().
        NNUEAccumulator acc = threadAccumulator.get();
        acc.rebuildFromScratch(context.getBoardView(), network);

        // Get clipped accumulator output
        short[] buffer = threadForwardBuffer.get();
        acc.getClippedOutput(context.isWhiteToMove(), buffer);

        // Run L2+L3 forward pass
        cachedScore = network.forward(buffer);
        dirty = false;
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        // Lazy strategy: just mark dirty. Accumulator rebuild deferred to evaluate().
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
        // NNUE outputs a single score that implicitly handles game phase
        // through the HalfKP features (piece count encodes phase).
        // Pipeline's taper blend will use this as both midgame and endgame.
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
