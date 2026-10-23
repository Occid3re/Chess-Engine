package julius.game.chessengine.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * UCI front end for the core engine. Runs without Spring; reads stdin on the calling thread
 * and searches on background threads.
 */
public final class Uci {

    public static final String NAME = "Alieknek 4.2.1";

    static final String[] BENCH_FENS = {
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "r1bq1rk1/pp2bppp/2n2n2/3p4/3P4/2NBPN2/PP3PPP/R2QK2R w KQ - 0 9",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
            "2r3k1/5p1p/p1q3r1/2Pp4/1P1Rp2n/1bN3R1/B4PPP/Q5K1 b - - 5 30",
            "rnbqkb1r/pp1p1ppp/4pn2/2p5/2PP4/2N5/PP2PPPP/R1BQKBNR w KQkq - 0 4",
            "r2q1rk1/pp3pbp/2b1p1p1/n7/2nP4/1PN3Q1/P1B1NPPP/R1B2RK1 b - - 2 16",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "r3rk2/1p3p1p/2p2pp1/p7/P2P3P/2Pb2P1/1P1N1P2/R3R1K1 w - - 2 22",
            "4rrk1/pp1n3p/3q2pQ/2p1pb2/2PP4/2P3N1/P2B2PP/4RRK1 b - - 7 19",
            "r1bbk1nr/pp3p1p/2n5/1N4p1/2Np1B2/8/PPP2PPP/2KR1B1R w kq - 0 13",
            "8/8/1p6/1P1k4/8/4K3/8/8 w - - 0 1",
            "6k1/5ppp/8/8/8/8/1R3PPP/6K1 w - - 0 1",
            "r1b1k2r/ppppnppp/2n2q2/2b5/3NP3/2P1B3/PP3PPP/RN1QKB1R w KQkq - 0 1",
            "3r1rk1/p4ppp/1p2pn2/2q5/2P5/1P2B3/P3QPPP/3R1RK1 w - - 0 18",
            "8/5pk1/6p1/1p1P4/1Pr2P2/6P1/5K2/3R4 w - - 0 45",
    };

    private final java.util.function.Consumer<String> out;
    private final SearchManager manager;
    private final Position position = new Position();
    private String syzygyPath = "";
    /** RootPenalty option, applied to the next "go" (empty = none). */
    private String rootPenalty = "";

    public Uci(PrintStream out) {
        this(out::println);
    }

    /** UCI front end writing its output lines to {@code out} (stdout, or e.g. a WebSocket session). */
    public Uci(java.util.function.Consumer<String> out) {
        this.out = out;
        int hash = Integer.getInteger("chessengine.tt.mb", Integer.getInteger("core.hash", 64));
        int threads = Integer.getInteger("chessengine.threads", Integer.getInteger("core.threads", 1));
        this.manager = new SearchManager(hash, threads);
        this.manager.setMoveOverhead(Integer.getInteger("chessengine.moveOverhead", 30));
        String tb = System.getProperty("chessengine.syzygy.paths", "");
        if (!tb.isBlank()) {
            syzygyPath = tb;
            manager.setTablebases(Tablebases.load(tb, this::send));
        }
    }

    public static void main(String[] args) throws IOException {
        PrintStream ps = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
        Uci uci = new Uci(ps);
        if (args.length > 0 && args[0].equals("bench")) {
            uci.bench(args.length > 1 ? Integer.parseInt(args[1]) : 13);
            return;
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (!uci.handle(line.trim())) break;
        }
        uci.manager.stopSearch();
    }

    private synchronized void send(String s) {
        out.accept(s);
    }

    /** Stops a running search and waits for it; used when the client goes away. */
    public void close() {
        manager.stopSearch();
        manager.waitForFinish();
    }

    public boolean handle(String line) {
        if (line.isEmpty()) return true;
        String[] t = line.split("\\s+");
        switch (t[0]) {
            case "uci" -> {
                send("id name " + NAME);
                send("id author Julius");
                send("option name Hash type spin default 64 min 1 max 65536");
                send("option name Threads type spin default 1 min 1 max 256");
                send("option name Move Overhead type spin default 30 min 0 max 5000");
                send("option name SyzygyPath type string default <empty>");
                send("option name Ponder type check default false");
                send("option name UseNNUE type check default true");
                send("option name Contempt type spin default 0 min -200 max 200");
                send("option name RootPenalty type string default <empty>");
                send("option name UCI_Chess960 type check default false");
                if (Tune.enabled()) {
                    for (Tune.Param p : Tune.params()) {
                        send("option name " + p.name() + " type spin default " + p.defaultValue()
                                + " min " + p.min() + " max " + p.max());
                    }
                }
                send("uciok");
            }
            case "isready" -> send("readyok");
            case "ucinewgame" -> {
                manager.stopSearch();
                manager.waitForFinish();
                manager.newGame();
            }
            case "setoption" -> setOption(line);
            case "position" -> setPosition(t);
            case "go" -> go(t);
            case "stop" -> manager.stopSearch();
            case "ponderhit" -> manager.ponderhit();
            case "quit" -> {
                return false;
            }
            case "d" -> send(position.toFen());
            case "eval" -> {
                Eval e = new Eval(false);
                send("hce eval (white) " + e.evaluateWhite(position) + "  (side to move) " + e.evaluate(position));
                julius.game.chessengine.core.nnue.Network net = julius.game.chessengine.core.nnue.Network.loadDefault();
                if (net != null) {
                    julius.game.chessengine.core.nnue.Accumulators a = julius.game.chessengine.core.nnue.Accumulators.create(net, 1);
                    a.refresh(position, 0);
                    send("nnue eval (side to move) " + a.evaluate(position, 0) + (manager.useNnue ? " [active]" : " [disabled]")
                            + " (" + julius.game.chessengine.core.nnue.Accumulators.backend() + ")");
                    double u = julius.game.chessengine.core.nnue.UncertaintyHead.estimate(position);
                    if (u >= 0) send(String.format(java.util.Locale.ROOT, "nnue uncertainty %.4f", u));
                }
            }
            case "perft" -> {
                int d = t.length > 1 ? Integer.parseInt(t[1]) : 5;
                long st = System.nanoTime();
                long n = MoveGen.perft(position, d);
                long ms = Math.max(1, (System.nanoTime() - st) / 1_000_000);
                send("perft " + d + " nodes " + n + " time " + ms + " nps " + n * 1000 / ms);
            }
            case "bench" -> bench(t.length > 1 ? Integer.parseInt(t[1]) : 13);
            default -> send("info string unknown command " + t[0]);
        }
        return true;
    }

    private void setOption(String line) {
        int ni = line.indexOf("name ");
        if (ni < 0) return;
        int vi = line.indexOf(" value ");
        String name = (vi > 0 ? line.substring(ni + 5, vi) : line.substring(ni + 5)).trim();
        String value = vi > 0 ? line.substring(vi + 7).trim() : "";
        manager.stopSearch();
        manager.waitForFinish();
        switch (name.toLowerCase()) {
            case "hash" -> manager.setHash(Integer.parseInt(value));
            case "threads" -> manager.setThreads(Integer.parseInt(value));
            case "move overhead" -> manager.setMoveOverhead(Integer.parseInt(value));
            case "usennue" -> manager.useNnue = Boolean.parseBoolean(value);
            case "contempt" -> manager.setContempt(Integer.parseInt(value));
            case "uci_chess960" -> manager.chess960 = Boolean.parseBoolean(value);
            // "e2e4:25 d2d4:10" (or "none"): root moves that must be this many centipawns better to be chosen
            case "rootpenalty" -> rootPenalty = value.equalsIgnoreCase("none") || value.equals("<empty>") ? "" : value;
            case "syzygypath" -> {
                if (!value.equals(syzygyPath)) {
                    syzygyPath = value;
                    manager.setTablebases(Tablebases.load(value, this::send));
                }
            }
            default -> {
                // Tuning parameters (only numeric ones); anything else, e.g. "Ponder false", is ignored.
                try {
                    if (!value.isEmpty() && Tune.set(name, Integer.parseInt(value))) {
                        send("info string " + name + " = " + value);
                    }
                } catch (NumberFormatException ignored) {
                    // not a tuning parameter
                }
            }
        }
    }

    private void setPosition(String[] t) {
        int i = 1;
        if (t.length > 1 && t[1].equals("startpos")) {
            position.setFen(Position.START_FEN);
            i = 2;
        } else if (t.length > 1 && t[1].equals("fen")) {
            StringBuilder fen = new StringBuilder();
            i = 2;
            while (i < t.length && !t[i].equals("moves")) fen.append(t[i++]).append(' ');
            position.setFen(fen.toString());
        }
        if (i < t.length && t[i].equals("moves")) {
            for (i++; i < t.length; i++) {
                int m = position.parseUciMove(t[i], manager.chess960);
                if (m == Move.NONE) {
                    send("info string illegal move " + t[i]);
                    break;
                }
                position.makeMove(m);
            }
        }
    }

    private void go(String[] t) {
        SearchLimits l = new SearchLimits();
        for (int i = 1; i < t.length; i++) {
            switch (t[i]) {
                case "wtime" -> l.wtime = Long.parseLong(t[++i]);
                case "btime" -> l.btime = Long.parseLong(t[++i]);
                case "winc" -> l.winc = Long.parseLong(t[++i]);
                case "binc" -> l.binc = Long.parseLong(t[++i]);
                case "movestogo" -> l.movestogo = Integer.parseInt(t[++i]);
                case "depth" -> l.depth = Integer.parseInt(t[++i]);
                case "nodes" -> l.nodes = Long.parseLong(t[++i]);
                case "movetime" -> l.movetime = Long.parseLong(t[++i]);
                case "infinite" -> l.infinite = true;
                case "ponder" -> l.ponder = true;
                case "searchmoves" -> {
                    java.util.List<Integer> list = new java.util.ArrayList<>();
                    while (i + 1 < t.length && !GO_KEYWORDS.contains(t[i + 1])) {
                        int m = position.parseUciMove(t[++i], manager.chess960);
                        if (m != Move.NONE) list.add(m);
                    }
                    if (!list.isEmpty()) l.searchMoves = list.stream().mapToInt(Integer::intValue).toArray();
                }
                default -> {
                }
            }
        }
        applyRootPenalty(l);
        if (!l.hasClock() && l.movetime == 0 && l.depth == 0 && l.nodes == 0) l.infinite = true;
        manager.go(new Position(position), l, this::send);
    }

    private static final java.util.Set<String> GO_KEYWORDS = java.util.Set.of("wtime", "btime", "winc", "binc",
            "movestogo", "depth", "nodes", "mate", "movetime", "infinite", "ponder", "searchmoves");

    /** Resolves the RootPenalty option ("e2e4:25 d2d4:10") against the position about to be searched. */
    private void applyRootPenalty(SearchLimits l) {
        if (rootPenalty.isEmpty()) return;
        java.util.List<int[]> list = new java.util.ArrayList<>();
        for (String item : rootPenalty.split("[\\s,]+")) {
            int colon = item.indexOf(':');
            if (colon <= 0) continue;
            try {
                int m = position.parseUciMove(item.substring(0, colon), manager.chess960);
                int cp = Math.max(0, Math.min(1000, Integer.parseInt(item.substring(colon + 1))));
                if (m != Move.NONE && cp > 0) list.add(new int[]{m, cp});
            } catch (NumberFormatException ignored) {
                // a malformed entry is skipped
            }
        }
        if (list.isEmpty()) return;
        l.penaltyMoves = list.stream().mapToInt(e -> e[0]).toArray();
        l.penaltyCp = list.stream().mapToInt(e -> e[1]).toArray();
    }

    private void bench(int depth) {
        long totalNodes = 0;
        long st = System.nanoTime();
        manager.newGame();
        for (String fen : BENCH_FENS) {
            Position p = new Position(fen);
            manager.newGame();
            manager.go(p, SearchLimits.depth(depth), s -> { });
            manager.waitForFinish();
            totalNodes += manager.totalNodes();
        }
        long ms = Math.max(1, (System.nanoTime() - st) / 1_000_000);
        if (Boolean.getBoolean("core.verifyNnue")) send("info string verified " + Searcher.VERIFIED.get() + " NNUE evaluations");
        send("bench: " + totalNodes + " nodes " + (totalNodes * 1000 / ms) + " nps " + ms + " ms");
    }
}
