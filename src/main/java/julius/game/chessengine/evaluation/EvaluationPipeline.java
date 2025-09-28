package julius.game.chessengine.evaluation;

import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.utils.Score;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Coordinates a set of {@link EvaluationModule modules} and provides tapered evaluation blending.
 * Each module can signal when its cached contribution is stale so that the pipeline avoids
 * recomputing unrelated features.
 */
public final class EvaluationPipeline {

    private final int blendScale;

    private static final class ModuleState {
        private final EvaluationModule module;
        private final EvaluationWeights.ModuleWeight weight;
        private int midgameCache;
        private int endgameCache;

        private ModuleState(EvaluationModule module, EvaluationWeights.ModuleWeight weight) {
            this.module = module;
            this.weight = weight;
            this.module.markDirty();
        }
    }

    private final List<ModuleState> modules;
    private final EvaluationWeights weights;
    private EvaluationContext context;
    private boolean initialized;
    private boolean aggregateDirty = true;
    private int midgameTotal;
    private int endgameTotal;

    public EvaluationPipeline(List<? extends EvaluationModule> modules) {
        this(modules, EvaluationWeights.identity(), 256);
    }

    public EvaluationPipeline(List<? extends EvaluationModule> modules, EvaluationWeights weights) {
        this(modules, weights, 256);
    }

    public EvaluationPipeline(List<? extends EvaluationModule> modules, EvaluationWeights weights, int blendScale) {
        if (modules == null || modules.isEmpty()) {
            throw new IllegalArgumentException("At least one evaluation module is required");
        }
        this.weights = (weights != null ? weights : EvaluationWeights.identity());
        this.blendScale = Math.max(1, blendScale);
        List<ModuleState> moduleStates = new ArrayList<>(modules.size());
        for (EvaluationModule module : modules) {
            EvaluationWeights.ModuleWeight weight = this.weights.weightFor(module.getClass());
            moduleStates.add(new ModuleState(module, weight));
        }
        this.modules = Collections.unmodifiableList(moduleStates);
    }

    public void initialize(EvaluationContext context) {
        this.context = Objects.requireNonNull(context, "context");
        for (ModuleState state : modules) {
            state.module.initialize(context);
            state.module.markDirty();
        }
        initialized = true;
        aggregateDirty = true;
        refreshTotals();
    }

    public void updateContext(EvaluationContext context) {
        if (!initialized) {
            initialize(context);
            return;
        }
        this.context = Objects.requireNonNull(context, "context");
        markAllDirty();
    }

    public void applyMove(MoveContext moveContext) {
        ensureInitialized();
        for (ModuleState state : modules) {
            state.module.applyMove(moveContext);
        }
        aggregateDirty = true;
    }

    public void undoMove(MoveContext moveContext) {
        ensureInitialized();
        for (ModuleState state : modules) {
            state.module.undoMove(moveContext);
        }
        aggregateDirty = true;
    }

    public int getMidgameScore() {
        refreshTotals();
        return midgameTotal;
    }

    public int getEndgameScore() {
        refreshTotals();
        return endgameTotal;
    }

    public int getBlendedScore() {
        refreshTotals();
        if (context == null) {
            return 0;
        }
        int phase = clamp(context.getPhase());
        int midgameWeight = blendScale - phase;
        int endgameWeight = phase;
        long blended = (long) midgameTotal * midgameWeight + (long) endgameTotal * endgameWeight;
        return (int) (blended / blendScale);
    }

    public double getScoreDifference() {
        return getBlendedScore() / 100.0;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public EvaluationContext getContext() {
        return context;
    }

    private void refreshTotals() {
        ensureInitialized();
        if (!aggregateDirty) {
            return;
        }
        double midgame = 0.0;
        double endgame = 0.0;
        for (ModuleState state : modules) {
            if (state.module.isDirty()) {
                state.module.evaluate(context);
            }
            state.midgameCache = state.module.getMidgameScore();
            state.endgameCache = state.module.getEndgameScore();
            midgame += state.midgameCache * state.weight.midgame();
            endgame += state.endgameCache * state.weight.endgame();
        }
        int checkAdjustment = computeCheckAdjustment();
        midgame += checkAdjustment;
        endgame += checkAdjustment;
        midgameTotal = (int) Math.round(midgame);
        endgameTotal = (int) Math.round(endgame);
        aggregateDirty = false;
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Pipeline has not been initialized yet");
        }
    }

    private void markAllDirty() {
        for (ModuleState state : modules) {
            state.module.markDirty();
        }
        aggregateDirty = true;
    }

    private static int clamp(int phase) {
        if (phase < 0) {
            return 0;
        }
        if (phase > blendScale) {
            return blendScale;
        }
        return phase;
    }

    private int computeCheckAdjustment() {
        if (context == null) {
            return 0;
        }
        GameStateEnum state = context.getGameState();
        if (state == null) {
            return 0;
        }
        return switch (state) {
            case BLACK_IN_CHECK -> Score.CHECK;
            case WHITE_IN_CHECK -> -Score.CHECK;
            default -> 0;
        };
    }
}
