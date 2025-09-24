package julius.game.chessengine.ai;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.GameStateEnum;

import java.util.Map;

import static julius.game.chessengine.helper.BitHelper.FileMasks;
import static julius.game.chessengine.helper.KingHelper.KING_ATTACKS;
import static julius.game.chessengine.utils.Score.CHECKMATE;
import static julius.game.chessengine.utils.Score.DRAW;

final class AlphaBetaSearch {

    private static final int MAX_CHECK_EXTENSIONS_IN_A_ROW = 2;
    private static final int ABS_PLY_LIMIT_MARGIN = 32;
    private static final int LMR_HISTORY_MAX = 4000;

    private final AI ai;

    AlphaBetaSearch(AI ai) {
        this.ai = ai;
    }

    double alphaBeta(Engine simulatorEngine, int depth, double alpha, double beta,
                     boolean isWhite, long deadline, int prevMove, int plyFromRoot,
                     int extStreak) {
        ai.incrementNodesVisited();
        SearchInstrumentation instr = ai.instrumentation();
        instr.recordVisitedPly(plyFromRoot);

        if (ai.abortRequested(deadline)) return AI.EXIT_FLAG;

        if (plyFromRoot >= ai.getMaxDepth() + ABS_PLY_LIMIT_MARGIN) {
            double eval = ai.evaluateBoard(simulatorEngine, isWhite, deadline);
            if (eval == AI.EXIT_FLAG) return AI.EXIT_FLAG;
            if (!isWhite) eval = -eval;
            return eval;
        }

        boolean inCheck = isSideInCheck(simulatorEngine, isWhite);

        if (simulatorEngine.getGameState().isInStateCheckMate()) {
            double m = CHECKMATE - plyFromRoot;
            return isWhite ? -m : +m;
        }
        if (simulatorEngine.getGameState().isInStateDraw()) {
            double drawScore = ai.evaluateStaticPosition(simulatorEngine.getGameState(), isWhite, plyFromRoot);
            return isWhite ? drawScore : -drawScore;
        }

        if (depth <= 0) {
            double eval = ai.evaluateBoard(simulatorEngine, isWhite, deadline);
            if (eval == AI.EXIT_FLAG) return AI.EXIT_FLAG;
            if (!isWhite) eval = -eval;
            return eval;
        }

        long boardHash = simulatorEngine.getBoardStateHash();

        instr.recordTranspositionLookup();
        TranspositionTableEntry entry = ai.getTranspositionTable().get(boardHash);
        if (entry != null) {
            boolean usable = entry.depth >= depth;
            if (usable) {
                if (entry.nodeType == NodeType.EXACT) {
                    instr.recordTranspositionHit(entry.nodeType, true);
                    return entry.score;
                }
                if (entry.nodeType == NodeType.LOWERBOUND && entry.score > alpha) alpha = entry.score;
                else if (entry.nodeType == NodeType.UPPERBOUND && entry.score < beta) beta = entry.score;
                boolean cutoff = alpha >= beta;
                instr.recordTranspositionHit(entry.nodeType, cutoff);
                if (cutoff) return entry.score;
            } else {
                instr.recordTranspositionHit(entry.nodeType, false);
            }
        }

        MoveList moves = simulatorEngine.getAllLegalMoves();
        int mobility = moves.size();
        BitBoard bitBoard = simulatorEngine.getBitBoard();
        boolean allowNullMove = ai.isNullMovePruningEnabled()
                && !inCheck
                && !simulatorEngine.isEndgame()
                && prevMove != -1;

        if (allowNullMove) {
            double mateThreatScore = CHECKMATE - (plyFromRoot + 1);
            if ((isWhite && beta >= mateThreatScore) || (!isWhite && alpha <= -mateThreatScore)) {
                allowNullMove = false;
            }
        }

        if (allowNullMove) {
            int reduction = computeNullMoveReduction(bitBoard, depth, isWhite, mobility);
            int savedEp = simulatorEngine.doNullMoveForSearch();
            ai.incrementNullMoveCount();
            instr.recordNullMoveAttempt();
            double nullScore = alphaBeta(simulatorEngine, depth - 1 - reduction, alpha, beta, !isWhite, deadline, -1, plyFromRoot + 1, 0);
            simulatorEngine.undoNullMoveForSearch(savedEp);

            if (nullScore == AI.EXIT_FLAG) return AI.EXIT_FLAG;

            boolean nullFailHigh = isWhite ? nullScore >= beta : nullScore <= alpha;
            if (nullFailHigh) {
                double mateThreshold = CHECKMATE - (plyFromRoot + 1);
                double windowEdge = isWhite ? beta : alpha;
                double swingThreshold = Math.max(600, mateThreshold / 64.0);
                double swing = Double.isFinite(windowEdge) ? Math.abs(nullScore - windowEdge) : Math.abs(nullScore);
                boolean requiresVerification = Math.abs(nullScore) >= mateThreshold
                        || swing >= swingThreshold;

                if (requiresVerification) {
                    instr.recordNullMoveVerification();
                    double verificationScore = alphaBeta(simulatorEngine, depth - 1, alpha, beta, isWhite, deadline, prevMove, plyFromRoot, 0);
                    if (verificationScore == AI.EXIT_FLAG) return AI.EXIT_FLAG;
                    if (Math.abs(verificationScore) < mateThreshold) {
                        nullFailHigh = isWhite ? verificationScore >= beta : verificationScore <= alpha;
                    } else {
                        nullFailHigh = false;
                    }
                    if (!nullFailHigh) {
                        instr.recordNullMoveVerificationFail();
                    }
                }
            }

            if (nullFailHigh) {
                instr.recordNullMovePrune();
                return isWhite ? beta : alpha;
            }
        }

        double alphaOriginal = alpha;
        double betaOriginal = beta;

        if (isWhite) {
            return maximizer(simulatorEngine, depth, alpha, beta, boardHash, alphaOriginal, moves, deadline, prevMove, plyFromRoot, extStreak);
        } else {
            return minimizer(simulatorEngine, depth, alpha, beta, boardHash, betaOriginal, moves, deadline, prevMove, plyFromRoot, extStreak);
        }
    }

    double quiescenceSearch(Engine simulatorEngine, boolean isWhitesTurn,
                            double alpha, double beta, long deadline, int depth) {
        if (ai.abortRequested(deadline)) return AI.EXIT_FLAG;

        boolean inCheck = isSideInCheck(simulatorEngine, isWhitesTurn);
        SearchInstrumentation instr = ai.instrumentation();
        instr.recordQuiescenceNode(depth);

        double standPat = ai.evaluateStaticPosition(simulatorEngine.getGameState(), isWhitesTurn, depth);

        if (!inCheck) {
            if (standPat >= beta) {
                instr.recordQuiescenceStandPatCut();
                return beta;
            }
            if (alpha < standPat) alpha = standPat;

            final int BIG_DELTA = 900;
            if (standPat + BIG_DELTA <= alpha) {
                instr.recordQuiescenceDeltaPrune();
                return alpha;
            }
        }

        MoveList moves = inCheck ? simulatorEngine.getAllLegalMoves()
                : getPossibleCapturesOrPromotions(simulatorEngine);

        MoveList ordered = ai.sortMovesByEfficiency(moves, 0,
                simulatorEngine.getBoardStateHash(), -1, simulatorEngine);

        for (int i = 0; i < ordered.size(); i++) {
            int m = ordered.getMove(i);
            boolean isCapture = MoveHelper.isCapture(m);
            boolean isPromotion = MoveHelper.isPawnPromotionMove(m);
            boolean isQuiet = !isCapture && !isPromotion;

            if (!inCheck && isQuiet) continue;
            if (!inCheck && isPromotion && !isCapture) continue;

            if (!inCheck && (!isPromotion || isQuiet)) {
                int see = simulatorEngine.see(m);
                if (see < 0) {
                    simulatorEngine.performMove(m);
                    boolean givesCheck = isSideInCheck(simulatorEngine, !isWhitesTurn);
                    simulatorEngine.undoLastMove();
                    if (!givesCheck) {
                        instr.recordQuiescenceSeePrune();
                        continue;
                    }
                }
            }

            simulatorEngine.performMove(m);
            instr.recordQuiescenceCaptureSearched();

            double child = quiescenceSearch(simulatorEngine, !isWhitesTurn, -beta, -alpha, deadline, depth + 1);
            simulatorEngine.undoLastMove();
            if (child == AI.EXIT_FLAG) return AI.EXIT_FLAG;

            double score = -child;
            if (score >= beta) return beta;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    private double maximizer(Engine simulatorEngine, int depth, double alpha, double beta,
                             long boardHash, double alphaOriginal,
                             MoveList moves, long deadline, int prevMove, int plyFromRoot,
                             int extStreak) {

        double maxEval = Double.NEGATIVE_INFINITY;
        int bestMoveAtThisNode = -1;
        SearchInstrumentation instr = ai.instrumentation();

        final boolean inCheckAtNode = isSideInCheck(simulatorEngine, true);
        final AI.Heuristics heuristics = ai.currentHeuristics();
        final int[][] historyTable = heuristics.history();

        MoveList orderedMoves = ai.sortMovesByEfficiency(moves, depth, boardHash, prevMove, simulatorEngine);
        final Map<Integer, Integer> seeCache = ai.getThreadLocalSeeCache();
        seeCache.clear();
        for (int index = 0; index < orderedMoves.size(); index++) {
            if (ai.abortRequested(deadline)) {
                return AI.EXIT_FLAG;
            }
            int move = orderedMoves.getMove(index);

            int from = MoveHelper.deriveFromIndex(move);
            int to = MoveHelper.deriveToIndex(move);
            int movingPieceBits = MoveHelper.derivePieceTypeBits(move);
            int capturedPieceBits = MoveHelper.deriveCapturedPieceTypeBits(move);
            boolean isCapture = MoveHelper.isCapture(move);
            boolean isPromotion = MoveHelper.isPawnPromotionMove(move);
            boolean isQuiet = !isCapture && !isPromotion;
            int historyScore = historyTable[from][to];

            int seeGain = 0;
            boolean seeEvaluated = false;
            boolean seeWinsMaterial = false;

            boolean seePruneCandidate = (!inCheckAtNode && isCapture && !isPromotion) || isQuiet;
            if (seePruneCandidate) {
                seeGain = seeCache.computeIfAbsent(move, simulatorEngine::see);
                seeEvaluated = true;
                if (seeGain < 0) {
                    simulatorEngine.performMove(move);
                    boolean givesCheckTmp = isSideInCheck(simulatorEngine, false);
                    simulatorEngine.undoLastMove();
                    if (!givesCheckTmp) {
                        continue;
                    }
                }
            }

            if (seeEvaluated) {
                seeWinsMaterial = seeGain > 0;
            }

            BitBoard boardBefore = simulatorEngine.getBitBoard();
            long enemyKingBB = boardBefore.getBlackKing();
            int enemyKingSquare = enemyKingBB != 0L ? Long.numberOfTrailingZeros(enemyKingBB) : -1;
            int enemyKingFile = enemyKingSquare >= 0 ? (enemyKingSquare & 7) : -1;
            long kingFileMask = enemyKingFile >= 0 ? FileMasks[enemyKingFile] : 0L;
            boolean touchesKingFile = enemyKingSquare >= 0 && (((from & 7) == enemyKingFile) || ((to & 7) == enemyKingFile));
            boolean affectsKingFilePawns = touchesKingFile && (movingPieceBits == 1 || capturedPieceBits == 1);
            int pawnsOnFileBefore = (enemyKingSquare >= 0 && affectsKingFilePawns) ? countPawnsOnFile(boardBefore, kingFileMask) : 0;

            boolean isTactical = isCapture || isPromotion;
            boolean lmpPrecomputed = false;
            boolean lmpGivesCheck = false;
            boolean lmpAttacksQueen = false;
            boolean lmpAttacksKingZone = false;
            int lmpThreshold = 8 + depth * 2;
            if (!inCheckAtNode && !isTactical && depth <= 3 && index > lmpThreshold) {
                simulatorEngine.performMove(move);
                lmpGivesCheck = isSideInCheck(simulatorEngine, false);
                lmpAttacksQueen = attacksOpponentQueenNow(simulatorEngine, true);
                lmpAttacksKingZone = attacksOpponentKingZone(simulatorEngine, true);
                lmpPrecomputed = true;
                boolean skipQuiet = isQuiet
                        && !lmpGivesCheck
                        && !lmpAttacksQueen
                        && !lmpAttacksKingZone
                        && !seeWinsMaterial;
                simulatorEngine.undoLastMove();
                if (skipQuiet) {
                    instr.recordLateMovePrune();
                    continue;
                }
            }

            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();

            boolean givesCheck = lmpPrecomputed ? lmpGivesCheck : isSideInCheck(simulatorEngine, false);
            boolean attacksQueen = lmpPrecomputed ? lmpAttacksQueen : attacksOpponentQueenNow(simulatorEngine, true);
            boolean attacksHeavyPiece = attacksQueen || attacksOpponentRookNow(simulatorEngine, true);
            boolean attacksKingZone = lmpPrecomputed ? lmpAttacksKingZone : attacksOpponentKingZone(simulatorEngine, true);
            boolean opensKingFile = openedFileTowardKing(simulatorEngine.getBitBoard(), kingFileMask, pawnsOnFileBefore, affectsKingFilePawns);

            int nextDepth = depth - 1;
            boolean forcing = givesCheck || attacksQueen;
            boolean allowExtend = forcing && extStreak < MAX_CHECK_EXTENSIONS_IN_A_ROW;
            if (allowExtend) nextDepth++;
            int nextExtStreak = allowExtend ? extStreak + 1 : 0;

            boolean allowStandPatPrune = !inCheckAtNode
                    && isQuiet
                    && depth <= 2
                    && nextDepth <= 2
                    && !givesCheck
                    && !attacksHeavyPiece
                    && !seeWinsMaterial;

            if (allowStandPatPrune) {
                double staticEval = ai.evaluateStaticPosition(simulatorEngine.getGameState(), false, depth);
                staticEval = -staticEval;
                double margin = computeStandPatMargin(simulatorEngine.getBitBoard(), depth, nextDepth);
                if (staticEval + margin <= alpha) {
                    simulatorEngine.undoLastMove();
                    instr.recordFutilityPrune();
                    continue;
                }
            }

            double eval;
            instr.recordTranspositionLookup();
            TranspositionTableEntry childEntry = ai.getTranspositionTable().get(newBoardHash);
            boolean ttExactHit = childEntry != null
                    && childEntry.nodeType == NodeType.EXACT
                    && childEntry.depth >= nextDepth;

            if (ttExactHit) {
                instr.recordTranspositionHit(childEntry.nodeType, false);
                eval = childEntry.score;
            } else {
                if (childEntry != null) {
                    instr.recordTranspositionHit(childEntry.nodeType, false);
                }
                boolean canReduce = !inCheckAtNode
                        && !isTactical
                        && !givesCheck
                        && !attacksQueen
                        && !attacksKingZone
                        && !opensKingFile
                        && !seeWinsMaterial
                        && nextDepth >= 2
                        && index >= 3;

                if (plyFromRoot <= 1) {
                    canReduce = false;
                }

                boolean usePvs = index > 0 && alpha != Double.NEGATIVE_INFINITY && beta != Double.POSITIVE_INFINITY;
                double pBeta = usePvs ? (alpha + 1) : beta;

                int reduction = 0;
                if (canReduce) {
                    reduction = lmrReduction(nextDepth, index, historyScore);
                    if (reduction <= 0) canReduce = false;
                    else instr.recordLateMoveReduction(reduction);
                }

                if (canReduce) {
                    int reduced = Math.max(1, nextDepth - reduction);
                    eval = alphaBeta(simulatorEngine, reduced, alpha, pBeta, false, deadline, move, plyFromRoot + 1, nextExtStreak);
                    if (eval == AI.EXIT_FLAG || ai.positionChanged()) {
                        simulatorEngine.undoLastMove();
                        return AI.EXIT_FLAG;
                    }

                    boolean promising = eval < beta;
                    if (promising) {
                        eval = alphaBeta(simulatorEngine, nextDepth, usePvs ? alpha : pBeta, beta,
                                false, deadline, move, plyFromRoot + 1, nextExtStreak);
                        if (eval == AI.EXIT_FLAG || ai.positionChanged()) {
                            simulatorEngine.undoLastMove();
                            return AI.EXIT_FLAG;
                        }
                    }
                } else {
                    eval = alphaBeta(simulatorEngine, nextDepth, pBeta, beta, false, deadline, move, plyFromRoot + 1, nextExtStreak);
                    if (eval == AI.EXIT_FLAG || ai.positionChanged()) {
                        simulatorEngine.undoLastMove();
                        return AI.EXIT_FLAG;
                    }

                    if (usePvs && eval > alpha && eval < beta) {
                        eval = alphaBeta(simulatorEngine, nextDepth, alpha, beta, false, deadline, move, plyFromRoot + 1, nextExtStreak);
                        if (eval == AI.EXIT_FLAG || ai.positionChanged()) {
                            simulatorEngine.undoLastMove();
                            return AI.EXIT_FLAG;
                        }
                    }
                }
            }

            simulatorEngine.undoLastMove();

            if (eval > maxEval) {
                maxEval = eval;
                bestMoveAtThisNode = move;
            }

            alpha = Math.max(alpha, eval);
            if (beta <= alpha) {
                ai.updateKillerMoves(depth, move);
                ai.incrementHistory(prevMove, move, depth);
                heuristics.recordCounterMove(prevMove, move);
                instr.recordBetaCutoff();
                break;
            }
        }

        TranspositionTableEntry existingEntry = ai.getTranspositionTable().get(boardHash);
        boolean shouldUpdate = existingEntry == null || existingEntry.depth < depth;

        if (maxEval <= alphaOriginal && shouldUpdate) {
            ai.getTranspositionTable().put(boardHash, new TranspositionTableEntry(maxEval, depth, NodeType.UPPERBOUND, bestMoveAtThisNode), depth);
        } else if (maxEval >= beta && shouldUpdate) {
            ai.getTranspositionTable().put(boardHash, new TranspositionTableEntry(maxEval, depth, NodeType.LOWERBOUND, bestMoveAtThisNode), depth);
        } else if (shouldUpdate) {
            ai.getTranspositionTable().put(boardHash, new TranspositionTableEntry(maxEval, depth, NodeType.EXACT, bestMoveAtThisNode), depth);
        }

        return maxEval;
    }

    private double minimizer(Engine simulatorEngine, int depth, double alpha, double beta,
                             long boardHash, double betaOriginal,
                             MoveList moves, long deadline, int prevMove, int plyFromRoot,
                             int extStreak) {

        double minEval = Double.POSITIVE_INFINITY;
        int bestMoveAtThisNode = -1;
        SearchInstrumentation instr = ai.instrumentation();

        final boolean inCheckAtNode = isSideInCheck(simulatorEngine, false);
        final AI.Heuristics heuristics = ai.currentHeuristics();
        final int[][] historyTable = heuristics.history();

        MoveList orderedMoves = ai.sortMovesByEfficiency(moves, depth, boardHash, prevMove, simulatorEngine);
        final Map<Integer, Integer> seeCache = ai.getThreadLocalSeeCache();
        seeCache.clear();
        for (int index = 0; index < orderedMoves.size(); index++) {
            if (ai.abortRequested(deadline)) {
                return AI.EXIT_FLAG;
            }
            int move = orderedMoves.getMove(index);

            int from = MoveHelper.deriveFromIndex(move);
            int to = MoveHelper.deriveToIndex(move);
            int movingPieceBits = MoveHelper.derivePieceTypeBits(move);
            int capturedPieceBits = MoveHelper.deriveCapturedPieceTypeBits(move);
            boolean isCapture = MoveHelper.isCapture(move);
            boolean isPromotion = MoveHelper.isPawnPromotionMove(move);
            boolean isQuiet = !isCapture && !isPromotion;
            int historyScore = historyTable[from][to];

            int seeGain = 0;
            boolean seeEvaluated = false;
            boolean seeWinsMaterial = false;

            boolean seePruneCandidate = (!inCheckAtNode && isCapture && !isPromotion) || isQuiet;
            if (seePruneCandidate) {
                seeGain = seeCache.computeIfAbsent(move, simulatorEngine::see);
                seeEvaluated = true;
                if (seeGain < 0) {
                    simulatorEngine.performMove(move);
                    boolean givesCheckTmp = isSideInCheck(simulatorEngine, true);
                    simulatorEngine.undoLastMove();
                    if (!givesCheckTmp) {
                        continue;
                    }
                }
            }

            if (seeEvaluated) {
                seeWinsMaterial = seeGain > 0;
            }

            BitBoard boardBefore = simulatorEngine.getBitBoard();
            long enemyKingBB = boardBefore.getWhiteKing();
            int enemyKingSquare = enemyKingBB != 0L ? Long.numberOfTrailingZeros(enemyKingBB) : -1;
            int enemyKingFile = enemyKingSquare >= 0 ? (enemyKingSquare & 7) : -1;
            long kingFileMask = enemyKingFile >= 0 ? FileMasks[enemyKingFile] : 0L;
            boolean touchesKingFile = enemyKingSquare >= 0 && (((from & 7) == enemyKingFile) || ((to & 7) == enemyKingFile));
            boolean affectsKingFilePawns = touchesKingFile && (movingPieceBits == 1 || capturedPieceBits == 1);
            int pawnsOnFileBefore = (enemyKingSquare >= 0 && affectsKingFilePawns) ? countPawnsOnFile(boardBefore, kingFileMask) : 0;

            boolean isTactical = isCapture || isPromotion;
            boolean lmpPrecomputed = false;
            boolean lmpGivesCheck = false;
            boolean lmpAttacksQueen = false;
            boolean lmpAttacksKingZone = false;
            int lmpThreshold = 8 + depth * 2;
            if (!inCheckAtNode && !isTactical && depth <= 3 && index > lmpThreshold) {
                simulatorEngine.performMove(move);
                lmpGivesCheck = isSideInCheck(simulatorEngine, true);
                lmpAttacksQueen = attacksOpponentQueenNow(simulatorEngine, false);
                lmpAttacksKingZone = attacksOpponentKingZone(simulatorEngine, false);
                lmpPrecomputed = true;
                boolean skipQuiet = isQuiet
                        && !lmpGivesCheck
                        && !lmpAttacksQueen
                        && !lmpAttacksKingZone
                        && !seeWinsMaterial;
                simulatorEngine.undoLastMove();
                if (skipQuiet) {
                    instr.recordLateMovePrune();
                    continue;
                }
            }

            simulatorEngine.performMove(move);
            long newBoardHash = simulatorEngine.getBoardStateHash();

            boolean givesCheck = lmpPrecomputed ? lmpGivesCheck : isSideInCheck(simulatorEngine, true);
            boolean attacksQueen = lmpPrecomputed ? lmpAttacksQueen : attacksOpponentQueenNow(simulatorEngine, false);
            boolean attacksHeavyPiece = attacksQueen || attacksOpponentRookNow(simulatorEngine, false);
            boolean attacksKingZone = lmpPrecomputed ? lmpAttacksKingZone : attacksOpponentKingZone(simulatorEngine, false);
            boolean opensKingFile = openedFileTowardKing(simulatorEngine.getBitBoard(), kingFileMask, pawnsOnFileBefore, affectsKingFilePawns);

            int nextDepth = depth - 1;
            boolean forcing = givesCheck || attacksQueen;
            boolean allowExtend = forcing && extStreak < MAX_CHECK_EXTENSIONS_IN_A_ROW;
            if (allowExtend) nextDepth++;
            int nextExtStreak = allowExtend ? extStreak + 1 : 0;

            boolean allowStandPatPrune = !inCheckAtNode
                    && isQuiet
                    && depth <= 2
                    && nextDepth <= 2
                    && !givesCheck
                    && !attacksHeavyPiece
                    && !seeWinsMaterial;

            if (allowStandPatPrune) {
                double staticEval = ai.evaluateStaticPosition(simulatorEngine.getGameState(), true, depth);
                double margin = computeStandPatMargin(simulatorEngine.getBitBoard(), depth, nextDepth);
                if (staticEval - margin >= beta) {
                    simulatorEngine.undoLastMove();
                    instr.recordFutilityPrune();
                    continue;
                }
            }

            double eval;
            instr.recordTranspositionLookup();
            TranspositionTableEntry childEntry = ai.getTranspositionTable().get(newBoardHash);
            boolean ttExactHit = childEntry != null
                    && childEntry.nodeType == NodeType.EXACT
                    && childEntry.depth >= nextDepth;

            if (ttExactHit) {
                instr.recordTranspositionHit(childEntry.nodeType, false);
                eval = childEntry.score;
            } else {
                if (childEntry != null) {
                    instr.recordTranspositionHit(childEntry.nodeType, false);
                }
                boolean canReduce = !inCheckAtNode
                        && !isTactical
                        && !givesCheck
                        && !attacksQueen
                        && !attacksKingZone
                        && !opensKingFile
                        && !seeWinsMaterial
                        && nextDepth >= 2
                        && index >= 3;

                if (plyFromRoot <= 1) {
                    canReduce = false;
                }

                boolean usePvs = index > 0 && alpha != Double.NEGATIVE_INFINITY && beta != Double.POSITIVE_INFINITY;
                double pAlpha = usePvs ? (beta - 1) : alpha;

                int reduction = 0;
                if (canReduce) {
                    reduction = lmrReduction(nextDepth, index, historyScore);
                    if (reduction <= 0) canReduce = false;
                    else instr.recordLateMoveReduction(reduction);
                }

                if (canReduce) {
                    int reduced = Math.max(1, nextDepth - reduction);
                    eval = alphaBeta(simulatorEngine, reduced, pAlpha, beta, true, deadline, move, plyFromRoot + 1, nextExtStreak);
                    if (eval == AI.EXIT_FLAG || ai.positionChanged()) {
                        simulatorEngine.undoLastMove();
                        return AI.EXIT_FLAG;
                    }

                    boolean promising = eval > alpha;
                    if (promising) {
                        eval = alphaBeta(simulatorEngine, nextDepth, usePvs ? alpha : pAlpha, beta,
                                true, deadline, move, plyFromRoot + 1, nextExtStreak);
                        if (eval == AI.EXIT_FLAG || ai.positionChanged()) {
                            simulatorEngine.undoLastMove();
                            return AI.EXIT_FLAG;
                        }
                    }
                } else {
                    eval = alphaBeta(simulatorEngine, nextDepth, alpha, beta, true, deadline, move, plyFromRoot + 1, nextExtStreak);
                    if (eval == AI.EXIT_FLAG || ai.positionChanged()) {
                        simulatorEngine.undoLastMove();
                        return AI.EXIT_FLAG;
                    }

                    if (usePvs && eval > alpha && eval < beta) {
                        eval = alphaBeta(simulatorEngine, nextDepth, alpha, beta, true, deadline, move, plyFromRoot + 1, nextExtStreak);
                        if (eval == AI.EXIT_FLAG || ai.positionChanged()) {
                            simulatorEngine.undoLastMove();
                            return AI.EXIT_FLAG;
                        }
                    }
                }
            }

            simulatorEngine.undoLastMove();

            if (eval < minEval) {
                minEval = eval;
                bestMoveAtThisNode = move;
            }

            beta = Math.min(beta, eval);
            if (alpha >= beta) {
                ai.updateKillerMoves(depth, move);
                ai.incrementHistory(prevMove, move, depth);
                heuristics.recordCounterMove(prevMove, move);
                instr.recordBetaCutoff();
                break;
            }
        }

        TranspositionTableEntry existingEntry = ai.getTranspositionTable().get(boardHash);
        boolean shouldUpdate = existingEntry == null || existingEntry.depth < depth;

        if (minEval >= betaOriginal && shouldUpdate) {
            ai.getTranspositionTable().put(boardHash, new TranspositionTableEntry(minEval, depth, NodeType.LOWERBOUND, bestMoveAtThisNode), depth);
        } else if (minEval <= alpha && shouldUpdate) {
            ai.getTranspositionTable().put(boardHash, new TranspositionTableEntry(minEval, depth, NodeType.UPPERBOUND, bestMoveAtThisNode), depth);
        } else if (shouldUpdate) {
            ai.getTranspositionTable().put(boardHash, new TranspositionTableEntry(minEval, depth, NodeType.EXACT, bestMoveAtThisNode), depth);
        }

        return minEval;
    }

    private boolean isSideInCheck(Engine engine, boolean isWhite) {
        GameStateEnum state = engine.getGameState().getState();
        if (isWhite) {
            if (state == GameStateEnum.WHITE_IN_CHECK || state == GameStateEnum.BLACK_WON) {
                return true;
            }
        } else {
            if (state == GameStateEnum.BLACK_IN_CHECK || state == GameStateEnum.WHITE_WON) {
                return true;
            }
        }
        return engine.getBitBoard().isInCheck(isWhite);
    }

    private boolean attacksOpponentQueenNow(Engine e, boolean moverIsWhite) {
        BitBoard bb = e.getBitBoard();
        long enemyQueen = moverIsWhite ? bb.getBlackQueens() : bb.getWhiteQueens();
        if (enemyQueen == 0) return false;
        long myAttacks = bb.getAttackBitboard(moverIsWhite);
        return (myAttacks & enemyQueen) != 0L;
    }

    private boolean attacksOpponentRookNow(Engine e, boolean moverIsWhite) {
        BitBoard bb = e.getBitBoard();
        long enemyRooks = moverIsWhite ? bb.getBlackRooks() : bb.getWhiteRooks();
        if (enemyRooks == 0L) {
            return false;
        }
        long myAttacks = bb.getAttackBitboard(moverIsWhite);
        return (myAttacks & enemyRooks) != 0L;
    }

    private boolean attacksOpponentKingZone(Engine e, boolean moverIsWhite) {
        BitBoard bb = e.getBitBoard();
        long enemyKing = moverIsWhite ? bb.getBlackKing() : bb.getWhiteKing();
        if (enemyKing == 0L) {
            return false;
        }
        int kingIndex = Long.numberOfTrailingZeros(enemyKing);
        long kingZone = KING_ATTACKS[kingIndex];
        long myAttacks = bb.getAttackBitboard(moverIsWhite);
        return (myAttacks & kingZone) != 0L;
    }

    private int computeNullMoveReduction(BitBoard board, int depth, boolean isWhite, int mobility) {
        int maxReduction = depth - 2;
        if (maxReduction <= 0) {
            return 0;
        }

        long pieces = isWhite ? board.getWhitePieces() : board.getBlackPieces();
        long pawns = isWhite ? board.getWhitePawns() : board.getBlackPawns();
        int nonPawnMaterial = Long.bitCount(pieces) - Long.bitCount(pawns);
        if (nonPawnMaterial < 0) {
            nonPawnMaterial = 0;
        }

        double reductionEstimate = getReductionEstimate(depth, mobility, nonPawnMaterial);

        int reduction = (int) Math.floor(Math.max(0.0, reductionEstimate));
        return Math.min(reduction, maxReduction);
    }

    private static double getReductionEstimate(int depth, int mobility, int nonPawnMaterial) {
        double depthFactor = Math.min(depth, 10) / 10.0;
        double materialFactor = Math.min(nonPawnMaterial, 12) / 12.0;
        double mobilityFactor = Math.min(Math.max(mobility, 0), 30) / 30.0;

        double reductionEstimate = 1.25
                + (depthFactor * 1.5)
                + (materialFactor * 0.75)
                + (mobilityFactor * 0.5);

        if (nonPawnMaterial <= 2 || mobility <= 4) {
            reductionEstimate -= 0.75;
        }
        if (mobility <= 2) {
            reductionEstimate -= 0.5;
        }
        return reductionEstimate;
    }

    private int countPawnsOnFile(BitBoard board, long fileMask) {
        if (fileMask == 0L) {
            return 0;
        }
        long pawns = (board.getWhitePawns() | board.getBlackPawns()) & fileMask;
        return Long.bitCount(pawns);
    }

    private boolean openedFileTowardKing(BitBoard boardAfterMove, long kingFileMask,
                                         int pawnsBefore, boolean interactsWithKingFile) {
        if (!interactsWithKingFile || kingFileMask == 0L || pawnsBefore <= 0) {
            return false;
        }
        int pawnsAfter = countPawnsOnFile(boardAfterMove, kingFileMask);
        return pawnsAfter < pawnsBefore;
    }

    private int lmrReduction(int depth, int moveIndex, int historyScore) {
        if (depth <= 2) return 0;

        double d = Math.log1p(depth);
        double m = Math.log1p(moveIndex + 1);
        double base = 0.35 * d * m;

        int h = Math.max(0, Math.min(historyScore, LMR_HISTORY_MAX));
        double hist = (LMR_HISTORY_MAX == 0) ? 0.0 : (double) h / LMR_HISTORY_MAX;
        double penalty = 0.8 * hist;

        int r = (int) Math.floor(base - penalty);

        if (r < 1) r = 1;
        if (r > depth - 1) r = depth - 1;
        return r;
    }

    private double computeStandPatMargin(BitBoard board, int depthRemaining, int nextDepth) {
        long whiteNonPawns = board.getWhitePieces() & ~board.getWhitePawns();
        long blackNonPawns = board.getBlackPieces() & ~board.getBlackPawns();
        int nonPawnCount = Long.bitCount(whiteNonPawns) + Long.bitCount(blackNonPawns);

        double materialFactor = Math.min(1.0, nonPawnCount / 16.0);
        int depthForMargin = Math.max(1, Math.min(4, Math.max(depthRemaining, nextDepth)));

        double base = 60.0;
        double depthBonus = depthForMargin * 35.0;
        double materialBonus = materialFactor * 70.0;
        return base + depthBonus + materialBonus;
    }

    private MoveList getPossibleCapturesOrPromotions(Engine simulatorEngine) {
        MoveList allLegalMoves = simulatorEngine.getAllLegalMoves();
        MoveList capturesAndPromotions = new MoveList();
        for (int i = 0; i < allLegalMoves.size(); i++) {
            int m = allLegalMoves.getMove(i);
            if (MoveHelper.isCapture(m) || MoveHelper.isPawnPromotionMove(m)) {
                capturesAndPromotions.add(m);
            }
        }

        return capturesAndPromotions;
    }
}
