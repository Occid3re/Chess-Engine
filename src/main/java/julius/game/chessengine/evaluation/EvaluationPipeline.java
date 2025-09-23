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

    private static final int BLEND_SCALE = 256;

    private static final class ModuleState {
        private final EvaluationModule module;
        private int midgameCache;
        private int endgameCache;

        private ModuleState(EvaluationModule module) {
            this.module = module;
            this.module.markDirty();
        }
    }

    private final List<ModuleState> modules;
    private EvaluationContext context;
    private boolean initialized;
    private boolean aggregateDirty = true;
    private int midgameTotal;
    private int endgameTotal;

    public EvaluationPipeline(List<? extends EvaluationModule> modules) {
        if (modules == null || modules.isEmpty()) {
            throw new IllegalArgumentException("At least one evaluation module is required");
        }
        List<ModuleState> moduleStates = new ArrayList<>(modules.size());
        for (EvaluationModule module : modules) {
            moduleStates.add(new ModuleState(module));
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

    public void setContextWithoutInvalidation(EvaluationContext context) {
        ensureInitialized();
        this.context = Objects.requireNonNull(context, "context");
        aggregateDirty = true;
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
        int midgameWeight = BLEND_SCALE - phase;
        int endgameWeight = phase;
        long blended = (long) midgameTotal * midgameWeight + (long) endgameTotal * endgameWeight;
        return (int) (blended / BLEND_SCALE);
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
        int midgame = 0;
        int endgame = 0;
        for (ModuleState state : modules) {
            if (state.module.isDirty()) {
                state.module.evaluate(context);
            }
            state.midgameCache = state.module.getMidgameScore();
            state.endgameCache = state.module.getEndgameScore();
            midgame += state.midgameCache;
            endgame += state.endgameCache;
        }
        int checkAdjustment = computeCheckAdjustment();
        midgame += checkAdjustment;
        endgame += checkAdjustment;
        midgameTotal = midgame;
        endgameTotal = endgame;
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
        if (phase > BLEND_SCALE) {
            return BLEND_SCALE;
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
