package julius.game.chessengine.core;

import java.util.function.Consumer;

/**
 * Owns the shared transposition table and the search threads (Lazy SMP), and implements time management.
 */
public final class SearchManager {

    final TranspositionTable tt;
    volatile boolean stop;
    volatile boolean pondering;
    Tablebases tablebases;
    /** Use the bundled NNUE network when one is available (hand-crafted evaluation otherwise). */
    boolean useNnue = !"false".equalsIgnoreCase(System.getProperty("core.useNnue", "true"));
    /** Centipawns a draw is worth less than equality for the side to move at the root (0 = plain draw score). */
    volatile int contempt;

    private Searcher[] searchers;
    private Thread[] threads;
    private Thread mainThread;
    private Consumer<String> out = s -> { };

    private int moveOverhead = 30;
    private long startNanos;
    private long optimumMs;
    private long maximumMs;
    private long nodeLimit;
    private boolean timeManaged;
    private SearchLimits limits;
    private double instability;
    /** The root's uncertainty (expected evaluation error as a win probability), -1 without an uncertainty head. */
    private double rootUncertainty = -1;
    private int lastScore;
    private final Object finishLock = new Object();

    public SearchManager(int hashMb, int threads) {
        tt = new TranspositionTable(hashMb);
        setThreads(threads);
    }

    public synchronized void setThreads(int n) {
        n = Math.max(1, Math.min(256, n));
        searchers = new Searcher[n];
        for (int i = 0; i < n; i++) searchers[i] = new Searcher(i, this);
    }

    public int threadCount() {
        return searchers.length;
    }

    public void setHash(int mb) {
        tt.resize(mb);
    }

    public void setMoveOverhead(int ms) {
        moveOverhead = Math.max(0, ms);
    }

    public void setTablebases(Tablebases tb) {
        tablebases = tb;
    }

    public void setContempt(int cp) {
        contempt = Math.max(-200, Math.min(200, cp));
    }

    /** Whether a root move may be searched ("go searchmoves"). */
    boolean rootAllowed(int move) {
        int[] allowed = limits.searchMoves;
        if (allowed == null) return true;
        for (int m : allowed) if (m == move) return true;
        return false;
    }

    /** Penalty in centipawns for a root move (RootPenalty option), 0 for most moves. */
    int rootPenalty(int move) {
        int[] moves = limits.penaltyMoves;
        if (moves == null) return 0;
        for (int i = 0; i < moves.length; i++) if (moves[i] == move) return limits.penaltyCp[i];
        return 0;
    }

    /** True when root scores are shifted by penalties (they must not be stored as exact values). */
    boolean rootPenalised() {
        return limits.penaltyMoves != null && limits.penaltyMoves.length > 0;
    }

    public void newGame() {
        tt.clear();
        for (Searcher s : searchers) s.clearHistory();
    }

    long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    long totalNodes() {
        long n = 0;
        for (Searcher s : searchers) n += s.nodes;
        return n;
    }

    long totalTbHits() {
        long n = 0;
        for (Searcher s : searchers) n += s.tbHits;
        return n;
    }

    // ------------------------------------------------------------------ time management

    private void initTime(SearchLimits l, Position root) {
        startNanos = System.nanoTime();
        nodeLimit = l.nodes;
        timeManaged = false;
        instability = 0;
        if (l.movetime > 0) {
            optimumMs = maximumMs = Math.max(1, l.movetime - moveOverhead);
            timeManaged = true;
            return;
        }
        if (!l.hasClock()) return;
        long time = root.side == Position.WHITE ? l.wtime : l.btime;
        long inc = root.side == Position.WHITE ? l.winc : l.binc;
        if (time < 0) return;
        long[] budget = allocate(time, inc, l.movestogo, root.fullmove, moveOverhead);
        optimumMs = budget[0];
        maximumMs = budget[1];
        timeManaged = true;
    }

    /**
     * The budget for one move, {optimum, maximum} in milliseconds. With moves to go, the clock is shared
     * evenly. In sudden death and increment games the share assumes about 40 more moves at the start and
     * fewer as the game goes on, never fewer than 16, so later moves keep a fair share instead of the early
     * ones eating it; the optimum stays under a fifth of the clock, the maximum under 40 %. Under ten
     * seconds a move gets a twentieth of the clock (at most an eighth) plus most of the increment, so a
     * bullet scramble never runs the flag down.
     */
    static long[] allocate(long time, long inc, int movestogo, int fullmove, int moveOverhead) {
        long left = Math.max(1, time - moveOverhead);
        inc = Math.max(0, inc);
        double opt, max;
        if (movestogo > 0) {
            int mtg = Math.min(movestogo, 50);
            opt = (double) left / mtg + inc * 0.75;
            opt = Math.min(opt, left * (mtg == 1 ? 0.8 : 0.6));
            max = Math.min(opt * 3.0, left * 0.8);
        } else {
            int mtg = Math.max(Tune.tmMtgMin, Tune.tmMtg - Math.max(0, fullmove - 1) / 2);
            opt = (double) left / mtg + inc * (Tune.tmInc / 100.0);
            opt = Math.min(opt, left * 0.20);
            max = Math.min(opt * 3.0, left * 0.40);
            if (left < 10_000) {
                opt = Math.min(opt, left / 20.0 + inc * 0.6);
                max = Math.min(max, left / 8.0 + inc * 0.6);
            }
        }
        max = Math.max(max, opt);
        return new long[]{Math.max(1, (long) opt), Math.max(1, (long) max)};
    }

    /** Called by the main search thread periodically. */
    void checkTime(Searcher s) {
        if (stop || s.completedDepth < 1) return;
        if (nodeLimit > 0 && totalNodes() >= nodeLimit) {
            stop = true;
            return;
        }
        if (timeManaged && !pondering && elapsedMs() >= maximumMs) stop = true;
    }

    void onIterationComplete(Searcher s, boolean bestChanged) {
        printInfo(s);
        if (limits.depth > 0 && s.completedDepth >= limits.depth) {
            stop = true;
            return;
        }
        if (Math.abs(s.bestScore) >= Searcher.MATE_BOUND && s.completedDepth >= 10 && !limits.infinite) {
            int mateIn = Searcher.MATE - Math.abs(s.bestScore);
            if (s.completedDepth > 2 * mateIn + 4) {
                stop = true;
                return;
            }
        }
        if (nodeLimit > 0 && totalNodes() >= nodeLimit) {
            stop = true;
            return;
        }
        if (!timeManaged || pondering || limits.movetime > 0) return;
        instability = instability * 0.5 + (bestChanged ? 1.0 : 0.0);
        double factor = 0.75 + instability * 0.6;
        int drop = lastScore - s.bestScore;
        if (s.completedDepth > 4 && drop > 20) factor *= Math.min(1.6, 1.0 + drop / 100.0);
        if (Tune.tmUnc > 0 && rootUncertainty >= 0) {
            // unclear positions get more of the budget, clear ones less (Tune.tmUnc)
            factor *= Math.max(0.6, Math.min(1.6, 1.0 + Tune.tmUnc / 100.0 * (rootUncertainty / 0.04 - 1.0)));
        }
        lastScore = s.bestScore;
        long elapsed = elapsedMs();
        if (elapsed >= optimumMs * factor || elapsed >= maximumMs * 0.9) stop = true;
    }

    private void printInfo(Searcher s) {
        long elapsed = Math.max(1, elapsedMs());
        long nodes = totalNodes();
        StringBuilder sb = new StringBuilder(256);
        sb.append("info depth ").append(s.completedDepth).append(" seldepth ").append(s.selDepth);
        sb.append(" multipv 1 score ").append(formatScore(s.bestScore));
        sb.append(" nodes ").append(nodes).append(" nps ").append(nodes * 1000 / elapsed);
        sb.append(" hashfull ").append(tt.hashfull());
        if (tablebases != null) sb.append(" tbhits ").append(totalTbHits());
        sb.append(" time ").append(elapsed).append(" pv");
        for (int i = 0; i < s.bestPvLength; i++) sb.append(' ').append(Move.toUci(s.bestPv[i]));
        out.accept(sb.toString());
    }

    static String formatScore(int score) {
        if (score >= Searcher.MATE_BOUND) return "mate " + ((Searcher.MATE - score + 1) / 2);
        if (score <= -Searcher.MATE_BOUND) return "mate " + (-(Searcher.MATE + score) / 2);
        return "cp " + score;
    }

    // ------------------------------------------------------------------ control

    public synchronized void go(Position root, SearchLimits l, Consumer<String> output) {
        waitForFinish();
        this.out = output;
        this.limits = l;
        stop = false;
        pondering = l.ponder;
        lastScore = 0;
        initTime(l, root);
        tt.newSearch();

        if (!root.isValid()) {
            // A king is missing or the side not to move is in check: searching would capture a king.
            output.accept("info string invalid position " + root.toFen());
            output.accept("bestmove 0000");
            return;
        }
        int[] legal = new int[256];
        int n = MoveGen.generateAll(root, legal, 0);
        int legalCount = 0, onlyMove = Move.NONE;
        for (int i = 0; i < n; i++) {
            if (root.isLegal(legal[i]) && rootAllowed(legal[i])) {
                legalCount++;
                onlyMove = legal[i];
            }
        }
        if (legalCount == 0) {
            output.accept("info depth 0 score " + (root.inCheck() ? "mate 0" : "cp 0"));
            output.accept("bestmove 0000");
            return;
        }
        if (tablebases != null && !l.infinite && l.depth == 0 && l.searchMoves == null) {
            int[] tb = tablebases.probeRoot(root);
            if (tb != null) {
                int sc = tb[1] > 0 ? Searcher.TB_WIN - 1 : tb[1] < 0 ? -Searcher.TB_WIN + 1 : 0;
                output.accept("info depth 1 score cp " + sc + " nodes 0 tbhits 1 pv " + Move.toUci(tb[0]));
                if (l.ponder || l.infinite) {
                    finishLater(tb[0]);
                } else {
                    output.accept("bestmove " + Move.toUci(tb[0]));
                }
                return;
            }
        }
        if (legalCount == 1 && timeManaged && !l.ponder) {
            optimumMs = Math.min(optimumMs, 50);
            maximumMs = Math.min(maximumMs, 100);
        }

        int maxDepth = l.depth > 0 ? Math.min(l.depth, Searcher.MAX_PLY - 1) : Searcher.MAX_PLY - 1;
        final int fallback = onlyMove;
        // How unclear the root is for the network (expected error of its evaluation as a win probability), reported
        // before bestmove for the bot and the play app; the search itself does not use it.
        julius.game.chessengine.core.nnue.UncertaintyHead head = useNnue
                ? julius.game.chessengine.core.nnue.UncertaintyHead.loadDefault() : null;
        rootUncertainty = head == null ? -1 : head.predict(root);
        final String uncertainty = head == null ? null
                : String.format(java.util.Locale.ROOT, "info string unc %.3f", rootUncertainty);
        threads = new Thread[searchers.length];
        for (int i = 1; i < searchers.length; i++) {
            Searcher s = searchers[i];
            s.pos.copyFrom(root);
            threads[i] = new Thread(() -> s.iterate(maxDepth), "search-" + i);
            threads[i].setDaemon(true);
        }
        Searcher main = searchers[0];
        main.pos.copyFrom(root);
        mainThread = new Thread(() -> {
            for (int i = 1; i < threads.length; i++) threads[i].start();
            main.iterate(maxDepth);
            // Wait for stop/ponderhit in infinite/ponder mode before reporting
            synchronized (finishLock) {
                while ((limits.infinite || pondering) && !stop) {
                    try {
                        finishLock.wait(5);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            stop = true;
            for (int i = 1; i < threads.length; i++) {
                try {
                    threads[i].join();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            int best = main.bestMove != Move.NONE ? main.bestMove : fallback;
            StringBuilder sb = new StringBuilder("bestmove ").append(Move.toUci(best));
            if (main.bestPvLength > 1 && main.bestPv[0] == best) sb.append(" ponder ").append(Move.toUci(main.bestPv[1]));
            if (uncertainty != null) output.accept(uncertainty);
            output.accept(sb.toString());
        }, "search-main");
        mainThread.setDaemon(true);
        mainThread.start();
    }

    private void finishLater(int move) {
        mainThread = new Thread(() -> {
            synchronized (finishLock) {
                while (!stop && (limits.infinite || pondering)) {
                    try {
                        finishLock.wait(5);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            out.accept("bestmove " + Move.toUci(move));
        }, "search-main");
        mainThread.setDaemon(true);
        mainThread.start();
    }

    public void stopSearch() {
        stop = true;
        synchronized (finishLock) {
            finishLock.notifyAll();
        }
    }

    public void ponderhit() {
        startNanos = System.nanoTime();
        pondering = false;
        synchronized (finishLock) {
            finishLock.notifyAll();
        }
    }

    public void waitForFinish() {
        Thread t = mainThread;
        if (t != null) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isSearching() {
        Thread t = mainThread;
        return t != null && t.isAlive();
    }

    /**
     * Single-threaded search on the calling thread (no helper threads, no output); used by data generation.
     * Returns {bestMove, score} with the score from the side to move's perspective.
     */
    public int[] searchInThread(Position root, SearchLimits l) {
        this.out = s -> { };
        this.limits = l;
        stop = false;
        pondering = false;
        lastScore = 0;
        initTime(l, root);
        tt.newSearch();
        Searcher main = searchers[0];
        main.pos.copyFrom(root);
        int maxDepth = l.depth > 0 ? Math.min(l.depth, Searcher.MAX_PLY - 1) : Searcher.MAX_PLY - 1;
        main.iterate(maxDepth);
        return new int[]{main.bestMove, main.bestScore};
    }

    /** Synchronous fixed-limit search used by bench and tools; returns {bestMove, score}. */
    public int[] searchSync(Position root, SearchLimits l) {
        final int[] result = new int[2];
        go(root, l, line -> {
            if (line.startsWith("bestmove")) result[0] = root.parseUciMove(line.split(" ")[1]);
        });
        waitForFinish();
        result[1] = searchers[0].bestScore;
        return result;
    }
}
