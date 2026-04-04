package julius.game.chessengine.evaluation;

import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.utils.Score;
import julius.game.chessengine.tuning.Tuning;
import lombok.Getter;

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
    private final int blendScale;
    @Getter
    private EvaluationContext context;
    @Getter
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
        EvaluationWeights weights1 = (weights != null ? weights : EvaluationWeights.identity());
        this.blendScale = Tuning.evaluationBlendScale();
        List<ModuleState> moduleStates = new ArrayList<>(modules.size());
        for (EvaluationModule module : modules) {
            EvaluationWeights.ModuleWeight weight = weights1.weightFor(module.getClass());
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
        int score = (int) (blended / blendScale);

        // Mop-up evaluation: in late endgame with material advantage,
        // encourage driving the losing king toward the corner
        if (phase > 200) { // deep endgame
            score += computeMopUpBonus(score);
        }

        return score;
    }

    /**
     * When one side has a significant material advantage in the endgame,
     * bonus for: (1) losing king far from center, (2) kings close together.
     */
    private int computeMopUpBonus(int currentScore) {
        if (context == null || context.getBoardView() == null) {
            return 0;
        }
        // Only apply if material advantage is significant (> 2 pawns)
        if (Math.abs(currentScore) < 200) {
            return 0;
        }
        var board = context.getBoardView();
        long whiteKing = board.getWhiteKing();
        long blackKing = board.getBlackKing();
        if (whiteKing == 0 || blackKing == 0) {
            return 0;
        }
        int wkSq = Long.numberOfTrailingZeros(whiteKing);
        int bkSq = Long.numberOfTrailingZeros(blackKing);

        boolean whiteWinning = currentScore > 0;
        int losingKingSq = whiteWinning ? bkSq : wkSq;

        // Distance of losing king from center (d4/e4/d5/e5)
        int losingFile = losingKingSq & 7;
        int losingRank = losingKingSq >> 3;
        int fileDist = Math.max(3 - losingFile, losingFile - 4);
        int rankDist = Math.max(3 - losingRank, losingRank - 4);
        int cornerDistance = fileDist + rankDist; // 0 at center, up to 6 at corner

        // Distance between kings (encourage winning king to approach)
        int kingFileDiff = Math.abs((wkSq & 7) - (bkSq & 7));
        int kingRankDiff = Math.abs((wkSq >> 3) - (bkSq >> 3));
        int kingDist = Math.max(kingFileDiff, kingRankDiff);
        int closenessBonus = Math.max(0, 7 - kingDist); // 0 when far apart, 6 when adjacent

        int mopUp = cornerDistance * 5 + closenessBonus * 3;
        return whiteWinning ? mopUp : -mopUp;
    }

    public double getScoreDifference() {
        return getBlendedScore() / 100.0;
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

    private int clamp(int phase) {
        if (phase < 0) {
            return 0;
        }
        return Math.min(phase, blendScale);
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
