package julius.game.chessengine.core.tools;

import julius.game.chessengine.core.Move;
import julius.game.chessengine.core.MoveGen;
import julius.game.chessengine.core.Position;
import julius.game.chessengine.core.SearchLimits;
import julius.game.chessengine.core.SearchManager;
import julius.game.chessengine.core.Searcher;
import julius.game.chessengine.core.Tablebases;
import julius.game.chessengine.core.nnue.UncertaintyHead;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Self-play data generator. Plays fixed-node games from randomised openings and writes
 * {@code fen;whiteScore;wdl} lines (wdl: 2 = white won, 1 = draw, 0 = black won) for quiet positions.
 *
 * <p>Usage: {@code DataGen <out> <threads> <positions> [nodesPerMove] [openings.epd]}; an output file ending in
 * {@code .bin} gets packed 32-byte records ({@link PackData}) for the GPU trainer instead of text.</p>
 *
 * <p>Optional system properties (endgame coverage):</p>
 * <ul>
 *   <li>{@code gen.starts=file} – positions ({@code fen} or {@code fen;...} lines) to start games from,
 *       e.g. late middlegames and endgames; {@code gen.startFraction} (default 0.4) of the games use them.</li>
 *   <li>{@code gen.syzygy=paths} – once a game reaches the tables, its result is the tablebase verdict
 *       (and the game continues {@code gen.tbExtraPlies} plies, default 40, to record more endgame positions).
 *       Needs {@code chessengine.syzygy.nativeLibrary}.</li>
 *   <li>{@code gen.winScore}/{@code gen.winPlies} (default 1500/6) and {@code gen.drawPly}/{@code gen.drawPlies}
 *       (default 80/12) – adjudication; higher values play decided games and long draws out further.</li>
 *   <li>{@code gen.nodeJitter} (default 0.1) – node limit varies by +-10 % per move so repeated starts diverge.</li>
 * </ul>
 *
 * <p>Focus on what the network knows least (needs its own uncertainty output, version 4, or its uncertainty head,
 * {@code -Dcore.uncFile} or the bundled one; off by default):</p>
 * <ul>
 *   <li>{@code gen.uncThreshold} (default 0.07) – positions the head rates at least this uncertain (expected error of
 *       the static evaluation as a win probability; 0.07 = the most uncertain ~13 % of self-play positions, whose
 *       actual error is 2.7 times the others').</li>
 *   <li>{@code gen.uncNodes} (default 1) – node multiplier for those positions: their labels come from a deeper
 *       search.</li>
 *   <li>{@code gen.uncFork} (default 0) – share of games that start from such a position seen earlier (each thread
 *       keeps the last 512), with 0-1 random plies: more games around the network's blind spots.</li>
 * </ul>
 */
public final class DataGen {

    private static final AtomicLong written = new AtomicLong();
    private static final AtomicLong games = new AtomicLong();

    /**
     * Node limits vary by +-this share per move: the search is deterministic, so without it a start position or
     * book opening without random plies would replay the identical game (duplicates at large scale).
     */
    private static final double NODE_JITTER = Double.parseDouble(System.getProperty("gen.nodeJitter", "0.1"));
    private static final double START_FRACTION = Double.parseDouble(System.getProperty("gen.startFraction", "0.4"));
    private static final int WIN_SCORE = Integer.getInteger("gen.winScore", 1500);
    private static final int WIN_PLIES = Integer.getInteger("gen.winPlies", 6);
    private static final int DRAW_PLY = Integer.getInteger("gen.drawPly", 80);
    private static final int DRAW_PLIES = Integer.getInteger("gen.drawPlies", 12);
    private static final int TB_EXTRA_PLIES = Integer.getInteger("gen.tbExtraPlies", 40);
    private static final double UNC_THRESHOLD = Double.parseDouble(System.getProperty("gen.uncThreshold", "0.07"));
    private static final double UNC_NODES = Double.parseDouble(System.getProperty("gen.uncNodes", "1"));
    private static final double UNC_FORK = Double.parseDouble(System.getProperty("gen.uncFork", "0"));
    private static final int FORK_POOL = 512;
    private static final AtomicLong uncertain = new AtomicLong();
    private static final AtomicLong searched = new AtomicLong();
    private static List<String> starts = List.of();
    private static Tablebases tablebases;
    private static boolean focus;  // uncertainty focus: the network has an uncertainty output or head

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        int threads = Integer.parseInt(args[1]);
        long target = Long.parseLong(args[2]);
        long nodes = args.length > 3 ? Long.parseLong(args[3]) : 6000;
        List<String> openings = args.length > 4 ? Files.readAllLines(Path.of(args[4])) : List.of();
        String startFile = System.getProperty("gen.starts");
        if (startFile != null) {
            starts = Files.readAllLines(Path.of(startFile));
            System.out.println("start positions: " + starts.size() + " (fraction " + START_FRACTION + ")");
        }
        String tbPaths = System.getProperty("gen.syzygy");
        if (tbPaths != null) {
            tablebases = Tablebases.load(tbPaths, System.out::println);
        }
        if (UNC_NODES > 1 || UNC_FORK > 0) {
            focus = UncertaintyHead.available();
            System.out.println(!focus ? "uncertainty focus OFF: no uncertainty output or head for this network"
                    : String.format(java.util.Locale.ROOT, "uncertainty focus: >= %.3f gets %.1fx nodes, %.0f %% of games start there",
                    UNC_THRESHOLD, UNC_NODES, 100 * UNC_FORK));
        }
        long seed = System.nanoTime();
        BufferedWriter writer = null;
        if (out.toString().endsWith(".bin")) {
            binOut = new java.io.BufferedOutputStream(Files.newOutputStream(out, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND), 1 << 20);
        } else {
            writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        final BufferedWriter textOut = writer;
        long start = System.currentTimeMillis();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final long s = seed + 7919L * t;
            Thread th = new Thread(() -> {
                try {
                    run(textOut, target, nodes, openings, new SplittableRandom(s));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            th.start();
            workers.add(th);
        }
        Thread reporter = new Thread(() -> {
            while (written.get() < target) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    return;
                }
                long el = Math.max(1, (System.currentTimeMillis() - start) / 1000);
                String share = !focus ? "" : String.format(java.util.Locale.ROOT, " uncertain %.1f %%",
                        100.0 * uncertain.get() / Math.max(1, searched.get()));
                System.out.printf("positions %d games %d (%d pos/s)%s%n", written.get(), games.get(),
                        written.get() / el, share);
            }
        });
        reporter.setDaemon(true);
        reporter.start();
        for (Thread th : workers) th.join();
        if (writer != null) writer.close();
        if (binOut != null) binOut.close();
        System.out.println("done: " + written.get() + " positions, " + games.get() + " games");
    }

    /** Packed output (.bin), or null for text. */
    private static java.io.OutputStream binOut;

    private static void run(BufferedWriter writer, long target, long nodes, List<String> openings, SplittableRandom rng)
            throws IOException {
        java.nio.ByteBuffer rec = java.nio.ByteBuffer.allocate(PackData.RECORD).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        java.io.ByteArrayOutputStream records = new java.io.ByteArrayOutputStream();
        SearchManager manager = new SearchManager(16, 1);
        int[] moves = new int[256];
        ArrayDeque<String> forks = new ArrayDeque<>();
        List<String> fens = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        while (written.get() < target) {
            Position p;
            int randomPlies;
            if (!forks.isEmpty() && rng.nextDouble() < UNC_FORK) {
                // a position the network was unsure about, picked at random from the pool
                String fen = forks.toArray(new String[0])[rng.nextInt(forks.size())];
                p = new Position(fen);
                randomPlies = rng.nextInt(2);
            } else if (!starts.isEmpty() && rng.nextDouble() < START_FRACTION) {
                String line = starts.get(rng.nextInt(starts.size()));
                int semi = line.indexOf(';');
                try {
                    p = new Position(semi > 0 ? line.substring(0, semi) : line);
                } catch (RuntimeException e) {
                    continue;
                }
                if (!p.isValid()) continue;
                randomPlies = rng.nextInt(3);
            } else {
                boolean fromBook = !openings.isEmpty() && rng.nextInt(4) != 0;
                p = fromBook ? new Position(openings.get(rng.nextInt(openings.size())) + " 0 1") : new Position();
                randomPlies = fromBook ? rng.nextInt(3) : 8 + rng.nextInt(2);
            }
            boolean ok = true;
            for (int i = 0; i < randomPlies; i++) {
                int n = legalMoves(p, moves);
                if (n == 0) {
                    ok = false;
                    break;
                }
                p.makeMove(moves[rng.nextInt(n)]);
            }
            if (!ok || legalMoves(p, moves) == 0) continue;
            manager.newGame();
            fens.clear();
            scores.clear();
            int result = -1; // white-perspective wdl
            int tbResult = -1, tbEntryPly = 0;
            int winPlies = 0, drawPlies = 0;
            for (int ply = 0; ply < 400; ply++) {
                if (tablebases != null && tbResult < 0 && p.castling == 0 && Long.bitCount(p.all) <= tablebases.largest()) {
                    int[] probe = tablebases.probeRoot(p);
                    if (probe != null) {
                        int stm = probe[1];
                        tbResult = stm == 0 ? 1 : ((stm > 0) == (p.side == Position.WHITE) ? 2 : 0);
                        tbEntryPly = ply;
                    }
                }
                if (tbResult >= 0 && ply - tbEntryPly >= TB_EXTRA_PLIES) break;
                int n = legalMoves(p, moves);
                if (n == 0) {
                    result = p.inCheck() ? (p.side == Position.WHITE ? 0 : 2) : 1;
                    break;
                }
                if (p.halfmove >= 100 || p.isInsufficientMaterial() || p.isRepetition()) {
                    result = 1;
                    break;
                }
                SearchLimits l = new SearchLimits();
                double factor = 1;
                if (focus) {
                    searched.incrementAndGet();
                    if (UncertaintyHead.estimate(p) >= UNC_THRESHOLD) {
                        uncertain.incrementAndGet();
                        factor = UNC_NODES;
                        if (UNC_FORK > 0 && rng.nextInt(4) == 0) {  // a sample, neighbours are alike
                            if (forks.size() >= FORK_POOL) forks.pollFirst();
                            forks.addLast(p.toFen());
                        }
                    }
                }
                l.nodes = Math.max(1, Math.round(factor * nodes * (1 - NODE_JITTER + 2 * NODE_JITTER * rng.nextDouble())));
                int[] r = manager.searchInThread(p, l);
                int best = r[0];
                int score = r[1];
                if (best == Move.NONE) best = moves[0];
                int whiteScore = p.side == Position.WHITE ? score : -score;
                if (tbResult < 0 && Math.abs(score) >= WIN_SCORE) {
                    if (++winPlies >= WIN_PLIES) {
                        result = whiteScore > 0 ? 2 : 0;
                        break;
                    }
                } else {
                    winPlies = 0;
                }
                if (tbResult < 0 && ply >= DRAW_PLY && Math.abs(score) <= 8) {
                    if (++drawPlies >= DRAW_PLIES) {
                        result = 1;
                        break;
                    }
                } else {
                    drawPlies = 0;
                }
                if (!p.inCheck() && !Move.isNoisy(best) && Math.abs(score) < Searcher.TB_BOUND) {
                    fens.add(p.toFen());
                    scores.add(whiteScore);
                }
                p.makeMove(best);
            }
            if (tbResult >= 0) result = tbResult; // the theoretical value beats how the game happened to end
            if (result < 0) result = 1;
            games.incrementAndGet();
            if (binOut != null) {
                records.reset();
                for (int i = 0; i < fens.size(); i++) {
                    if (PackData.pack(fens.get(i) + ";" + scores.get(i) + ";" + result, rec)) records.write(rec.array());
                }
                synchronized (binOut) {
                    records.writeTo(binOut);
                    binOut.flush();
                }
            } else {
                sb.setLength(0);
                for (int i = 0; i < fens.size(); i++) {
                    sb.append(fens.get(i)).append(';').append(scores.get(i)).append(';').append(result).append('\n');
                }
                synchronized (writer) {
                    writer.write(sb.toString());
                    writer.flush();
                }
            }
            written.addAndGet(fens.size());
        }
    }

    private static int legalMoves(Position p, int[] buf) {
        int n = MoveGen.generateAll(p, buf, 0);
        int w = 0;
        for (int i = 0; i < n; i++) {
            if (p.isLegal(buf[i])) buf[w++] = buf[i];
        }
        return w;
    }
}
