package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.syzygy.SyzygyMove;
import julius.game.chessengine.syzygy.SyzygyProbeResult;
import julius.game.chessengine.syzygy.SyzygyTablebaseService;
import julius.game.chessengine.syzygy.SyzygyWdl;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.utils.Score;

import java.util.Optional;
import java.util.OptionalInt;

import static julius.game.chessengine.utils.Score.CHECKMATE;

/**
 * Encapsulates all Syzygy tablebase probing and move selection logic that was
 * previously embedded in the monolithic AI class. The prober is stateless aside
 * from its reference to the tablebase service — every method receives the engine
 * state it needs to operate on.
 */
final class TablebaseProber {

    private static final double MATE_SCORE_MARGIN = 2048.0;
    private static final double TB_TIE_EPSILON = 0.01;
    private static final double TABLEBASE_CLAMP = 2000.0 / 100.0;

    private final SyzygyTablebaseService tablebaseService;

    TablebaseProber(SyzygyTablebaseService tablebaseService) {
        this.tablebaseService = tablebaseService;
    }

    boolean isAvailable() {
        return tablebaseService != null;
    }

    // ---- Records ----

    record TablebaseHit(double score, int bestMove, TablebaseResult result) {
    }

    record TablebaseContinuation(int move, double evaluation, TablebaseResult result,
                                 boolean zeroingMove) {
    }

    record TablebaseInfo(int dtz, int dtm, int whiteWdlSign) {
        boolean hasDtz() { return dtz >= 0; }
    }

    // ---- Public API ----

    Optional<TablebaseHit> resolveTablebaseHit(Engine engine, boolean isWhite) {
        TablebaseResult result = engine.getGameState().getLastTablebaseResult().orElse(null);

        if (tablebaseService != null) {
            Optional<SyzygyProbeResult> probe = tablebaseService.probe(engine.getBitBoard());
            if (probe.isPresent()) {
                result = TablebaseResult.from(probe.get());
                engine.getGameState().setLastTablebaseResult(result);
            }
        }

        if (!isExactWdl(result)) {
            return Optional.empty();
        }

        double whitePerspective = clampTablebaseEval(Score.tablebaseToEvaluation(result, engine.whitesTurn(),
                engine.getGameState().getHalfmoveClock()));

        int bestMove = determineTablebaseBestMove(engine, result, isWhite);

        return Optional.of(new TablebaseHit(whitePerspective, bestMove, result));
    }

    Optional<TablebaseResult> resolveExactTablebaseResult(Engine engine) {
        TablebaseResult result = engine.getGameState().getLastTablebaseResult().orElse(null);
        if (tablebaseService != null) {
            Optional<SyzygyProbeResult> probe = tablebaseService.probe(engine.getBitBoard());
            if (probe.isPresent()) {
                result = TablebaseResult.from(probe.get());
                engine.getGameState().setLastTablebaseResult(result);
            }
        }
        if (!isExactWdl(result)) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    void promoteTablebaseMove(IntArrayList moves, Engine engine) {
        if (moves == null || moves.isEmpty()) {
            return;
        }
        TablebaseResult result = engine.getGameState().getLastTablebaseResult().orElse(null);
        if (result == null) {
            return;
        }
        Optional<SyzygyMove> suggestion = result.recommendedMove();
        if (suggestion.isEmpty()) {
            return;
        }
        int matchedMove = findSuggestedMove(moves, suggestion.get());
        if (matchedMove == -1) {
            return;
        }
        int index = moves.indexOf(matchedMove);
        if (index <= 0) {
            return;
        }
        int first = moves.getInt(0);
        moves.set(0, matchedMove);
        moves.set(index, first);
    }

    boolean shouldUseTablebaseTieBreak(double candidateEval, double bestEval) {
        if (!Double.isFinite(candidateEval) || !Double.isFinite(bestEval)) {
            return false;
        }
        if (Math.abs(candidateEval - bestEval) > TB_TIE_EPSILON) {
            return false;
        }
        if (isMateValue(candidateEval) || isMateValue(bestEval)) {
            return false;
        }
        double candidateSign = Math.signum(candidateEval);
        double bestSign = Math.signum(bestEval);
        if (candidateSign == 0.0 || bestSign == 0.0) {
            return false;
        }
        return candidateSign == bestSign;
    }

    TablebaseInfo probeMoveTablebase(Engine engine, int move) {
        if (tablebaseService == null || move < 0) {
            return null;
        }
        engine.performMove(move);
        try {
            TablebaseResult result = engine.getGameState().getLastTablebaseResult().orElse(null);
            if (!isExactWdl(result)) {
                Optional<SyzygyProbeResult> probe = tablebaseService.probe(engine.getBitBoard());
                if (probe.isEmpty()) {
                    return null;
                }
                result = TablebaseResult.from(probe.get());
                if (!isExactWdl(result)) {
                    return null;
                }
                engine.getGameState().setLastTablebaseResult(result);
            }
            int dtz = result.dtz().isPresent() ? Math.abs(result.dtz().getAsInt()) : -1;
            int dtm = result.dtm().isPresent() ? Math.abs(result.dtm().getAsInt()) : -1;
            boolean childIsWhite = engine.whitesTurn();
            int wdlScore = result.wdl().score();
            int whiteWdlSign = childIsWhite ? wdlScore : -wdlScore;
            return new TablebaseInfo(dtz, dtm, whiteWdlSign);
        } finally {
            engine.undoLastMove();
        }
    }

    boolean preferCandidateByTablebase(Engine engine,
                                       int candidateMove,
                                       double candidateEval,
                                       boolean candidateZeroing,
                                       int bestMove,
                                       boolean bestZeroing) {
        TablebaseInfo candidateInfo = probeMoveTablebase(engine, candidateMove);
        TablebaseInfo bestInfo = probeMoveTablebase(engine, bestMove);
        if (candidateInfo == null && bestInfo == null) {
            return false;
        }

        int candidateSign = candidateInfo != null ? Integer.signum(candidateInfo.whiteWdlSign()) : 0;
        int bestSign = bestInfo != null ? Integer.signum(bestInfo.whiteWdlSign()) : 0;

        if (candidateSign > 0 && bestSign > 0) {
            int candidateDtz = candidateInfo.hasDtz() ? candidateInfo.dtz() : Integer.MAX_VALUE;
            int bestDtz = bestInfo.hasDtz() ? bestInfo.dtz() : Integer.MAX_VALUE;
            if (candidateDtz < bestDtz) {
                return true;
            }
            if (candidateDtz > bestDtz) {
                return false;
            }
            if (candidateZeroing != bestZeroing) {
                return candidateZeroing;
            }
            return false;
        }

        if (candidateSign < 0 && bestSign < 0) {
            int candidateDtz = candidateInfo.hasDtz() ? candidateInfo.dtz() : -1;
            int bestDtz = bestInfo.hasDtz() ? bestInfo.dtz() : -1;
            if (candidateDtz > bestDtz) {
                return true;
            }
            if (candidateDtz < bestDtz) {
                return false;
            }
            if (candidateZeroing != bestZeroing) {
                return !candidateZeroing;
            }
            return false;
        }

        if (candidateInfo != null && bestInfo == null) {
            if (candidateSign > 0 && candidateInfo.hasDtz()) {
                return true;
            }
            if (candidateSign > 0 && candidateZeroing != bestZeroing) {
                return candidateZeroing;
            }
            return false;
        }

        if (candidateInfo == null) {
            if (bestSign > 0 && bestInfo.hasDtz()) {
                return false;
            }
            if (bestSign < 0 && bestInfo.hasDtz()) {
                return false;
            }
            if (bestSign > 0 && candidateZeroing != bestZeroing) {
                return candidateZeroing;
            }
            if (bestSign < 0 && candidateZeroing != bestZeroing) {
                return !candidateZeroing;
            }
        }

        return false;
    }

    // ---- Internal helpers ----

    private int determineTablebaseBestMove(Engine simulatorEngine, TablebaseResult parentResult, boolean parentIsWhite) {
        IntArrayList legal = simulatorEngine.getAllLegalMoves();
        if (legal.isEmpty()) {
            return -1;
        }

        int suggestedMove = -1;
        TablebaseContinuation bestContinuation = null;

        if (parentResult != null) {
            Optional<SyzygyMove> suggestion = parentResult.recommendedMove();
            if (suggestion.isPresent()) {
                suggestedMove = findSuggestedMove(legal, suggestion.get());
                if (suggestedMove != -1) {
                    Optional<TablebaseContinuation> continuation = evaluateTablebaseContinuation(simulatorEngine, suggestedMove);
                    if (continuation.isPresent()) {
                        TablebaseContinuation candidate = continuation.get();
                        if (isContinuationConsistent(parentResult, candidate)) {
                            return candidate.move();
                        }
                        bestContinuation = candidate;
                    }
                }
            }
        }

        for (int i = 0; i < legal.size(); i++) {
            int move = legal.getInt(i);
            if (move == suggestedMove) {
                continue;
            }
            Optional<TablebaseContinuation> continuation = evaluateTablebaseContinuation(simulatorEngine, move);
            if (continuation.isEmpty()) {
                continue;
            }
            TablebaseContinuation candidate = continuation.get();
            if (bestContinuation == null
                    || isContinuationBetter(parentIsWhite, candidate, bestContinuation, parentResult)) {
                bestContinuation = candidate;
            }
        }
        return bestContinuation != null ? bestContinuation.move() : -1;
    }

    private Optional<TablebaseContinuation> evaluateTablebaseContinuation(Engine simulatorEngine, int move) {
        boolean zeroing = MoveHelper.isCapture(move) || MoveHelper.derivePieceTypeBits(move) == 1;
        simulatorEngine.performMove(move);
        try {
            Optional<TablebaseResult> childResult = resolveExactTablebaseResult(simulatorEngine);
            if (childResult.isEmpty()) {
                return Optional.empty();
            }
            TablebaseResult result = childResult.get();
            double evaluation = clampTablebaseEval(Score.tablebaseToEvaluation(result, simulatorEngine.whitesTurn(),
                    simulatorEngine.getGameState().getHalfmoveClock()));
            return Optional.of(new TablebaseContinuation(move, evaluation, result, zeroing));
        } finally {
            simulatorEngine.undoLastMove();
        }
    }

    private boolean isContinuationConsistent(TablebaseResult parentResult, TablebaseContinuation continuation) {
        if (parentResult == null || continuation == null) {
            return false;
        }
        int parentSign = Integer.signum(parentResult.wdl().score());
        int childSign = Integer.signum(continuation.result().wdl().score());
        if (parentSign == 0) {
            return childSign == 0;
        }
        return parentSign == -childSign;
    }

    private boolean isContinuationBetter(boolean parentIsWhite,
                                         TablebaseContinuation candidate,
                                         TablebaseContinuation incumbent,
                                         TablebaseResult parentResult) {
        if (incumbent == null) {
            return true;
        }
        if (candidate == null) {
            return false;
        }

        double candidateEval = candidate.evaluation();
        double incumbentEval = incumbent.evaluation();
        if (!Double.isFinite(candidateEval)) {
            return false;
        }
        if (!Double.isFinite(incumbentEval)) {
            return true;
        }

        double diff = candidateEval - incumbentEval;
        if (Math.abs(diff) > TB_TIE_EPSILON) {
            return parentIsWhite ? diff > 0 : diff < 0;
        }

        int outcome = parentResult != null ? Integer.signum(parentResult.wdl().score()) : 0;
        return switch (outcome) {
            case 1 -> preferWinningContinuation(candidate, incumbent);
            case -1 -> preferDefensiveContinuation(candidate, incumbent);
            case 0 -> preferDrawingContinuation(candidate, incumbent);
            default -> false;
        };
    }

    private boolean preferWinningContinuation(TablebaseContinuation candidate, TablebaseContinuation incumbent) {
        int candidateDtz = normaliseDistance(candidate.result().dtz(), Integer.MAX_VALUE);
        int incumbentDtz = normaliseDistance(incumbent.result().dtz(), Integer.MAX_VALUE);
        if (candidateDtz != incumbentDtz) {
            return candidateDtz < incumbentDtz;
        }

        int candidateDtm = normaliseDistance(candidate.result().dtm(), Integer.MAX_VALUE);
        int incumbentDtm = normaliseDistance(incumbent.result().dtm(), Integer.MAX_VALUE);
        if (candidateDtm != incumbentDtm) {
            return candidateDtm < incumbentDtm;
        }

        if (candidate.zeroingMove() != incumbent.zeroingMove()) {
            return candidate.zeroingMove();
        }
        return false;
    }

    private boolean preferDefensiveContinuation(TablebaseContinuation candidate, TablebaseContinuation incumbent) {
        int candidateDtz = normaliseDistance(candidate.result().dtz(), Integer.MIN_VALUE);
        int incumbentDtz = normaliseDistance(incumbent.result().dtz(), Integer.MIN_VALUE);
        if (candidateDtz != incumbentDtz) {
            return candidateDtz > incumbentDtz;
        }

        int candidateDtm = normaliseDistance(candidate.result().dtm(), Integer.MIN_VALUE);
        int incumbentDtm = normaliseDistance(incumbent.result().dtm(), Integer.MIN_VALUE);
        if (candidateDtm != incumbentDtm) {
            return candidateDtm > incumbentDtm;
        }

        if (candidate.zeroingMove() != incumbent.zeroingMove()) {
            return !candidate.zeroingMove();
        }
        return false;
    }

    private boolean preferDrawingContinuation(TablebaseContinuation candidate, TablebaseContinuation incumbent) {
        int candidateDtz = normaliseDistance(candidate.result().dtz(), Integer.MAX_VALUE);
        int incumbentDtz = normaliseDistance(incumbent.result().dtz(), Integer.MAX_VALUE);
        if (candidateDtz != incumbentDtz) {
            return candidateDtz < incumbentDtz;
        }
        if (candidate.zeroingMove() != incumbent.zeroingMove()) {
            return candidate.zeroingMove();
        }
        int candidateDtm = normaliseDistance(candidate.result().dtm(), Integer.MAX_VALUE);
        int incumbentDtm = normaliseDistance(incumbent.result().dtm(), Integer.MAX_VALUE);
        if (candidateDtm != incumbentDtm) {
            return candidateDtm < incumbentDtm;
        }
        return false;
    }

    private int normaliseDistance(OptionalInt value, int fallback) {
        if (value.isEmpty()) {
            return fallback;
        }
        return Math.abs(value.getAsInt());
    }

    int findSuggestedMove(IntArrayList legal, SyzygyMove suggestion) {
        int fromIndex = suggestion.fromIndex();
        int toIndex = suggestion.toIndex();
        int promotionBits = suggestion.promotionPieceTypeBits();
        for (int i = 0; i < legal.size(); i++) {
            int move = legal.getInt(i);
            if (MoveHelper.deriveFromIndex(move) != fromIndex) {
                continue;
            }
            if (MoveHelper.deriveToIndex(move) != toIndex) {
                continue;
            }
            int movePromotion = MoveHelper.derivePromotionPieceTypeBits(move);
            if (promotionBits == 0) {
                if (movePromotion != 0) {
                    continue;
                }
            } else if (movePromotion != promotionBits) {
                continue;
            }

            return move;
        }
        return -1;
    }

    boolean isExactWdl(TablebaseResult result) {
        if (result == null) {
            return false;
        }
        SyzygyWdl wdl = result.wdl();
        return wdl == SyzygyWdl.WIN || wdl == SyzygyWdl.LOSS || wdl == SyzygyWdl.DRAW;
    }

    double clampTablebaseEval(double eval) {
        if (!Double.isFinite(eval)) {
            return eval;
        }
        double mateThreshold = (CHECKMATE - MATE_SCORE_MARGIN) / 100.0;
        if (Math.abs(eval) >= mateThreshold) {
            return eval;
        }
        if (eval > TABLEBASE_CLAMP) {
            return TABLEBASE_CLAMP;
        }
        return Math.max(eval, -TABLEBASE_CLAMP);
    }

    private boolean isMateValue(double score) {
        return Math.abs(score * 100.0) >= (CHECKMATE - MATE_SCORE_MARGIN);
    }
}
