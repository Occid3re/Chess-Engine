package julius.game.chessengine.core.tools;

import julius.game.chessengine.core.Move;
import julius.game.chessengine.core.MoveGen;
import julius.game.chessengine.core.Position;
import julius.game.chessengine.core.SearchLimits;
import julius.game.chessengine.core.SearchManager;
import julius.game.chessengine.core.Searcher;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Self-play data generator. Plays fixed-node games from randomised openings and writes
 * {@code fen;whiteScore;wdl} lines (wdl: 2 = white won, 1 = draw, 0 = black won) for quiet positions.
 *
 * <p>Usage: {@code DataGen <out> <threads> <positions> [nodesPerMove] [openings.epd]}</p>
 */
public final class DataGen {

    private static final AtomicLong written = new AtomicLong();
    private static final AtomicLong games = new AtomicLong();

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        int threads = Integer.parseInt(args[1]);
        long target = Long.parseLong(args[2]);
        long nodes = args.length > 3 ? Long.parseLong(args[3]) : 6000;
        List<String> openings = args.length > 4 ? Files.readAllLines(Path.of(args[4])) : List.of();
        long seed = System.nanoTime();
        BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        long start = System.currentTimeMillis();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final long s = seed + 7919L * t;
            Thread th = new Thread(() -> {
                try {
                    run(writer, target, nodes, openings, new SplittableRandom(s));
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
                System.out.printf("positions %d games %d (%d pos/s)%n", written.get(), games.get(), written.get() / el);
            }
        });
        reporter.setDaemon(true);
        reporter.start();
        for (Thread th : workers) th.join();
        writer.close();
        System.out.println("done: " + written.get() + " positions, " + games.get() + " games");
    }

    private static void run(BufferedWriter writer, long target, long nodes, List<String> openings, SplittableRandom rng)
            throws IOException {
        SearchManager manager = new SearchManager(16, 1);
        int[] moves = new int[256];
        List<String> fens = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        while (written.get() < target) {
            boolean fromBook = !openings.isEmpty() && rng.nextInt(4) != 0;
            Position p = fromBook ? new Position(openings.get(rng.nextInt(openings.size())) + " 0 1") : new Position();
            int randomPlies = fromBook ? rng.nextInt(3) : 8 + rng.nextInt(2);
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
            int winPlies = 0, drawPlies = 0;
            for (int ply = 0; ply < 400; ply++) {
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
                l.nodes = nodes;
                int[] r = manager.searchInThread(p, l);
                int best = r[0];
                int score = r[1];
                if (best == Move.NONE) best = moves[0];
                int whiteScore = p.side == Position.WHITE ? score : -score;
                if (Math.abs(score) >= 1500) {
                    if (++winPlies >= 6) {
                        result = whiteScore > 0 ? 2 : 0;
                        break;
                    }
                } else {
                    winPlies = 0;
                }
                if (ply >= 80 && Math.abs(score) <= 8) {
                    if (++drawPlies >= 12) {
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
            if (result < 0) result = 1;
            games.incrementAndGet();
            sb.setLength(0);
            for (int i = 0; i < fens.size(); i++) {
                sb.append(fens.get(i)).append(';').append(scores.get(i)).append(';').append(result).append('\n');
            }
            synchronized (writer) {
                writer.write(sb.toString());
                writer.flush();
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
