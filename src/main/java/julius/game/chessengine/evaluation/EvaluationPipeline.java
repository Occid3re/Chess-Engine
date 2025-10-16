package julius.game.chessengine.evaluation;

import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.utils.Score;
import julius.game.chessengine.tuning.Tuning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Coordinates a set of {@link EvaluationModule modules} and provides tapered evaluation blending.
 * Each module can signal when its cached contribution is stale so that the pipeline avoids
 * recomputing unrelated features.
 */
public final class EvaluationPipeline {

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
    private final int blendScale;
    private EvaluationContext context;
    private boolean initialized;
    private boolean aggregateDirty = true;
    private int midgameTotal;
    private int endgameTotal;

    public EvaluationPipeline(List<? extends EvaluationModule> modules) {
        this(modules, EvaluationWeights.identity());
    }

    public EvaluationPipeline(List<? extends EvaluationModule> modules, EvaluationWeights weights) {
        if (modules == null || modules.isEmpty()) {
            throw new IllegalArgumentException("At least one evaluation module is required");
        }
        this.weights = (weights != null ? weights : EvaluationWeights.identity());
        this.blendScale = Tuning.evaluationBlendScale();
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
        long profilerStart = EvaluationProfiler.onRefreshStart();
        double midgame = 0.0;
        double endgame = 0.0;
        int evaluatedModules = 0;
        for (ModuleState state : modules) {
            if (state.module.isDirty()) {
                state.module.evaluate(context);
                evaluatedModules++;
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
        EvaluationProfiler.onRefreshEnd(profilerStart, evaluatedModules);
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

    private int clamp(int phase) {
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

    public static void enableProfiling() {
        EvaluationProfiler.enable();
    }

    public static void disableProfiling() {
        EvaluationProfiler.disable();
    }

    public static void resetProfiling() {
        EvaluationProfiler.reset();
    }

    public static boolean isProfilingEnabled() {
        return EvaluationProfiler.isEnabled();
    }

    public static EvaluationStats snapshotProfiling() {
        return EvaluationProfiler.snapshot();
    }

    public record EvaluationStats(long refreshCalls, long modulesEvaluated, long refreshNanos) {
    }

    private static final class EvaluationProfiler {
        private static final LongAdder refreshCalls = new LongAdder();
        private static final LongAdder modulesEvaluated = new LongAdder();
        private static final LongAdder refreshNanos = new LongAdder();
        private static volatile boolean enabled = Boolean.getBoolean("chessengine.eval.profile");

        private EvaluationProfiler() {
        }

        static boolean isEnabled() {
            return enabled;
        }

        static long onRefreshStart() {
            if (!enabled) {
                return 0L;
            }
            refreshCalls.increment();
            return System.nanoTime();
        }

        static void onRefreshEnd(long start, int evaluatedModules) {
            if (!enabled) {
                return;
            }
            if (evaluatedModules > 0) {
                modulesEvaluated.add(evaluatedModules);
            }
            if (start != 0L) {
                refreshNanos.add(System.nanoTime() - start);
            }
        }

        static void enable() {
            enabled = true;
        }

        static void disable() {
            enabled = false;
            reset();
        }

        static void reset() {
            refreshCalls.reset();
            modulesEvaluated.reset();
            refreshNanos.reset();
        }

        static EvaluationStats snapshot() {
            if (!enabled) {
                return new EvaluationStats(0, 0, 0);
            }
            return new EvaluationStats(
                    refreshCalls.sum(),
                    modulesEvaluated.sum(),
                    refreshNanos.sum()
            );
        }
    }
}
