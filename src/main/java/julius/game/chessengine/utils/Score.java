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
import julius.game.chessengine.evaluation.ThreatModule;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.tuning.EngineTuningBootstrap;
import julius.game.chessengine.tuning.MoveOrderingParameters;
import julius.game.chessengine.tuning.NumericTuningParameters;
import julius.game.chessengine.tuning.Tuning;

import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Central entry point for the evaluation pipeline.  The legacy score bookkeeping previously
 * maintained in this class has been replaced by incremental evaluation modules coordinated by the
 * {@link EvaluationPipeline}.  The public API now exposes only tapered totals that originate from
 * the pipeline itself.
 */
public class Score {

    private static final ThreadLocal<ScoreFactory> THREAD_FACTORY = new ThreadLocal<>();
    private static volatile ScoreFactory GLOBAL_FACTORY = bitBoard -> new Score(bitBoard, EvaluationWeights.identity());
    private static volatile SyzygyTablebaseService TABLEBASE_SERVICE;

    private static final int DEFAULT_TABLEBASE_PIECE_LIMIT = 6;
    private static volatile int tablebasePieceLimit = DEFAULT_TABLEBASE_PIECE_LIMIT;

    static {
        EngineTuningBootstrap.ensureDefaultTuning();
    }

    public static final int CHECKMATE = 100000;
    public static final int CHECK = 50;
    public static final int DRAW = 0;

    @JsonIgnore
    private final EvaluationWeights weights;
    private final EvaluationPipeline evaluationPipeline;
    @JsonIgnore
    private EvaluationContext evaluationContext;
    @JsonIgnore
    private EvaluationContext spareEvaluationContext;
    @JsonIgnore
    private TablebaseResult tablebaseResult;
    @JsonIgnore
    private Integer tablebaseCentipawn;
    @JsonIgnore
    private boolean tablebaseBypassesEvaluation;

    public Score() {
        this(EvaluationWeights.identity());
    }

    public Score(EvaluationWeights weights) {
        this.weights = weights != null ? weights : EvaluationWeights.identity();

        MaterialModule materialModule = new MaterialModule();
        PawnStructureModule pawnStructureModule = new PawnStructureModule();
        ActivityModule activityModule = new ActivityModule();
        KingSafetyModule kingSafetyModule = new KingSafetyModule();
        ThreatModule threatModule = new ThreatModule();

        materialModule.setPawnChangeListener(pawnStructureModule);
        this.evaluationPipeline = new EvaluationPipeline(List.of(
                materialModule,
                pawnStructureModule,
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
            this.tablebaseResult = other.tablebaseResult;
            this.tablebaseCentipawn = other.tablebaseCentipawn;
            this.tablebaseBypassesEvaluation = other.tablebaseBypassesEvaluation;
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

    public static AutoCloseable useNumericParameters(Map<String, Double> parameters) {
        return NumericTuningParameters.use(parameters);
    }

    public static int killerMoveScore() {
        return MoveOrderingParameters.killerMoveScore();
    }

    public static ScoreFactory forEvaluationWeights(EvaluationWeights weights) {
        EvaluationWeights resolved = (weights != null ? weights : EvaluationWeights.identity());
        return bitBoard -> new Score(bitBoard, resolved);
    }

    public static void setGlobalFactory(ScoreFactory factory) {
        GLOBAL_FACTORY = Objects.requireNonNull(factory, "factory");
    }

    public static synchronized void setTablebaseService(SyzygyTablebaseService service) {
        Objects.requireNonNull(service, "service");
        TABLEBASE_SERVICE = service;
        tablebasePieceLimit = normalizePieceLimit(service.getEffectiveMaxPieces());
    }

    public static synchronized void clearTablebaseService() {
        TABLEBASE_SERVICE = null;
        tablebasePieceLimit = DEFAULT_TABLEBASE_PIECE_LIMIT;
    }

    public static synchronized SyzygyTablebaseService getTablebaseService() {
        return TABLEBASE_SERVICE;
    }

    private static int normalizePieceLimit(int limit) {
        return limit > 0 ? limit : DEFAULT_TABLEBASE_PIECE_LIMIT;
    }

    public void refresh(BitBoard bitBoard, GameStateEnum state) {
        Objects.requireNonNull(bitBoard, "bitBoard");
        if (evaluationContext == null) {
            evaluationContext = EvaluationContext.from(bitBoard, state);
        } else {
            evaluationContext.updateFrom(bitBoard, state);
        }
        EvaluationContext updated = evaluationContext;

        boolean bypass = updateTablebaseState(bitBoard, updated);

        if (!evaluationPipeline.isInitialized()) {
            evaluationPipeline.initialize(updated);
        } else {
            evaluationPipeline.updateContext(updated);
        }

        // Prime the aggregate totals so subsequent lookups observe the refreshed context immediately.
        if (!bypass) {
            evaluationPipeline.getBlendedScore();
        }
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

        boolean bypass = updateTablebaseState(bitBoard, next);

        if (next == null || !evaluationPipeline.isInitialized()) {
            evaluationPipeline.initialize(next);
            if (!bypass) {
                evaluationPipeline.getBlendedScore();
            }
            return;
        }

        synchronizePipeline(next);
        if (!bypass) {
            MoveContext moveContext = new MoveContext(move, previous, next);
            evaluationPipeline.applyMove(moveContext);
        }
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

        boolean bypass = updateTablebaseState(bitBoard, next);

        if (next == null || !evaluationPipeline.isInitialized()) {
            evaluationPipeline.initialize(next);
            if (!bypass) {
                evaluationPipeline.getBlendedScore();
            }
            return;
        }

        synchronizePipeline(next);
        if (!bypass) {
            MoveContext moveContext = new MoveContext(move, previous, next);
            evaluationPipeline.undoMove(moveContext);
        }
    }

    private void synchronizePipeline(EvaluationContext context) {
        if (!evaluationPipeline.isInitialized()) {
            evaluationPipeline.initialize(context);
        } else {
            evaluationPipeline.updateContext(context);
        }
    }

    public double getScoreDifference() {
        if (tablebaseBypassesEvaluation && tablebaseCentipawn != null) {
            return tablebaseCentipawn / 100.0;
        }
        if (!evaluationPipeline.isInitialized()) {
            return 0.0;
        }
        return evaluationPipeline.getScoreDifference();
    }

    public int getMidgameScore() {
        if (tablebaseBypassesEvaluation && tablebaseCentipawn != null) {
            return tablebaseCentipawn;
        }
        if (!evaluationPipeline.isInitialized()) {
            return 0;
        }
        return evaluationPipeline.getMidgameScore();
    }

    public int getEndgameScore() {
        if (tablebaseBypassesEvaluation && tablebaseCentipawn != null) {
            return tablebaseCentipawn;
        }
        if (!evaluationPipeline.isInitialized()) {
            return 0;
        }
        return evaluationPipeline.getEndgameScore();
    }

    public int getBlendedScore() {
        if (tablebaseBypassesEvaluation && tablebaseCentipawn != null) {
            return tablebaseCentipawn;
        }
        if (!evaluationPipeline.isInitialized()) {
            return 0;
        }
        return evaluationPipeline.getBlendedScore();
    }

    public static int getPieceValue(int pieceTypeBits) {
        return switch (pieceTypeBits) {
            case 1 -> MaterialModule.pawnValue() / 100;
            case 2 -> MaterialModule.knightValue() / 100;
            case 3 -> MaterialModule.bishopValue() / 100;
            case 4 -> MaterialModule.rookValue() / 100;
            case 5 -> MaterialModule.queenValue() / 100;
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

    public Optional<TablebaseResult> getTablebaseResult() {
        return Optional.ofNullable(tablebaseResult);
    }

    public OptionalInt getTablebaseCentipawnScore() {
        return tablebaseCentipawn != null ? OptionalInt.of(tablebaseCentipawn) : OptionalInt.empty();
    }

    private boolean updateTablebaseState(BitBoard bitBoard, EvaluationContext context) {
        SyzygyTablebaseService service = TABLEBASE_SERVICE;
        if (service == null || context == null) {
            clearTablebaseState();
            return false;
        }
        int limit = tablebasePieceLimit;
        long occupancy = context.getAllPieces();
        if (Long.bitCount(occupancy) > limit) {
            clearTablebaseState();
            return false;
        }
        Optional<SyzygyProbeResult> probe = service.probe(bitBoard);
        if (probe.isEmpty()) {
            clearTablebaseState();
            return false;
        }
        TablebaseResult resolved = TablebaseResult.from(probe.get());
        this.tablebaseResult = resolved;
        this.tablebaseCentipawn = computeTablebaseCentipawn(resolved, bitBoard.isWhitesTurn());
        this.tablebaseBypassesEvaluation = true;
        return true;
    }

    private void clearTablebaseState() {
        this.tablebaseResult = null;
        this.tablebaseCentipawn = null;
        this.tablebaseBypassesEvaluation = false;
    }

    public static double tablebaseToEvaluation(TablebaseResult result, boolean whiteToMove) {
        return tablebaseToCentipawn(result, whiteToMove) / 100.0;
    }

    public static int tablebaseToCentipawn(TablebaseResult result, boolean whiteToMove) {
        int wdlScore = result.wdl().score();
        if (wdlScore == 0) {
            return DRAW;
        }
        int sign = Integer.signum(wdlScore);
        if (!whiteToMove) {
            sign = -sign;
        }
        int distance = result.dtm().isPresent()
                ? Math.abs(result.dtm().getAsInt())
                : result.dtz().isPresent() ? Math.abs(result.dtz().getAsInt()) : 0;
        double dtzPenalty = Math.max(1.0, Tuning.searchTbDtzPenalty());
        int scaledPenalty = Math.min(CHECKMATE - 1, (int) Math.round(distance * dtzPenalty));
        return sign * (CHECKMATE - scaledPenalty);
    }

    private int computeTablebaseCentipawn(TablebaseResult result, boolean whiteToMove) {
        return tablebaseToCentipawn(result, whiteToMove);
    }
}
