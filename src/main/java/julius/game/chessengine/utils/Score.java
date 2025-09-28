package julius.game.chessengine.utils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.evaluation.ActivityModule;
import julius.game.chessengine.evaluation.EvaluationContext;
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
    private static volatile ScoreFactory GLOBAL_FACTORY = bitBoard -> new Score(bitBoard, EvaluationWeights.identity());

    public static final int CHECKMATE = 100000;
    public static final int CHECK = 50;
    public static final int DRAW = 0;
    public static final int KILLER_MOVE_SCORE = 10000;

    private final MaterialModule materialModule = new MaterialModule();
    private final PawnStructureModule pawnStructureModule = new PawnStructureModule();
    private final PieceSquareModule pieceSquareModule = new PieceSquareModule();
    private final ActivityModule activityModule = new ActivityModule();
    private final KingSafetyModule kingSafetyModule = new KingSafetyModule();
    private final ThreatModule threatModule = new ThreatModule();

    @JsonIgnore
    private final EvaluationWeights weights;
    private final EvaluationPipeline evaluationPipeline;
    @JsonIgnore
    private EvaluationContext evaluationContext;
    @JsonIgnore
    private EvaluationContext spareEvaluationContext;

    public Score() {
        this(EvaluationWeights.identity());
    }

    public Score(EvaluationWeights weights) {
        this.weights = weights != null ? weights : EvaluationWeights.identity();
        materialModule.setPawnChangeListener(pawnStructureModule);
        this.evaluationPipeline = new EvaluationPipeline(List.of(
                materialModule,
                pawnStructureModule,
                pieceSquareModule,
                activityModule,
                kingSafetyModule,
                threatModule
        ), this.weights);
    }

    public Score(BitBoard bitBoard, EvaluationWeights weights) {
        this(weights);
        initializeFrom(bitBoard);
    }

    public Score(Score other) {
        this(other != null ? other.weights : null);
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
        return useFactory(forEvaluationWeights(weights));
    }

    public static ScoreFactory forEvaluationWeights(EvaluationWeights weights) {
        EvaluationWeights resolved = (weights != null ? weights : EvaluationWeights.identity());
        return bitBoard -> new Score(bitBoard, resolved);
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
        return switch (pieceTypeBits) {
            case 1 -> MaterialModule.PAWN_VALUE / 100;
            case 2 -> MaterialModule.KNIGHT_VALUE / 100;
            case 3 -> MaterialModule.BISHOP_VALUE / 100;
            case 4 -> MaterialModule.ROOK_VALUE / 100;
            case 5 -> MaterialModule.QUEEN_VALUE / 100;
            case 6 -> 1000;
            default -> throw new IllegalStateException("Unexpected value: " + pieceTypeBits);
        };
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
