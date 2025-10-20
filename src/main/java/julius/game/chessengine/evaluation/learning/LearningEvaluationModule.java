package julius.game.chessengine.evaluation.learning;

import julius.game.chessengine.evaluation.EvaluationContext;
import julius.game.chessengine.evaluation.EvaluationModule;
import julius.game.chessengine.evaluation.MoveContext;
import julius.game.chessengine.evaluation.MaterialModule;
import julius.game.chessengine.tuning.Tuning;

import java.util.Arrays;
import java.util.Objects;

/**
 * Evaluation module that converts the {@link EvaluationContext} into a lightweight feature tensor
 * and queries a trainable neural network to produce tapered centipawn scores.
 */
public final class LearningEvaluationModule implements EvaluationModule, MaterialModule.PawnChangeListener {

    public static final int FEATURE_VECTOR_SIZE = 19;

    private final LearningModelStore modelStore;
    private final double[] features = new double[FEATURE_VECTOR_SIZE];
    private final int[] whitePieceCounts = new int[7];
    private final int[] blackPieceCounts = new int[7];
    private final int[] whitePawnFiles = new int[8];
    private final int[] blackPawnFiles = new int[8];

    private EvaluationContext context;
    private int midgameScore;
    private int endgameScore;
    private boolean dirty = true;

    public LearningEvaluationModule() {
        this(new LearningModelStore());
    }

    LearningEvaluationModule(LearningModelStore store) {
        this.modelStore = Objects.requireNonNull(store, "store");
        if (store.inputSize() != 0 && store.inputSize() != FEATURE_VECTOR_SIZE) {
            throw new IllegalArgumentException("Learning model expects feature length " + store.inputSize()
                    + " but module provides " + FEATURE_VECTOR_SIZE);
        }
    }

    @Override
    public void initialize(EvaluationContext context) {
        rebuildFromContext(context);
    }

    @Override
    public void evaluate(EvaluationContext context) {
        if (!dirty) {
            return;
        }
        rebuildFromContext(context);
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        Objects.requireNonNull(moveContext, "moveContext");
        rebuildFromContext(moveContext.getCurrentContext());
    }

    @Override
    public void undoMove(MoveContext moveContext) {
        Objects.requireNonNull(moveContext, "moveContext");
        rebuildFromContext(moveContext.getCurrentContext());
    }

    @Override
    public int getMidgameScore() {
        return midgameScore;
    }

    @Override
    public int getEndgameScore() {
        return endgameScore;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void markDirty() {
        dirty = true;
    }

    @Override
    public void onPawnAdded(boolean isWhite, int squareIndex) {
        updatePawnFile(isWhite, squareIndex, 1);
        dirty = true;
    }

    @Override
    public void onPawnRemoved(boolean isWhite, int squareIndex) {
        updatePawnFile(isWhite, squareIndex, -1);
        dirty = true;
    }

    private void rebuildFromContext(EvaluationContext ctx) {
        this.context = Objects.requireNonNull(ctx, "context");
        Arrays.fill(whitePieceCounts, 0);
        Arrays.fill(blackPieceCounts, 0);
        Arrays.fill(whitePawnFiles, 0);
        Arrays.fill(blackPawnFiles, 0);

        loadCounts();
        updateFeatures();
        runInference();
        dirty = false;
    }

    private void loadCounts() {
        whitePieceCounts[1] = Long.bitCount(context.getWhitePawns());
        whitePieceCounts[2] = Long.bitCount(context.getWhiteKnights());
        whitePieceCounts[3] = Long.bitCount(context.getWhiteBishops());
        whitePieceCounts[4] = Long.bitCount(context.getWhiteRooks());
        whitePieceCounts[5] = Long.bitCount(context.getWhiteQueens());
        whitePieceCounts[6] = Long.bitCount(context.getWhiteKing());

        blackPieceCounts[1] = Long.bitCount(context.getBlackPawns());
        blackPieceCounts[2] = Long.bitCount(context.getBlackKnights());
        blackPieceCounts[3] = Long.bitCount(context.getBlackBishops());
        blackPieceCounts[4] = Long.bitCount(context.getBlackRooks());
        blackPieceCounts[5] = Long.bitCount(context.getBlackQueens());
        blackPieceCounts[6] = Long.bitCount(context.getBlackKing());

        populatePawnFiles(context.getWhitePawns(), whitePawnFiles);
        populatePawnFiles(context.getBlackPawns(), blackPawnFiles);
    }

    private void populatePawnFiles(long bitboard, int[] target) {
        while (bitboard != 0) {
            int square = Long.numberOfTrailingZeros(bitboard);
            bitboard &= bitboard - 1;
            int file = square % 8;
            target[file]++;
        }
    }

    private void updatePawnFile(boolean isWhite, int squareIndex, int delta) {
        if (squareIndex < 0 || squareIndex >= 64) {
            return;
        }
        int file = squareIndex % 8;
        if (isWhite) {
            whitePawnFiles[file] = Math.max(0, whitePawnFiles[file] + delta);
        } else {
            blackPawnFiles[file] = Math.max(0, blackPawnFiles[file] + delta);
        }
    }

    private void updateFeatures() {
        features[0] = normalize(whitePieceCounts[1] - blackPieceCounts[1], 8.0);
        features[1] = normalize(whitePieceCounts[2] - blackPieceCounts[2], 2.0);
        features[2] = normalize(whitePieceCounts[3] - blackPieceCounts[3], 2.0);
        features[3] = normalize(whitePieceCounts[4] - blackPieceCounts[4], 2.0);
        features[4] = normalize(whitePieceCounts[5] - blackPieceCounts[5], 1.0);
        features[5] = materialBalance();
        features[6] = context.isWhiteToMove() ? 1.0 : -1.0;
        features[7] = (context.isWhiteKingHasCastled() ? 1.0 : 0.0)
                - (context.isBlackKingHasCastled() ? 1.0 : 0.0);
        features[8] = (context.isWhiteKingMoved() ? 1.0 : 0.0)
                - (context.isBlackKingMoved() ? 1.0 : 0.0);
        features[9] = clamp01(context.getHalfmoveClock() / 100.0);
        double phaseScale = Math.max(1.0, Tuning.evaluationBlendScale());
        features[10] = clamp01(context.getPhase() / phaseScale);
        for (int file = 0; file < 8; file++) {
            features[11 + file] = normalize(whitePawnFiles[file] - blackPawnFiles[file], 2.0);
        }
    }

    private double materialBalance() {
        int white = whitePieceCounts[1] * MaterialModule.pawnValue()
                + whitePieceCounts[2] * MaterialModule.knightValue()
                + whitePieceCounts[3] * MaterialModule.bishopValue()
                + whitePieceCounts[4] * MaterialModule.rookValue()
                + whitePieceCounts[5] * MaterialModule.queenValue();
        int black = blackPieceCounts[1] * MaterialModule.pawnValue()
                + blackPieceCounts[2] * MaterialModule.knightValue()
                + blackPieceCounts[3] * MaterialModule.bishopValue()
                + blackPieceCounts[4] * MaterialModule.rookValue()
                + blackPieceCounts[5] * MaterialModule.queenValue();
        return normalize(white - black, 4000.0);
    }

    private void runInference() {
        double[] output = modelStore.infer(features);
        if (output.length != 2) {
            throw new IllegalStateException("Learning model must output two values but returned " + output.length);
        }
        midgameScore = (int) Math.round(output[0]);
        endgameScore = (int) Math.round(output[1]);
    }

    private double normalize(double value, double scale) {
        if (scale == 0.0) {
            return 0.0;
        }
        return value / scale;
    }

    private double clamp01(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
