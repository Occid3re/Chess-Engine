package julius.game.chessengine.ai;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.evaluation.EvaluationContext;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.utils.Score;
import lombok.extern.log4j.Log4j2;

import java.util.Optional;
import java.util.function.LongPredicate;

import static julius.game.chessengine.utils.Score.*;

/**
 * Encapsulates quiescence search, static evaluation, and the per-thread
 * evaluation cache. Extracted from AI to reduce the main search class and
 * provide a focused abstraction for tactical horizon evaluation.
 */
@Log4j2
final class QuiescenceSearcher {

    private static final double MATE_SCORE_MARGIN = 2048.0;
    private static final double TABLEBASE_CLAMP = 2000.0 / 100.0;
    static final double EXIT_FLAG = AI.EXIT_FLAG;

    private final double drawBias;
    private final double quiescenceMaxDeltaPawn;
    private final ThreadLocal<Long2DoubleOpenHashMap> staticEvalCache;
    private final LongPredicate abortChecker;
    private final TablebaseProber tablebaseProber;

    // Dependencies set after construction (avoids circular init with AI)
    private MoveOrderer moveOrderer;
    private ThreadLocal<Heuristics> threadHeuristics;
    private TranspositionTable<TranspositionTableEntry> transpositionTable;
    private TranspositionTable<CaptureTranspositionTableEntry> captureTranspositionTable;

    QuiescenceSearcher(double drawBias,
                       double quiescenceMaxDeltaPawn,
                       TablebaseProber tablebaseProber,
                       LongPredicate abortChecker) {
        this.drawBias = drawBias;
        this.quiescenceMaxDeltaPawn = quiescenceMaxDeltaPawn;
        this.tablebaseProber = tablebaseProber;
        this.abortChecker = abortChecker;
        this.staticEvalCache = ThreadLocal.withInitial(() -> {
            Long2DoubleOpenHashMap map = new Long2DoubleOpenHashMap(512);
            map.defaultReturnValue(Double.NaN);
            return map;
        });
    }

    void setDependencies(MoveOrderer moveOrderer,
                         ThreadLocal<Heuristics> threadHeuristics,
                         TranspositionTable<TranspositionTableEntry> transpositionTable,
                         TranspositionTable<CaptureTranspositionTableEntry> captureTranspositionTable) {
        this.moveOrderer = moveOrderer;
        this.threadHeuristics = threadHeuristics;
        this.transpositionTable = transpositionTable;
        this.captureTranspositionTable = captureTranspositionTable;
    }

    void updateTranspositionTables(TranspositionTable<TranspositionTableEntry> main,
                                   TranspositionTable<CaptureTranspositionTableEntry> capture) {
        this.transpositionTable = main;
        this.captureTranspositionTable = capture;
    }

    void clearCache() {
        staticEvalCache.get().clear();
    }

    // ---- Main entry points ----

    double evaluateBoard(Engine simulatorEngine, boolean isWhitesTurn, long deadline) {
        if (simulatorEngine.getGameState().isInStateCheckMate()) {
            return -CHECKMATE;
        }

        long boardStateHash = simulatorEngine.getBoardStateHash();

        if (simulatorEngine.getGameState().isDrawForUIOrEval()) {
            double scoreDiff = resolveScoreDifference(simulatorEngine.getGameState(), boardStateHash,
                    simulatorEngine.whitesTurn());
            if ((isWhitesTurn && scoreDiff > 0) || (!isWhitesTurn && scoreDiff < 0)) {
                return DRAW - drawBias;
            } else if ((isWhitesTurn && scoreDiff < 0) || (!isWhitesTurn && scoreDiff > 0)) {
                return DRAW + drawBias;
            }
            return DRAW;
        }

        double alpha = Double.NEGATIVE_INFINITY;
        double beta = Double.POSITIVE_INFINITY;

        CaptureTranspositionTableEntry entry = captureTranspositionTable.get(boardStateHash);
        if (entry != null && entry.isWhite() == isWhitesTurn) {
            return entry.getScore();
        }

        if (!isSideInCheck(simulatorEngine, isWhitesTurn)
                && !simulatorEngine.hasAnyCaptureOrPromotion()) {
            if (abortChecker.test(deadline)) return EXIT_FLAG;
            double quietScore = evaluateStaticPosition(
                    simulatorEngine.getGameState(), boardStateHash, isWhitesTurn, 0
            );
            captureTranspositionTable.put(
                    boardStateHash, new CaptureTranspositionTableEntry(quietScore, isWhitesTurn), 0
            );
            return quietScore;
        }

        double score = quiescenceSearch(simulatorEngine, isWhitesTurn, alpha, beta, deadline, 0);
        if (score != EXIT_FLAG) {
            captureTranspositionTable.put(boardStateHash, new CaptureTranspositionTableEntry(score, isWhitesTurn), 0);
        }
        return score;
    }

    double quiescenceSearch(Engine simulatorEngine,
                            boolean isWhitesTurn,
                            double alpha,
                            double beta,
                            long deadline,
                            int depth) {
        if (abortChecker.test(deadline)) {
            return EXIT_FLAG;
        }

        GameState gameState = simulatorEngine.getGameState();
        Optional<TablebaseResult> tablebase = gameState.getLastTablebaseResult();
        if (tablebase.isPresent() && tablebaseProber.isExactWdl(tablebase.get())) {
            long boardHash = simulatorEngine.getBoardStateHash();
            return evaluateStaticPosition(gameState, boardHash, isWhitesTurn, depth);
        }

        if (gameState.isTerminal()) {
            long boardHash = simulatorEngine.getBoardStateHash();
            return evaluateStaticPosition(gameState, boardHash, isWhitesTurn, depth);
        }

        final boolean inCheck = isSideInCheck(simulatorEngine, isWhitesTurn);

        long boardHash = simulatorEngine.getBoardStateHash();
        double standPat = evaluateStaticPosition(gameState, boardHash, isWhitesTurn, depth);

        double alphaBeforeStandPat = alpha;

        if (!inCheck) {
            if (standPat >= beta) {
                return beta;
            }
            if (standPat > alpha) {
                alpha = standPat;
            }
        }

        IntArrayList moves = inCheck
                ? simulatorEngine.getAllLegalMoves()
                : MoveContainerUtils.filterCapturesAndPromotions(simulatorEngine.getAllLegalMoves());

        if (!inCheck) {
            if (moves.isEmpty()) {
                return alpha;
            }
            double optimisticSwing = estimateMaxTacticalSwing(moves);
            if (standPat + optimisticSwing <= alphaBeforeStandPat) {
                return alpha;
            }
        }

        IntArrayList ordered = moveOrderer.sortMovesByEfficiency(
                moves, 0, simulatorEngine.getBoardStateHash(), -1, simulatorEngine,
                threadHeuristics.get(), transpositionTable
        );

        for (int i = 0; i < ordered.size(); i++) {
            int m = ordered.getInt(i);

            boolean isCapture = MoveHelper.isCapture(m);
            boolean isPromotion = MoveHelper.isPawnPromotionMove(m);
            boolean isQuiet = !isCapture && !isPromotion;

            if (!inCheck && isQuiet) {
                continue;
            }

            if (!inCheck && isCapture && !isPromotion) {
                int see = simulatorEngine.see(m, 0);
                if (see < 0) {
                    if (!simulatorEngine.getGameState().isTerminal()) {
                        simulatorEngine.performMove(m);
                        boolean givesCheck = isSideInCheck(simulatorEngine, !isWhitesTurn);
                        simulatorEngine.undoLastMove();
                        if (!givesCheck) {
                            continue;
                        }
                    } else {
                        continue;
                    }
                }
            }

            if (simulatorEngine.getGameState().isTerminal()) {
                return standPat;
            }

            simulatorEngine.performMove(m);
            Optional<TablebaseResult> childTablebase = tablebaseProber.resolveExactTablebaseResult(simulatorEngine);
            double child;
            if (childTablebase.isPresent()) {
                long childHash = simulatorEngine.getBoardStateHash();
                child = evaluateStaticPosition(simulatorEngine.getGameState(), childHash, !isWhitesTurn, depth + 1);
            } else {
                child = quiescenceSearch(simulatorEngine, !isWhitesTurn, -beta, -alpha, deadline, depth + 1);
            }
            simulatorEngine.undoLastMove();

            if (child == EXIT_FLAG) {
                return EXIT_FLAG;
            }

            double score = -child;
            score = adjustMateFromChild(score);

            if (score >= beta) {
                return beta;
            }
            if (score > alpha) {
                alpha = score;
            }
        }

        return alpha;
    }

    // ---- Static evaluation ----

    double evaluateStaticPosition(GameState gameState, long boardHash, boolean isWhitesTurn, int depthOrPly) {
        if (gameState.isInStateCheckMate()) {
            return -(CHECKMATE - depthOrPly);
        }
        EvaluationContext context = gameState.getScore().getEvaluationContext();
        boolean whiteToMove = context != null && context.isWhiteToMove();
        Optional<TablebaseResult> tablebase = gameState.getLastTablebaseResult();
        if (tablebase.isPresent() && tablebaseProber.isExactWdl(tablebase.get())) {
            double whitePerspective = tablebaseProber.clampTablebaseEval(
                    Score.tablebaseToEvaluation(tablebase.get(), whiteToMove, gameState.getHalfmoveClock()));
            return isWhitesTurn ? whitePerspective : -whitePerspective;
        }
        if (gameState.isDrawForUIOrEval()) {
            if (log.isDebugEnabled()) log.debug("DRAW");
            double scoreDiff = resolveScoreDifference(gameState, boardHash, whiteToMove);
            if ((isWhitesTurn && scoreDiff > 0) || (!isWhitesTurn && scoreDiff < 0)) {
                return DRAW - drawBias;
            } else if ((isWhitesTurn && scoreDiff < 0) || (!isWhitesTurn && scoreDiff > 0)) {
                return DRAW + drawBias;
            }
            return DRAW;
        }
        double scoreDifference = resolveScoreDifference(gameState, boardHash, whiteToMove);
        return isWhitesTurn ? scoreDifference : -scoreDifference;
    }

    double resolveScoreDifference(GameState gameState, long boardHash, boolean whiteToMove) {
        Long2DoubleOpenHashMap cache = staticEvalCache.get();
        Optional<TablebaseResult> tablebase = gameState.getLastTablebaseResult();
        if (tablebase.isPresent()) {
            int halfmoveClock = gameState.getHalfmoveClock();
            long cacheKey = mixBoardHashWithHalfmove(boardHash, halfmoveClock);
            double cached = cache.get(cacheKey);
            if (!Double.isNaN(cached)) {
                return cached;
            }
            TablebaseResult result = tablebase.get();
            double evaluation = tablebaseProber.isExactWdl(result)
                    ? tablebaseProber.clampTablebaseEval(Score.tablebaseToEvaluation(result, whiteToMove, halfmoveClock))
                    : gameState.getScore().getScoreDifference();
            cache.put(cacheKey, evaluation);
            return evaluation;
        }
        double cached = cache.get(boardHash);
        if (!Double.isNaN(cached)) {
            return cached;
        }
        double computed = gameState.getScore().getScoreDifference();
        cache.put(boardHash, computed);
        return computed;
    }

    static long mixBoardHashWithHalfmove(long boardHash, int halfmoveClock) {
        long clock = Integer.toUnsignedLong(halfmoveClock);
        long rotated = Long.rotateLeft(clock, 32);
        return boardHash ^ rotated ^ (clock << 1);
    }

    // ---- Tactical helpers ----

    double estimateMaxTacticalSwing(IntArrayList moves) {
        double bestSwing = 0.0;
        final double pawnValue = Score.getPieceValue(1);

        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getInt(i);
            double swing = 0.0;

            if (MoveHelper.isCapture(move)) {
                int capturedBits = MoveHelper.deriveCapturedPieceTypeBits(move);
                double captureValue;
                if (capturedBits != 0) {
                    captureValue = Score.getPieceValue(capturedBits);
                } else if (MoveHelper.isEnPassantMove(move)) {
                    captureValue = pawnValue;
                } else {
                    captureValue = 0.0;
                }
                swing += captureValue;
            }

            if (MoveHelper.isPawnPromotionMove(move)) {
                int promotionBits = MoveHelper.derivePromotionPieceTypeBits(move);
                if (promotionBits != 0) {
                    double promotionDelta = Score.getPieceValue(promotionBits) - pawnValue;
                    if (promotionDelta > 0) {
                        swing += promotionDelta;
                    }
                }
            }

            if (swing > bestSwing) {
                bestSwing = swing;
                if (bestSwing >= quiescenceMaxDeltaPawn) {
                    return quiescenceMaxDeltaPawn;
                }
            }
        }

        return Math.min(bestSwing, quiescenceMaxDeltaPawn);
    }

    // ---- Board query helpers ----

    static boolean isSideInCheck(Engine engine, boolean isWhite) {
        julius.game.chessengine.engine.GameStateEnum state = engine.getGameState().getState();
        return (isWhite && state == julius.game.chessengine.engine.GameStateEnum.WHITE_IN_CHECK)
                || (!isWhite && state == julius.game.chessengine.engine.GameStateEnum.BLACK_IN_CHECK);
    }

    // ---- Score helpers ----

    static boolean isMateValue(double score) {
        return Double.isFinite(score) && Math.abs(score) >= (CHECKMATE - MATE_SCORE_MARGIN);
    }

    static double adjustMateFromChild(double score) {
        if (!Double.isFinite(score)) {
            return score;
        }
        double abs = Math.abs(score);
        if (abs >= (CHECKMATE - MATE_SCORE_MARGIN)) {
            return score > 0 ? score - 1 : score + 1;
        }
        return score;
    }

    static double toStoredMateScore(double score, int plyFromRoot) {
        if (!Double.isFinite(score)) {
            return score;
        }
        double abs = Math.abs(score);
        if (abs >= (CHECKMATE - MATE_SCORE_MARGIN)) {
            return score > 0 ? score + plyFromRoot : score - plyFromRoot;
        }
        return score;
    }

    static double fromStoredMateScore(double score, int plyFromRoot) {
        if (!Double.isFinite(score)) {
            return score;
        }
        double abs = Math.abs(score);
        if (abs >= (CHECKMATE - MATE_SCORE_MARGIN)) {
            return score > 0 ? score - plyFromRoot : score + plyFromRoot;
        }
        return score;
    }
}
