package julius.game.chessengine.evaluation;

import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.utils.Score;
import julius.game.chessengine.tuning.Tuning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Coordinates a set of {@link EvaluationModule modules} and provides tapered evaluation blending.
 * Each module can signal when its cached contribution is stale so that the pipeline avoids
 * recomputing unrelated features.
 */
public final class EvaluationPipeline {

    private enum ContextAspect {
        BOARD_STRUCTURE,
        ATTACK_MAPS
    }

    private static final class ModuleState {
        private final EvaluationModule module;
        private final EvaluationWeights.ModuleWeight weight;
        private final EnumSet<ContextAspect> dependencies;
        private int midgameCache;
        private int endgameCache;

        private ModuleState(EvaluationModule module, EvaluationWeights.ModuleWeight weight) {
            this.module = module;
            this.weight = weight;
            this.dependencies = dependenciesFor(module);
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
    private ContextFingerprint lastFingerprint;

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
        lastFingerprint = ContextFingerprint.capture(context);
    }

    public void updateContext(EvaluationContext context) {
        if (!initialized) {
            initialize(context);
            return;
        }
        this.context = Objects.requireNonNull(context, "context");
        handleContextChange(ContextFingerprint.capture(context));
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

    private void handleContextChange(ContextFingerprint fingerprint) {
        if (lastFingerprint == null) {
            markAllDirty();
            lastFingerprint = fingerprint;
            return;
        }

        ContextChangeSet changeSet = ContextChangeSet.between(lastFingerprint, fingerprint);
        if (changeSet.isEmpty()) {
            lastFingerprint = fingerprint;
            return;
        }

        boolean modulesMarkedDirty = false;
        if (changeSet.hasStructuralChanges()) {
            for (ModuleState state : modules) {
                if (changeSet.affects(state.dependencies)) {
                    state.module.markDirty();
                    modulesMarkedDirty = true;
                }
            }
        }

        if (modulesMarkedDirty || changeSet.isGameStateChanged()) {
            aggregateDirty = true;
        }

        lastFingerprint = fingerprint;
    }

    private static EnumSet<ContextAspect> dependenciesFor(EvaluationModule module) {
        if (module instanceof MaterialModule || module instanceof ActivityModule) {
            return EnumSet.of(ContextAspect.BOARD_STRUCTURE);
        }
        if (module instanceof PawnStructureModule
                || module instanceof ThreatModule
                || module instanceof KingSafetyModule) {
            return EnumSet.of(ContextAspect.BOARD_STRUCTURE, ContextAspect.ATTACK_MAPS);
        }
        return EnumSet.allOf(ContextAspect.class);
    }

    private static final class ContextFingerprint {
        private final long boardSignature;
        private final long attackSignature;
        private final GameStateEnum gameState;

        private ContextFingerprint(long boardSignature, long attackSignature, GameStateEnum gameState) {
            this.boardSignature = boardSignature;
            this.attackSignature = attackSignature;
            this.gameState = gameState;
        }

        private static ContextFingerprint capture(EvaluationContext context) {
            Objects.requireNonNull(context, "context");
            long boardSignature = 0L;

            boardSignature = mix(boardSignature, context.getWhitePawns());
            boardSignature = mix(boardSignature, context.getBlackPawns());
            boardSignature = mix(boardSignature, context.getWhiteKnights());
            boardSignature = mix(boardSignature, context.getBlackKnights());
            boardSignature = mix(boardSignature, context.getWhiteBishops());
            boardSignature = mix(boardSignature, context.getBlackBishops());
            boardSignature = mix(boardSignature, context.getWhiteRooks());
            boardSignature = mix(boardSignature, context.getBlackRooks());
            boardSignature = mix(boardSignature, context.getWhiteQueens());
            boardSignature = mix(boardSignature, context.getBlackQueens());
            boardSignature = mix(boardSignature, context.getWhiteKing());
            boardSignature = mix(boardSignature, context.getBlackKing());
            boardSignature = mix(boardSignature, context.getWhitePieces());
            boardSignature = mix(boardSignature, context.getBlackPieces());
            boardSignature = mix(boardSignature, context.getAllPieces());
            boardSignature = mix(boardSignature, context.isWhiteToMove() ? 1L : 0L);
            boardSignature = mix(boardSignature, context.getLastMoveDoubleStepPawnIndex());
            boardSignature = mix(boardSignature, context.isWhiteKingMoved());
            boardSignature = mix(boardSignature, context.isBlackKingMoved());
            boardSignature = mix(boardSignature, context.isWhiteRookA1Moved());
            boardSignature = mix(boardSignature, context.isWhiteRookH1Moved());
            boardSignature = mix(boardSignature, context.isBlackRookA8Moved());
            boardSignature = mix(boardSignature, context.isBlackRookH8Moved());
            boardSignature = mix(boardSignature, context.isWhiteKingHasCastled());
            boardSignature = mix(boardSignature, context.isBlackKingHasCastled());
            boardSignature = mix(boardSignature, context.getHalfmoveClock());
            boardSignature = mix(boardSignature, context.getFullmoveNumber());

            long attackSignature = 0L;
            attackSignature = mix(attackSignature, context.getWhiteAttackMap());
            attackSignature = mix(attackSignature, context.getBlackAttackMap());

            return new ContextFingerprint(boardSignature, attackSignature, context.getGameState());
        }

        private static long mix(long seed, long value) {
            long result = seed;
            result ^= value + 0x9e3779b97f4a7c15L + (result << 6) + (result >>> 2);
            return result;
        }

        private static long mix(long seed, int value) {
            return mix(seed, Integer.toUnsignedLong(value));
        }

        private static long mix(long seed, boolean value) {
            return mix(seed, value ? 1L : 0L);
        }
    }

    private static final class ContextChangeSet {
        private final EnumSet<ContextAspect> aspects;
        private final boolean gameStateChanged;

        private ContextChangeSet(EnumSet<ContextAspect> aspects, boolean gameStateChanged) {
            this.aspects = aspects;
            this.gameStateChanged = gameStateChanged;
        }

        private static ContextChangeSet between(ContextFingerprint previous, ContextFingerprint current) {
            EnumSet<ContextAspect> aspects = EnumSet.noneOf(ContextAspect.class);
            if (previous.boardSignature != current.boardSignature) {
                aspects.add(ContextAspect.BOARD_STRUCTURE);
            }
            if (previous.attackSignature != current.attackSignature) {
                aspects.add(ContextAspect.ATTACK_MAPS);
            }
            boolean gameStateChanged = !Objects.equals(previous.gameState, current.gameState);
            return new ContextChangeSet(aspects, gameStateChanged);
        }

        private boolean hasStructuralChanges() {
            return !aspects.isEmpty();
        }

        private boolean affects(EnumSet<ContextAspect> dependencies) {
            for (ContextAspect aspect : aspects) {
                if (dependencies.contains(aspect)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isGameStateChanged() {
            return gameStateChanged;
        }

        private boolean isEmpty() {
            return aspects.isEmpty() && !gameStateChanged;
        }
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
}
