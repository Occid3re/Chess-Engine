package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.ImmutableBoardView;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.utils.Score;
import julius.game.chessengine.tuning.Tuning;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Coordinates a set of {@link EvaluationModule modules} and provides tapered evaluation blending.
 * Each module can signal when its cached contribution is stale so that the pipeline avoids
 * recomputing unrelated features.
 */
public final class EvaluationPipeline {

    private static final class ModuleState {
        private final EvaluationModule module;
        private final EvaluationWeights.ModuleWeight weight;
        private final EnumSet<EvaluationContextAspect> contextAspects;
        private int midgameCache;
        private int endgameCache;

        private ModuleState(EvaluationModule module, EvaluationWeights.ModuleWeight weight) {
            this.module = module;
            this.weight = weight;
            this.contextAspects = resolveAspects(module);
            this.module.markDirty();
        }

        private static EnumSet<EvaluationContextAspect> resolveAspects(EvaluationModule module) {
            if (module instanceof ContextAwareEvaluationModule aware) {
                EnumSet<EvaluationContextAspect> aspects = aware.getContextAspects();
                if (aspects == null || aspects.isEmpty()) {
                    return EnumSet.allOf(EvaluationContextAspect.class);
                }
                return EnumSet.copyOf(aspects);
            }
            return EnumSet.allOf(EvaluationContextAspect.class);
        }
    }

    private final List<ModuleState> modules;
    private final EvaluationWeights weights;
    private final int blendScale;
    private EvaluationContext context;
    private boolean initialized;
    private boolean aggregateDirty = true;
    private final EnumSet<EvaluationContextAspect> pendingContextChanges =
            EnumSet.noneOf(EvaluationContextAspect.class);
    private ContextFingerprint fingerprint;
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
        fingerprint = ContextFingerprint.capture(this.context);
        pendingContextChanges.clear();
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
        EvaluationContext next = Objects.requireNonNull(context, "context");
        ContextFingerprint nextFingerprint = ContextFingerprint.capture(next);
        EnumSet<EvaluationContextAspect> changes =
                (fingerprint == null)
                        ? EnumSet.allOf(EvaluationContextAspect.class)
                        : nextFingerprint.diff(fingerprint);
        boolean stateChanged = nextFingerprint.stateChanged(fingerprint);
        this.context = next;
        if (!changes.isEmpty()) {
            pendingContextChanges.addAll(changes);
        }
        if (stateChanged || !changes.isEmpty()) {
            aggregateDirty = true;
        }
        fingerprint = nextFingerprint;
    }

    public void applyMove(MoveContext moveContext) {
        ensureInitialized();
        pendingContextChanges.clear();
        for (ModuleState state : modules) {
            state.module.applyMove(moveContext);
        }
        aggregateDirty = true;
    }

    public void undoMove(MoveContext moveContext) {
        ensureInitialized();
        pendingContextChanges.clear();
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
        propagatePendingContextChanges();
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

    private void propagatePendingContextChanges() {
        if (pendingContextChanges.isEmpty()) {
            return;
        }
        for (ModuleState state : modules) {
            for (EvaluationContextAspect aspect : pendingContextChanges) {
                if (state.contextAspects.contains(aspect)) {
                    state.module.markDirty();
                    break;
                }
            }
        }
        pendingContextChanges.clear();
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

    private static final class ContextFingerprint {
        private static final long STRUCTURE_SEED = 0x9E3779B97F4A7C15L;
        private static final long ATTACK_SEED = 0x517CC1B727220A95L;

        private final long structureSignature;
        private final long attackSignature;
        private final GameStateEnum gameState;

        private ContextFingerprint(long structureSignature, long attackSignature, GameStateEnum gameState) {
            this.structureSignature = structureSignature;
            this.attackSignature = attackSignature;
            this.gameState = gameState;
        }

        private static ContextFingerprint capture(EvaluationContext context) {
            if (context == null) {
                return new ContextFingerprint(0L, 0L, null);
            }
            ImmutableBoardView board = context.getBoardView();
            long structure = STRUCTURE_SEED;
            if (board != null) {
                structure = mix(structure, board.isWhitesTurn() ? 1L : 0L);
                structure = mix(structure, board.getWhitePawns());
                structure = mix(structure, board.getBlackPawns());
                structure = mix(structure, board.getWhiteKnights());
                structure = mix(structure, board.getBlackKnights());
                structure = mix(structure, board.getWhiteBishops());
                structure = mix(structure, board.getBlackBishops());
                structure = mix(structure, board.getWhiteRooks());
                structure = mix(structure, board.getBlackRooks());
                structure = mix(structure, board.getWhiteQueens());
                structure = mix(structure, board.getBlackQueens());
                structure = mix(structure, board.getWhiteKing());
                structure = mix(structure, board.getBlackKing());
                structure = mix(structure, board.getWhitePieces());
                structure = mix(structure, board.getBlackPieces());
                structure = mix(structure, board.getAllPieces());
                structure = mix(structure, board.isWhiteKingMoved() ? 1L : 0L);
                structure = mix(structure, board.isBlackKingMoved() ? 1L : 0L);
                structure = mix(structure, board.isWhiteRookA1Moved() ? 1L : 0L);
                structure = mix(structure, board.isWhiteRookH1Moved() ? 1L : 0L);
                structure = mix(structure, board.isBlackRookA8Moved() ? 1L : 0L);
                structure = mix(structure, board.isBlackRookH8Moved() ? 1L : 0L);
                structure = mix(structure, board.isWhiteKingHasCastled() ? 1L : 0L);
                structure = mix(structure, board.isBlackKingHasCastled() ? 1L : 0L);
                structure = mix(structure, board.getLastMoveDoubleStepPawnIndex());
                structure = mix(structure, board.getHalfmoveClock());
                structure = mix(structure, board.getFullmoveNumber());
            }
            structure = mix(structure, context.getPhase());
            long attacks = ATTACK_SEED;
            attacks = mix(attacks, context.getWhiteAttackMap());
            attacks = mix(attacks, context.getBlackAttackMap());
            return new ContextFingerprint(structure, attacks, context.getGameState());
        }

        private EnumSet<EvaluationContextAspect> diff(ContextFingerprint previous) {
            EnumSet<EvaluationContextAspect> changes = EnumSet.noneOf(EvaluationContextAspect.class);
            if (previous == null || structureSignature != previous.structureSignature) {
                changes.add(EvaluationContextAspect.STRUCTURE);
            }
            if (previous == null || attackSignature != previous.attackSignature) {
                changes.add(EvaluationContextAspect.ATTACK_MAPS);
            }
            return changes;
        }

        private boolean stateChanged(ContextFingerprint previous) {
            return previous == null || !Objects.equals(gameState, previous.gameState);
        }

        private static long mix(long accumulator, long value) {
            long x = accumulator ^ value;
            x ^= Long.rotateLeft(x, 21);
            x *= 0x9E3779B97F4A7C15L;
            return x;
        }
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
