package julius.game.chessengine.utils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.evaluation.ActivityModule;
import julius.game.chessengine.evaluation.BatteryModule;
import julius.game.chessengine.evaluation.EvaluationContext;
import julius.game.chessengine.evaluation.EvaluationPipeline;
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

    public static final int CHECKMATE = 100000;
    public static final int CHECK = 50;
    public static final int DRAW = 0;
    public static final int KILLER_MOVE_SCORE = 10000;

    @JsonIgnore
    private final EvaluationPipeline evaluationPipeline;
    @JsonIgnore
    private EvaluationContext evaluationContext;

    public Score() {
        MaterialModule materialModule = new MaterialModule();
        PawnStructureModule pawnStructureModule = new PawnStructureModule();
        materialModule.setPawnChangeListener(pawnStructureModule);
        PieceSquareModule pieceSquareModule = new PieceSquareModule();
        ActivityModule activityModule = new ActivityModule();
        BatteryModule batteryModule = new BatteryModule();
        KingSafetyModule kingSafetyModule = new KingSafetyModule();
        ThreatModule threatModule = new ThreatModule();
        this.evaluationPipeline = new EvaluationPipeline(List.of(
                materialModule,
                pawnStructureModule,
                pieceSquareModule,
                activityModule,
                batteryModule,
                kingSafetyModule,
                threatModule
        ));
    }

    public Score(Score other) {
        this();
        if (other != null && other.evaluationContext != null) {
            this.evaluationContext = other.evaluationContext.copy();
            if (other.evaluationPipeline.isInitialized()) {
                evaluationPipeline.initialize(this.evaluationContext);
            }
        }
    }

    public static Score initializeScore(BitBoard bitBoard) {
        Score score = new Score();
        score.initializeFrom(bitBoard);
        return score;
    }

    private void initializeFrom(BitBoard bitBoard) {
        Objects.requireNonNull(bitBoard, "bitBoard");
        EvaluationContext context = EvaluationContext.from(bitBoard, null);
        this.evaluationContext = context;
        evaluationPipeline.initialize(context);
    }

    public void refresh(BitBoard bitBoard, GameStateEnum state) {
        Objects.requireNonNull(bitBoard, "bitBoard");
        EvaluationContext updated = EvaluationContext.from(bitBoard, state);
        this.evaluationContext = updated;

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
        EvaluationContext updated = EvaluationContext.from(bitBoard, state);
        this.evaluationContext = updated;

        if (previous == null || !evaluationPipeline.isInitialized()) {
            evaluationPipeline.initialize(updated);
            return;
        }

        synchronizePipeline(updated);
        MoveContext moveContext = new MoveContext(move, previous, updated);
        evaluationPipeline.applyMove(moveContext);
    }

    public void undoMove(BitBoard bitBoard, int move, GameStateEnum state) {
        Objects.requireNonNull(bitBoard, "bitBoard");
        EvaluationContext previous = this.evaluationContext;
        EvaluationContext updated = EvaluationContext.from(bitBoard, state);
        this.evaluationContext = updated;

        if (previous == null || !evaluationPipeline.isInitialized()) {
            evaluationPipeline.initialize(updated);
            return;
        }

        synchronizePipeline(updated);
        MoveContext moveContext = new MoveContext(move, previous, updated);
        evaluationPipeline.undoMove(moveContext);
    }

    private void synchronizePipeline(EvaluationContext context) {
        if (!evaluationPipeline.isInitialized()) {
            evaluationPipeline.initialize(context);
        } else {
            evaluationPipeline.setContextWithoutInvalidation(context);
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

}
