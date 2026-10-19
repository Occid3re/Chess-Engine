package julius.game.chessengine.core;

import julius.game.chessengine.core.nnue.Accumulators;
import julius.game.chessengine.core.nnue.Network;

import static julius.game.chessengine.core.Position.*;
import static julius.game.chessengine.core.TranspositionTable.*;

/**
 * One search thread: iterative deepening + aspiration windows around a fail-soft negamax PVS search.
 * All scores are integer centipawns from the side to move's perspective.
 */
public final class Searcher {

    public static final int INF = 32000;
    public static final int MATE = 31000;
    public static final int MAX_PLY = 120;
    public static final int MATE_BOUND = MATE - 2 * MAX_PLY;
    public static final int TB_WIN = MATE_BOUND - 1;
    public static final int TB_BOUND = TB_WIN - 2 * MAX_PLY;

    private static final int[][] LMR = new int[64][64];

    static {
        initLmr();
    }

    /** (Re)builds the late-move-reduction table from {@link Tune#lmrBase} and {@link Tune#lmrDiv}. */
    static void initLmr() {
        double base = Tune.lmrBase / 100.0, div = Tune.lmrDiv / 100.0;
        for (int d = 1; d < 64; d++) {
            for (int m = 1; m < 64; m++) {
                LMR[d][m] = (int) (base + Math.log(d) * Math.log(m) / div);
            }
        }
    }

    final int id;
    final SearchManager mgr;
    final Position pos = new Position();
    final Eval eval = new Eval();
    private Accumulators acc;
    private Accumulators cachedAcc;
    private Network cachedNet;
    /** The network's uncertainty head while Tune.uncDepth > 0, else null. */
    private julius.game.chessengine.core.nnue.UncertaintyHead uncHead;
    private float[] uncInput;
    /** Uncertainty (x 1000) per position key, direct mapped: deep nodes come back in every iteration. */
    private final long[] uncKeys = new long[1 << 15];
    private final short[] uncValues = new short[1 << 15];

    long nodes;
    int selDepth;
    int rootDepth;
    int rootHistPly;
    int completedDepth;
    int bestMove;
    int bestScore;
    int rootUpdatedMove;
    int[] bestPv = new int[MAX_PLY + 1];
    int bestPvLength;
    long tbHits;

    private final int[] evalStack = new int[MAX_PLY + 8];
    private final int[] moveStack = new int[MAX_PLY + 8];
    private final int[] pieceStack = new int[MAX_PLY + 8];
    private final int[] excludedStack = new int[MAX_PLY + 8];
    private final int[][] killers = new int[MAX_PLY + 8][2];
    private final int[][] pvTable = new int[MAX_PLY + 8][MAX_PLY + 8];
    private final int[] pvLength = new int[MAX_PLY + 8];
    private final int[][] moveBuf = new int[MAX_PLY + 8][256];
    private final int[][] scoreBuf = new int[MAX_PLY + 8][256];
    private final int[][] quietsTried = new int[MAX_PLY + 8][64];
    private final int[][] capturesTried = new int[MAX_PLY + 8][64];

    private final int[] mainHistory = new int[2 * 4096];
    private final int[] captureHistory = new int[12 * 64 * 6];
    private final int[] contHistory = new int[768 * 768];
    private final int[] counterMoves = new int[768];

    Searcher(int id, SearchManager mgr) {
        this.id = id;
        this.mgr = mgr;
    }

    void clearHistory() {
        java.util.Arrays.fill(mainHistory, 0);
        java.util.Arrays.fill(captureHistory, 0);
        java.util.Arrays.fill(contHistory, 0);
        java.util.Arrays.fill(counterMoves, 0);
        for (int[] k : killers) {
            k[0] = k[1] = 0;
        }
    }

    // ------------------------------------------------------------------ iterative deepening

    void iterate(int maxDepth) {
        nodes = 0;
        tbHits = 0;
        completedDepth = 0;
        bestMove = Move.NONE;
        bestScore = -INF;
        bestPvLength = 0;
        rootHistPly = pos.historyPly();
        acc = mgr.useNnue ? accumulatorsFor(Network.loadDefault()) : null;
        uncHead = acc != null && Tune.uncDepth > 0 ? julius.game.chessengine.core.nnue.UncertaintyHead.loadDefault() : null;
        if (uncHead != null && (uncInput == null || uncInput.length != uncHead.inputs())) uncInput = new float[uncHead.inputs()];
        if (acc != null) acc.refresh(pos, 0);
        for (int[] k : killers) {
            k[0] = k[1] = 0;
        }
        int prevScore = 0;
        int lastBest = Move.NONE;
        for (int depth = 1; depth <= maxDepth; depth++) {
            if (id > 0 && depth > 1 && depth < maxDepth && ((depth + id) % 3 == 0)) {
                // Helper threads skip some depths to diversify the shared table.
                continue;
            }
            rootDepth = depth;
            selDepth = 0;
            rootUpdatedMove = Move.NONE;
            int delta = Tune.aspDelta;
            int alpha = -INF, beta = INF;
            if (depth >= 4) {
                alpha = Math.max(prevScore - delta, -INF);
                beta = Math.min(prevScore + delta, INF);
            }
            int score;
            while (true) {
                score = search(alpha, beta, depth, 0, false);
                if (mgr.stop) break;
                if (score <= alpha) {
                    beta = (alpha + beta) / 2;
                    alpha = Math.max(score - delta, -INF);
                } else if (score >= beta) {
                    beta = Math.min(score + delta, INF);
                    if (pvLength[0] > 0) {
                        bestMove = pvTable[0][0];
                    }
                } else {
                    break;
                }
                delta += delta / 2 + 5;
            }
            if (mgr.stop) {
                if (completedDepth == 0 && rootUpdatedMove != Move.NONE) bestMove = rootUpdatedMove;
                else if (rootUpdatedMove != Move.NONE && rootUpdatedMove != bestMove) bestMove = rootUpdatedMove;
                break;
            }
            completedDepth = depth;
            bestScore = score;
            prevScore = score;
            bestPvLength = pvLength[0];
            System.arraycopy(pvTable[0], 0, bestPv, 0, bestPvLength);
            if (bestPvLength > 0) bestMove = bestPv[0];
            if (id == 0) {
                boolean changed = lastBest != Move.NONE && lastBest != bestMove;
                lastBest = bestMove;
                mgr.onIterationComplete(this, changed);
                if (mgr.stop) break;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private Accumulators accumulatorsFor(Network net) {
        if (net == null) return null;
        if (net != cachedNet) {
            cachedNet = net;
            cachedAcc = Accumulators.create(net, MAX_PLY + 8);
        }
        return cachedAcc;
    }

    /** Static evaluation for the side to move at the given ply (NNUE when loaded, otherwise hand-crafted). */
    /** -Dcore.verifyNnue=true: every 64th network evaluation is checked against a from-scratch build (debugging). */
    private static final boolean VERIFY_NNUE = Boolean.getBoolean("core.verifyNnue");
    private Accumulators verifier;
    static final java.util.concurrent.atomic.AtomicLong VERIFIED = new java.util.concurrent.atomic.AtomicLong();

    private int evaluate(int ply) {
        int v = acc != null ? acc.evaluate(pos, ply) : eval.evaluate(pos);
        if (VERIFY_NNUE && acc != null && (nodes & 63) == 0) {
            if (verifier == null) verifier = Accumulators.create(cachedNet, 1);
            Accumulators fresh = Accumulators.create(cachedNet, 1); // empty cache: a true rebuild
            fresh.refresh(pos, 0);
            int expected = fresh.evaluate(pos, 0);
            if (expected != v) {
                throw new IllegalStateException("NNUE mismatch at ply " + ply + ": incremental " + v + " vs rebuilt "
                        + expected + " in " + pos.toFen());
            }
            VERIFIED.incrementAndGet();
        }
        // Fade the evaluation towards a draw as the fifty-move counter grows.
        v = v * (200 - pos.halfmove) / 200;
        return Math.max(-TB_BOUND + 1, Math.min(TB_BOUND - 1, v));
    }

    /** A draw, from the side to move's point of view: worth -contempt to the root side, +contempt to the other. */
    private int drawScore(int ply) {
        int c = mgr.contempt;
        return 1 - (int) (nodes & 2) + ((ply & 1) == 0 ? -c : c);
    }

    private static int scoreToTT(int score, int ply) {
        if (score == TranspositionTable.VALUE_NONE) return score;
        if (score >= TB_BOUND) return score + ply;
        if (score <= -TB_BOUND) return score - ply;
        return score;
    }

    private static int scoreFromTT(int score, int ply) {
        if (score == TranspositionTable.VALUE_NONE) return score;
        if (score >= TB_BOUND) return score - ply;
        if (score <= -TB_BOUND) return score + ply;
        return score;
    }

    private int contIdx(int ply) {
        if (ply < 0) return -1;
        int m = moveStack[ply];
        if (m == Move.NONE) return -1;
        return pieceStack[ply] * 64 + Move.to(m);
    }

    private int quietHistory(int side, int m, int pc, int c1, int c2) {
        int from = Move.from(m), to = Move.to(m);
        int h = mainHistory[side * 4096 + from * 64 + to];
        int pt = pc * 64 + to;
        if (c1 >= 0) h += contHistory[c1 * 768 + pt];
        if (c2 >= 0) h += contHistory[c2 * 768 + pt];
        return h;
    }

    private static int capturedType(Position p, int m) {
        if (Move.flag(m) == Move.EP_CAPTURE) return PAWN;
        int victim = p.board[Move.to(m)];
        return victim == NO_PIECE ? PAWN : type(victim);
    }

    private int captureHistoryIndex(int m) {
        return (pos.board[Move.from(m)] * 64 + Move.to(m)) * 6 + capturedType(pos, m);
    }

    private static void gravity(int[] table, int idx, int bonus) {
        table[idx] += bonus - table[idx] * Math.abs(bonus) / 16384;
    }

    private static int statBonus(int depth) {
        return Math.min(Tune.bonusMax, Tune.bonusMul * depth - Tune.bonusSub);
    }

    private void scoreMoves(int ply, int n, int ttMove, boolean qs) {
        int[] moves = moveBuf[ply], scores = scoreBuf[ply];
        int side = pos.side;
        int k1 = killers[ply][0], k2 = killers[ply][1];
        int c1 = contIdx(ply - 1), c2 = contIdx(ply - 2);
        int counter = c1 >= 0 ? counterMoves[c1] : Move.NONE;
        for (int i = 0; i < n; i++) {
            int m = moves[i];
            int s;
            if (m == ttMove) {
                s = 2_000_000_000;
            } else if (Move.isNoisy(m)) {
                if (Move.isPromotion(m)) {
                    s = Move.promotionType(m) == QUEEN ? 1_500_000 : -2_000_000;
                    if (Move.isCapture(m)) s += SEE_VALUE[capturedType(pos, m)];
                } else {
                    int mvv = SEE_VALUE[capturedType(pos, m)];
                    int ch = captureHistory[captureHistoryIndex(m)];
                    s = mvv * 16 + ch / 16;
                    if (!qs) s += pos.seeGE(m, -50) ? 1_000_000 : -1_000_000;
                }
            } else if (m == k1) {
                s = 900_000;
            } else if (m == k2) {
                s = 890_000;
            } else if (m == counter) {
                s = 880_000;
            } else {
                s = quietHistory(side, m, pos.board[Move.from(m)], c1, c2);
            }
            scores[i] = s;
        }
    }

    private int pickNext(int ply, int i, int n) {
        int[] moves = moveBuf[ply], scores = scoreBuf[ply];
        int best = i;
        int bestS = scores[i];
        for (int j = i + 1; j < n; j++) {
            if (scores[j] > bestS) {
                bestS = scores[j];
                best = j;
            }
        }
        if (best != i) {
            int tm = moves[i];
            moves[i] = moves[best];
            moves[best] = tm;
            int ts = scores[i];
            scores[i] = scores[best];
            scores[best] = ts;
        }
        return moves[i];
    }

    private void updateQuietStats(int ply, int best, int depth, int nq) {
        int bonus = statBonus(depth);
        if (killers[ply][0] != best) {
            killers[ply][1] = killers[ply][0];
            killers[ply][0] = best;
        }
        int c1 = contIdx(ply - 1);
        if (c1 >= 0) counterMoves[c1] = best;
        updateQuiet(ply, best, bonus);
        int[] q = quietsTried[ply];
        for (int i = 0; i < nq; i++) updateQuiet(ply, q[i], -bonus);
    }

    private void updateQuiet(int ply, int m, int bonus) {
        int from = Move.from(m), to = Move.to(m);
        gravity(mainHistory, pos.side * 4096 + from * 64 + to, bonus);
        int pt = pos.board[from] * 64 + to;
        int c1 = contIdx(ply - 1), c2 = contIdx(ply - 2);
        if (c1 >= 0) gravity(contHistory, c1 * 768 + pt, bonus);
        if (c2 >= 0) gravity(contHistory, c2 * 768 + pt, bonus);
    }

    // ------------------------------------------------------------------ main search

    int search(int alpha, int beta, int depth, int ply, boolean cutNode) {
        final boolean pvNode = beta - alpha > 1;
        final boolean root = ply == 0;
        pvLength[ply] = ply;
        final boolean inCheck = pos.inCheck();
        if (inCheck && ply < 2 * rootDepth + 8) depth++;
        if (depth <= 0) return qsearch(alpha, beta, ply);

        nodes++;
        if (id == 0 && (nodes & 1023) == 0) mgr.checkTime(this);
        if (mgr.stop) return 0;
        if (ply > selDepth) selDepth = ply;

        if (!root) {
            if (pos.halfmove >= 100 || pos.isInsufficientMaterial() || pos.isRepetition(rootHistPly)) {
                return drawScore(ply);
            }
            if (ply >= MAX_PLY) return inCheck ? 0 : evaluate(ply);
            alpha = Math.max(alpha, -MATE + ply);
            beta = Math.min(beta, MATE - ply - 1);
            if (alpha >= beta) return alpha;
        }

        final int excluded = excludedStack[ply];
        final long key = excluded == Move.NONE ? pos.key : pos.key ^ (0x9E3779B97F4A7C15L * (excluded + 1));
        final long tte = mgr.tt.probe(key);
        final boolean ttHit = tte != 0L;
        int ttMove = ttHit ? TranspositionTable.move(tte) : Move.NONE;
        if (root && bestMove != Move.NONE) ttMove = bestMove;
        if (ttMove != Move.NONE && !pos.isPseudoLegal(ttMove)) ttMove = Move.NONE;
        final int ttScore = ttHit ? scoreFromTT(TranspositionTable.score(tte), ply) : VALUE_NONE;
        final int ttDepth = ttHit ? TranspositionTable.depth(tte) : -100;
        final int ttBound = ttHit ? TranspositionTable.bound(tte) : BOUND_NONE;

        if (!pvNode && ttHit && ttDepth >= depth && ttScore != VALUE_NONE
                && (ttBound & (ttScore >= beta ? BOUND_LOWER : BOUND_UPPER)) != 0) {
            if (ttScore >= beta && ttMove != Move.NONE && Move.isQuiet(ttMove) && pos.isLegal(ttMove)) {
                updateQuiet(ply, ttMove, statBonus(depth));
            }
            return ttScore;
        }

        // Tablebase probe
        if (!root && excluded == Move.NONE && mgr.tablebases != null) {
            int tb = mgr.tablebases.probeWdl(pos);
            if (tb != Tablebases.FAIL) {
                tbHits++;
                int value;
                int bound;
                if (tb > 0) {
                    value = TB_WIN - ply;
                    bound = BOUND_LOWER;
                } else if (tb < 0) {
                    value = -TB_WIN + ply;
                    bound = BOUND_UPPER;
                } else {
                    value = 0;
                    bound = BOUND_EXACT;
                }
                if (bound == BOUND_EXACT || (bound == BOUND_LOWER ? value >= beta : value <= alpha)) {
                    mgr.tt.store(key, Move.NONE, scoreToTT(value, ply), VALUE_NONE, Math.min(MAX_PLY, depth + 6), bound);
                    return value;
                }
            }
        }

        int staticEval;
        int evalv;
        boolean improving;
        if (inCheck) {
            staticEval = evalv = VALUE_NONE;
            evalStack[ply] = VALUE_NONE;
            improving = false;
        } else {
            int te = ttHit ? TranspositionTable.eval(tte) : VALUE_NONE;
            staticEval = te != VALUE_NONE ? te : evaluate(ply);
            evalStack[ply] = staticEval;
            evalv = staticEval;
            if (ttScore != VALUE_NONE && (ttBound & (ttScore > staticEval ? BOUND_LOWER : BOUND_UPPER)) != 0) {
                evalv = ttScore;
            }
            if (!ttHit && excluded == Move.NONE) {
                mgr.tt.store(key, Move.NONE, VALUE_NONE, staticEval, -1, BOUND_NONE);
            }
            if (ply >= 2 && evalStack[ply - 2] != VALUE_NONE) improving = staticEval > evalStack[ply - 2];
            else if (ply >= 4 && evalStack[ply - 4] != VALUE_NONE) improving = staticEval > evalStack[ply - 4];
            else improving = true;
        }
        killers[ply + 2][0] = killers[ply + 2][1] = Move.NONE;
        excludedStack[ply + 1] = Move.NONE;
        // How sure the network is here (Tune.uncDepth): sure -> reduce late moves more, unclear -> less
        int uncAdjust = 0;
        if (uncHead != null && !inCheck && depth >= Tune.uncDepth) {
            int slot = (int) (pos.key & (uncKeys.length - 1));
            int u;
            if (uncKeys[slot] == pos.key) {
                u = uncValues[slot];
            } else {
                acc.activations(pos, ply, uncInput);
                u = (int) Math.min(Short.MAX_VALUE, uncHead.predict(uncInput, Long.bitCount(pos.all)) * 1000);
                uncKeys[slot] = pos.key;
                uncValues[slot] = (short) u;
            }
            uncAdjust = u < Tune.uncLo ? 1 : u > Tune.uncHi ? -1 : 0;
        }

        if (!pvNode && !inCheck && excluded == Move.NONE) {
            // Reverse futility pruning
            if (depth <= 8 && evalv - Tune.rfpMargin * (depth - (improving ? 1 : 0)) >= beta && evalv < TB_BOUND) {
                return evalv;
            }
            // Razoring
            if (depth <= 3 && evalv + Tune.razorMargin * depth < alpha) {
                int v = qsearch(alpha, alpha + 1, ply);
                if (v <= alpha) return v;
            }
            // Null move pruning
            if (depth >= 3 && evalv >= beta && staticEval >= beta - Tune.nmpDepthMargin * depth + Tune.nmpBaseMargin
                    && ply > 0 && moveStack[ply - 1] != Move.NONE
                    && pos.hasNonPawnMaterial(pos.side) && beta > -TB_BOUND) {
                int r = 4 + depth / 4 + Math.min(3, (evalv - beta) / Tune.nmpEvalDiv);
                moveStack[ply] = Move.NONE;
                pieceStack[ply] = 0;
                if (acc != null) acc.pushNull(ply);
                pos.makeNull();
                int v = -search(-beta, -beta + 1, depth - r, ply + 1, !cutNode);
                pos.unmakeNull();
                if (mgr.stop) return 0;
                if (v >= beta) {
                    if (v >= TB_BOUND) v = beta;
                    if (depth < 14) return v;
                    // Verification search at high depth (zugzwang safety)
                    int vv = search(beta - 1, beta, depth - r, ply, false);
                    if (vv >= beta) return v;
                }
            }
            // ProbCut
            int probBeta = beta + Tune.probcutMargin;
            if (depth >= 5 && Math.abs(beta) < TB_BOUND
                    && !(ttHit && ttDepth >= depth - 3 && ttScore != VALUE_NONE && ttScore < probBeta)) {
                int[] moves = moveBuf[ply];
                int n = MoveGen.generateNoisy(pos, moves, 0);
                scoreMoves(ply, n, ttMove, true);
                for (int i = 0; i < n; i++) {
                    int m = pickNext(ply, i, n);
                    if (!pos.seeGE(m, probBeta - staticEval)) continue;
                    if (!pos.isLegal(m)) continue;
                    moveStack[ply] = m;
                    pieceStack[ply] = pos.board[Move.from(m)];
                    if (acc != null) acc.push(pos, m, ply);
                    pos.makeMove(m);
                    int v = -qsearch(-probBeta, -probBeta + 1, ply + 1);
                    if (v >= probBeta) v = -search(-probBeta, -probBeta + 1, depth - 4, ply + 1, !cutNode);
                    pos.unmakeMove(m);
                    if (mgr.stop) return 0;
                    if (v >= probBeta) {
                        mgr.tt.store(key, m, scoreToTT(v, ply), staticEval, depth - 3, BOUND_LOWER);
                        return v;
                    }
                }
            }
        }

        // Internal iterative reduction
        if ((pvNode || cutNode) && depth >= 4 && ttMove == Move.NONE && excluded == Move.NONE) depth--;

        final int[] moves = moveBuf[ply];
        final int n = MoveGen.generateAll(pos, moves, 0);
        scoreMoves(ply, n, ttMove, false);
        final int side = pos.side;
        final int c1 = contIdx(ply - 1), c2 = contIdx(ply - 2);
        final int counter = c1 >= 0 ? counterMoves[c1] : Move.NONE;
        final int[] scores = scoreBuf[ply];

        int bestScore = -INF;
        int bestMoveHere = Move.NONE;
        int moveCount = 0, nq = 0, nc = 0;
        boolean skipQuiets = false;
        final int alphaOrig = alpha;

        for (int i = 0; i < n; i++) {
            int m = pickNext(ply, i, n);
            if (m == excluded) continue;
            boolean quiet = Move.isQuiet(m);
            if (skipQuiets && quiet && m != ttMove) continue;
            if (!pos.isLegal(m)) continue;
            // Root moves can be restricted (searchmoves) or penalised: a penalised move is searched with the
            // window shifted up by its penalty, so it is only chosen when it is that much better.
            if (root && !mgr.rootAllowed(m)) continue;
            final int pen = root ? mgr.rootPenalty(m) : 0;
            final int a = alpha + pen, b = beta + pen;
            moveCount++;
            int from = Move.from(m);
            int pc = pos.board[from];
            boolean givesCheck = pos.givesCheck(m);
            int hist = quiet ? quietHistory(side, m, pc, c1, c2) : 0;
            int r = LMR[Math.min(depth, 63)][Math.min(moveCount, 63)];

            if (!root && bestScore > -TB_BOUND && pos.hasNonPawnMaterial(side)) {
                int lmrDepth = Math.max(0, depth - 1 - r);
                if (quiet) {
                    int lmpLimit = improving ? 3 + depth * depth : (3 + depth * depth) / 2;
                    if (depth <= 8 && moveCount >= lmpLimit) {
                        skipQuiets = true;
                        continue;
                    }
                    if (!givesCheck) {
                        if (lmrDepth <= 3 && hist < -Tune.histPrune * depth) continue;
                        if (!inCheck && lmrDepth <= 8 && staticEval + Tune.futBase + Tune.futMul * lmrDepth <= alpha) continue;
                        if (lmrDepth <= 8 && !pos.seeGE(m, -Tune.seeQuiet * lmrDepth * lmrDepth)) continue;
                    }
                } else if (depth <= 8 && scores[i] < 0 && !pos.seeGE(m, -Tune.seeNoisy * depth)) {
                    continue;
                }
            }

            int ext = 0;
            // Singular extension: the TT move is much better than all alternatives
            if (!root && m == ttMove && excluded == Move.NONE && depth >= 7 && ttDepth >= depth - 3
                    && (ttBound & BOUND_LOWER) != 0 && ttScore != VALUE_NONE && Math.abs(ttScore) < TB_BOUND
                    && ply < 2 * rootDepth) {
                int singularBeta = ttScore - Tune.singularMul * depth / 10;
                excludedStack[ply] = m;
                int v = search(singularBeta - 1, singularBeta, (depth - 1) / 2, ply, cutNode);
                excludedStack[ply] = Move.NONE;
                if (mgr.stop) return 0;
                if (v < singularBeta) {
                    ext = 1;
                    if (!pvNode && v < singularBeta - Tune.doubleExtMargin && ply < rootDepth) ext = 2;
                } else if (singularBeta >= beta) {
                    return singularBeta; // multi-cut
                } else if (ttScore >= beta) {
                    ext = -1;
                }
                // regenerate ordering state: the singular search reused this ply's buffers
                n2Restore(ply, n, ttMove);
            }

            moveStack[ply] = m;
            pieceStack[ply] = pc;
            if (acc != null) acc.push(pos, m, ply);
            pos.makeMove(m);
            int newDepth = depth - 1 + ext;
            int score = 0;
            if (depth >= 2 && moveCount > 1 + (root ? 1 : 0) && (quiet || scores[i] < 0)) {
                int rr = r;
                if (cutNode) rr++;
                if (pvNode) rr--;
                if (!improving) rr++;
                if (givesCheck) rr--;
                if (m == killers[ply][0] || m == killers[ply][1] || m == counter) rr--;
                if (quiet) rr -= hist / Tune.lmrHistDiv;
                rr += uncAdjust;
                int d = Math.max(1, Math.min(newDepth, newDepth - rr));
                score = -search(-a - 1, -a, d, ply + 1, true);
                if (score > a && d < newDepth) {
                    score = -search(-a - 1, -a, newDepth, ply + 1, !cutNode);
                }
            } else if (!pvNode || moveCount > 1) {
                score = -search(-a - 1, -a, newDepth, ply + 1, !cutNode);
            }
            if (pvNode && (moveCount == 1 || score > a)) {
                score = -search(-b, -a, newDepth, ply + 1, false);
            }
            pos.unmakeMove(m);
            if (mgr.stop) return 0;
            score -= pen;

            if (score > bestScore) {
                bestScore = score;
                if (score > alpha) {
                    bestMoveHere = m;
                    if (pvNode) {
                        int[] row = pvTable[ply];
                        row[ply] = m;
                        int len = pvLength[ply + 1];
                        System.arraycopy(pvTable[ply + 1], ply + 1, row, ply + 1, len - ply - 1);
                        pvLength[ply] = Math.max(len, ply + 1);
                    }
                    if (root) rootUpdatedMove = m;
                    if (score >= beta) break;
                    alpha = score;
                }
            }
            if (m != bestMoveHere) {
                if (quiet) {
                    if (nq < 64) quietsTried[ply][nq++] = m;
                } else if (nc < 64) {
                    capturesTried[ply][nc++] = m;
                }
            }
        }

        if (moveCount == 0) {
            if (excluded != Move.NONE) return alpha;
            return inCheck ? -MATE + ply : 0;
        }

        if (bestScore >= beta) {
            int bonus = statBonus(depth);
            if (Move.isQuiet(bestMoveHere)) {
                updateQuietStats(ply, bestMoveHere, depth, nq);
            } else {
                gravity(captureHistory, captureHistoryIndex(bestMoveHere), bonus);
            }
            for (int i = 0; i < nc; i++) gravity(captureHistory, captureHistoryIndex(capturesTried[ply][i]), -bonus);
        }

        if (excluded == Move.NONE && !(root && mgr.rootPenalised())) {
            int bound = bestScore >= beta ? BOUND_LOWER : (pvNode && bestScore > alphaOrig ? BOUND_EXACT : BOUND_UPPER);
            mgr.tt.store(key, bestMoveHere, scoreToTT(bestScore, ply), staticEval, depth, bound);
        }
        return bestScore;
    }

    /** After a singular verification search the move buffer of this ply has been overwritten; rebuild it. */
    private void n2Restore(int ply, int n, int ttMove) {
        int[] moves = moveBuf[ply];
        int regenerated = MoveGen.generateAll(pos, moves, 0);
        scoreMoves(ply, regenerated, ttMove, false);
        // Put the TT move first again (it has the maximal score) so the loop index stays consistent.
        pickNext(ply, 0, regenerated);
        if (regenerated != n) throw new IllegalStateException("move count changed during singular search");
    }

    // ------------------------------------------------------------------ quiescence

    int qsearch(int alpha, int beta, int ply) {
        final boolean pvNode = beta - alpha > 1;
        pvLength[ply] = ply;
        nodes++;
        if (id == 0 && (nodes & 1023) == 0) mgr.checkTime(this);
        if (mgr.stop) return 0;
        if (ply > selDepth) selDepth = ply;
        if (pos.halfmove >= 100 || pos.isInsufficientMaterial() || pos.isRepetition(rootHistPly)) return drawScore(ply);
        final boolean inCheck = pos.inCheck();
        if (ply >= MAX_PLY) return inCheck ? 0 : evaluate(ply);

        final long key = pos.key;
        final long tte = mgr.tt.probe(key);
        final boolean ttHit = tte != 0L;
        final int ttScore = ttHit ? scoreFromTT(TranspositionTable.score(tte), ply) : VALUE_NONE;
        final int ttBound = ttHit ? TranspositionTable.bound(tte) : BOUND_NONE;
        int ttMove = ttHit ? TranspositionTable.move(tte) : Move.NONE;
        if (!pvNode && ttScore != VALUE_NONE && (ttBound & (ttScore >= beta ? BOUND_LOWER : BOUND_UPPER)) != 0) {
            return ttScore;
        }

        int bestScore;
        int staticEval = VALUE_NONE;
        int futilityBase;
        if (inCheck) {
            bestScore = -INF;
            futilityBase = -INF;
        } else {
            int te = ttHit ? TranspositionTable.eval(tte) : VALUE_NONE;
            staticEval = te != VALUE_NONE ? te : evaluate(ply);
            bestScore = staticEval;
            if (ttScore != VALUE_NONE && (ttBound & (ttScore > bestScore ? BOUND_LOWER : BOUND_UPPER)) != 0) {
                bestScore = ttScore;
            }
            if (bestScore >= beta) {
                if (!ttHit) mgr.tt.store(key, Move.NONE, scoreToTT(bestScore, ply), staticEval, -1, BOUND_LOWER);
                return bestScore;
            }
            if (bestScore > alpha) alpha = bestScore;
            futilityBase = staticEval + Tune.qsFutility;
        }

        if (ttMove != Move.NONE && (!pos.isPseudoLegal(ttMove) || (!inCheck && !Move.isNoisy(ttMove)))) ttMove = Move.NONE;
        final int[] moves = moveBuf[ply];
        final int n = inCheck ? MoveGen.generateAll(pos, moves, 0) : MoveGen.generateNoisy(pos, moves, 0);
        scoreMoves(ply, n, ttMove, true);
        int bestMoveHere = Move.NONE;
        int moveCount = 0;
        for (int i = 0; i < n; i++) {
            int m = pickNext(ply, i, n);
            if (!pos.isLegal(m)) continue;
            moveCount++;
            if (!inCheck) {
                if (Move.isPromotion(m) && Move.promotionType(m) != QUEEN) continue;
                if (!Move.isPromotion(m)) {
                    int value = futilityBase + SEE_VALUE[capturedType(pos, m)];
                    if (value <= alpha && !pos.givesCheck(m)) {
                        if (value > bestScore) bestScore = value;
                        continue;
                    }
                }
                if (!pos.seeGE(m, 0)) continue;
            } else if (bestScore > -TB_BOUND && moveCount > 3 && Move.isQuiet(m)) {
                continue;
            }
            moveStack[ply] = m;
            pieceStack[ply] = pos.board[Move.from(m)];
            if (acc != null) acc.push(pos, m, ply);
            pos.makeMove(m);
            int score = -qsearch(-beta, -alpha, ply + 1);
            pos.unmakeMove(m);
            if (mgr.stop) return 0;
            if (score > bestScore) {
                bestScore = score;
                if (score > alpha) {
                    bestMoveHere = m;
                    if (pvNode) {
                        int[] row = pvTable[ply];
                        row[ply] = m;
                        int len = pvLength[ply + 1];
                        System.arraycopy(pvTable[ply + 1], ply + 1, row, ply + 1, len - ply - 1);
                        pvLength[ply] = Math.max(len, ply + 1);
                    }
                    if (score >= beta) break;
                    alpha = score;
                }
            }
        }
        if (inCheck && moveCount == 0) return -MATE + ply;
        int bound = bestScore >= beta ? BOUND_LOWER : BOUND_UPPER;
        mgr.tt.store(key, bestMoveHere, scoreToTT(bestScore, ply), staticEval, 0, bound);
        return bestScore;
    }
}
