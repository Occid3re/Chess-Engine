package julius.game.chessengine.utils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.evaluation.ActivityModule;
import julius.game.chessengine.evaluation.EvaluationContext;
import julius.game.chessengine.evaluation.EvaluationParameters;
import julius.game.chessengine.evaluation.EvaluationPipeline;
import julius.game.chessengine.evaluation.EvaluationWeights;
import julius.game.chessengine.evaluation.KingSafetyModule;
import julius.game.chessengine.evaluation.MaterialModule;
import julius.game.chessengine.evaluation.MoveContext;
import julius.game.chessengine.evaluation.PawnStructureModule;
import julius.game.chessengine.evaluation.PieceSquareModule;
import julius.game.chessengine.evaluation.ThreatModule;

import java.util.List;
import java.util.Objects;

/**
 * Central entry point for the evaluation pipeline.  The legacy score bookkeeping previously
 * maintained in this class has been replaced by incremental evaluation modules coordinated by the
 * {@link EvaluationPipeline}.  The public API now exposes only tapered totals that originate from
 * the pipeline itself.
 */
public class Score {

    private static final ThreadLocal<ScoreFactory> THREAD_FACTORY = new ThreadLocal<>();
    private static final ThreadLocal<EvaluationParameters> THREAD_PARAMETERS = new ThreadLocal<>();
    private static volatile EvaluationParameters GLOBAL_PARAMETERS = EvaluationParameters.defaults();
    private static volatile ScoreFactory GLOBAL_FACTORY = bitBoard -> new Score(bitBoard, EvaluationWeights.identity(), resolveParameters());

    public static final int CHECKMATE = 100000;
    public static final int CHECK = 50;
    public static final int DRAW = 0;
    public static final int KILLER_MOVE_SCORE = 10000;

    private final MaterialModule materialModule;
    private final PawnStructureModule pawnStructureModule;
    private final PieceSquareModule pieceSquareModule;
    private final ActivityModule activityModule;
    private final KingSafetyModule kingSafetyModule;
    private final ThreatModule threatModule;

    @JsonIgnore
    private final EvaluationWeights weights;
    @JsonIgnore
    private final EvaluationParameters parameters;
    private final EvaluationPipeline evaluationPipeline;
    @JsonIgnore
    private EvaluationContext evaluationContext;
    @JsonIgnore
    private EvaluationContext spareEvaluationContext;

    public Score() {
        this(EvaluationWeights.identity(), resolveParameters());
    }

    public Score(EvaluationWeights weights) {
        this(weights, resolveParameters());
    }

    public Score(EvaluationWeights weights, EvaluationParameters parameters) {
        this.weights = weights != null ? weights : EvaluationWeights.identity();
        this.parameters = parameters != null ? parameters : resolveParameters();
        this.materialModule = new MaterialModule(this.parameters);
        this.pawnStructureModule = new PawnStructureModule(this.parameters);
        this.pieceSquareModule = new PieceSquareModule(this.parameters);
        this.activityModule = new ActivityModule();
        this.kingSafetyModule = new KingSafetyModule(this.parameters);
        this.threatModule = new ThreatModule();
        materialModule.setPawnChangeListener(pawnStructureModule);
        this.evaluationPipeline = new EvaluationPipeline(List.of(
                materialModule,
                pawnStructureModule,
                pieceSquareModule,
                activityModule,
                kingSafetyModule,
                threatModule
        ), this.weights, this.parameters);
    }

    public Score(BitBoard bitBoard, EvaluationWeights weights) {
        this(bitBoard, weights, resolveParameters());
    }

    public Score(BitBoard bitBoard, EvaluationWeights weights, EvaluationParameters parameters) {
        this(weights, parameters);
        initializeFrom(bitBoard);
    }

    public Score(Score other) {
        this(other != null ? other.weights : null, other != null ? other.parameters : null);
        if (other != null && other.evaluationContext != null) {
            this.evaluationContext = other.evaluationContext.copy();
            if (other.spareEvaluationContext != null) {
                this.spareEvaluationContext = other.spareEvaluationContext.copy();
            }
            if (other.evaluationPipeline.isInitialized()) {
                evaluationPipeline.initialize(this.evaluationContext);
            }
        }
    }

    public static Score initializeScore(BitBoard bitBoard) {
        Objects.requireNonNull(bitBoard, "bitBoard");
        return resolveFactory().create(bitBoard);
    }

    private void initializeFrom(BitBoard bitBoard) {
        Objects.requireNonNull(bitBoard, "bitBoard");
        EvaluationContext context = EvaluationContext.from(bitBoard, null);
        this.evaluationContext = context;
        this.spareEvaluationContext = null;
        evaluationPipeline.initialize(context);
    }

    private static ScoreFactory resolveFactory() {
        ScoreFactory local = THREAD_FACTORY.get();
        return (local != null) ? local : GLOBAL_FACTORY;
    }

    private static EvaluationParameters resolveParameters() {
        EvaluationParameters local = THREAD_PARAMETERS.get();
        return (local != null) ? local : GLOBAL_PARAMETERS;
    }

    public static AutoCloseable useFactory(ScoreFactory factory) {
        Objects.requireNonNull(factory, "factory");
        ScoreFactory previous = THREAD_FACTORY.get();
        THREAD_FACTORY.set(factory);
        return () -> {
            if (previous == null) {
                THREAD_FACTORY.remove();
            } else {
                THREAD_FACTORY.set(previous);
            }
        };
    }

    public static AutoCloseable useEvaluationWeights(EvaluationWeights weights) {
        return useEvaluationConfig(weights, resolveParameters());
    }

    public static ScoreFactory forEvaluationWeights(EvaluationWeights weights) {
        return forEvaluationConfig(weights, resolveParameters());
    }

    public static AutoCloseable useEvaluationParameters(EvaluationParameters parameters) {
        EvaluationParameters resolved = parameters != null ? parameters : EvaluationParameters.defaults();
        EvaluationParameters previous = THREAD_PARAMETERS.get();
        THREAD_PARAMETERS.set(resolved);
        return () -> {
            if (previous == null) {
                THREAD_PARAMETERS.remove();
            } else {
                THREAD_PARAMETERS.set(previous);
            }
        };
    }

    public static AutoCloseable useEvaluationConfig(EvaluationWeights weights, EvaluationParameters parameters) {
        EvaluationParameters resolvedParameters = parameters != null ? parameters : EvaluationParameters.defaults();
        EvaluationWeights resolvedWeights = weights != null ? weights : EvaluationWeights.identity();
        AutoCloseable parameterHandle = useEvaluationParameters(resolvedParameters);
        AutoCloseable factoryHandle = useFactory(forEvaluationConfig(resolvedWeights, resolvedParameters));
        return () -> {
            try {
                factoryHandle.close();
            } finally {
                parameterHandle.close();
            }
        };
    }

    public static ScoreFactory forEvaluationConfig(EvaluationWeights weights, EvaluationParameters parameters) {
        EvaluationWeights resolvedWeights = weights != null ? weights : EvaluationWeights.identity();
        EvaluationParameters resolvedParameters = parameters != null ? parameters : resolveParameters();
        return bitBoard -> new Score(bitBoard, resolvedWeights, resolvedParameters);
    }

    public static void setGlobalFactory(ScoreFactory factory) {
        GLOBAL_FACTORY = Objects.requireNonNull(factory, "factory");
    }

    public void refresh(BitBoard bitBoard, GameStateEnum state) {
        Objects.requireNonNull(bitBoard, "bitBoard");
        if (evaluationContext == null) {
            evaluationContext = EvaluationContext.from(bitBoard, state);
        } else {
            evaluationContext.updateFrom(bitBoard, state);
        }
        EvaluationContext updated = evaluationContext;

        if (!evaluationPipeline.isInitialized()) {
            evaluationPipeline.initialize(updated);
        } else {
            evaluationPipeline.updateContext(updated);
        }

        // Prime the aggregate totals so subsequent lookups observe the refreshed context immediately.
        evaluationPipeline.getBlendedScore();
    }

    public void applyMove(BitBoard bitBoard, int move, GameStateEnum state) {
        Objects.requireNonNull(bitBoard, "bitBoard");
        EvaluationContext previous = this.evaluationContext;
        EvaluationContext next = spareEvaluationContext;
        if (next == null) {
            next = EvaluationContext.from(bitBoard, state);
        } else {
            next.updateFrom(bitBoard, state);
        }
        this.evaluationContext = next;
        this.spareEvaluationContext = previous;

        if (next == null || !evaluationPipeline.isInitialized()) {
            evaluationPipeline.initialize(next);
            return;
        }

        synchronizePipeline(next);
        MoveContext moveContext = new MoveContext(move, previous, next);
        evaluationPipeline.applyMove(moveContext);
    }

    public void undoMove(BitBoard bitBoard, int move, GameStateEnum state) {
        Objects.requireNonNull(bitBoard, "bitBoard");
        EvaluationContext previous = this.evaluationContext;
        EvaluationContext next = spareEvaluationContext;
        if (next == null) {
            next = EvaluationContext.from(bitBoard, state);
        } else {
            next.updateFrom(bitBoard, state);
        }
        this.evaluationContext = next;
        this.spareEvaluationContext = previous;

        if (next == null || !evaluationPipeline.isInitialized()) {
            evaluationPipeline.initialize(next);
            return;
        }

        synchronizePipeline(next);
        MoveContext moveContext = new MoveContext(move, previous, next);
        evaluationPipeline.undoMove(moveContext);
    }

    private void synchronizePipeline(EvaluationContext context) {
        if (!evaluationPipeline.isInitialized()) {
            evaluationPipeline.initialize(context);
        } else {
            evaluationPipeline.updateContext(context);
        }
    }

    public double getScoreDifference() {
        if (!evaluationPipeline.isInitialized()) {
            return 0.0;
        }
        return evaluationPipeline.getScoreDifference();
    }

    public int getMidgameScore() {
        if (!evaluationPipeline.isInitialized()) {
            return 0;
        }
        return evaluationPipeline.getMidgameScore();
    }

    public int getEndgameScore() {
        if (!evaluationPipeline.isInitialized()) {
            return 0;
        }
        return evaluationPipeline.getEndgameScore();
    }

    public int getBlendedScore() {
        if (!evaluationPipeline.isInitialized()) {
            return 0;
        }
        return evaluationPipeline.getBlendedScore();
    }

    public static int getPieceValue(int pieceTypeBits) {
        if (pieceTypeBits == 6) {
            return 1000;
        }
        int value = resolveParameters().materialValueForPiece(pieceTypeBits);
        if (value == 0) {
            throw new IllegalStateException("Unexpected value: " + pieceTypeBits);
        }
        return value / 100;
    }

    @JsonIgnore
    public EvaluationContext getEvaluationContext() {
        return evaluationContext;
    }

    @JsonIgnore
    public EvaluationPipeline getEvaluationPipeline() {
        return evaluationPipeline;
    }
}
