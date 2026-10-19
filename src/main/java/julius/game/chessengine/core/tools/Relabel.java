package julius.game.chessengine.core.tools;

import julius.game.chessengine.core.Move;
import julius.game.chessengine.core.Position;
import julius.game.chessengine.core.SearchLimits;
import julius.game.chessengine.core.SearchManager;
import julius.game.chessengine.core.Searcher;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Re-scores {@code fen;score;wdl} positions with this engine's own search: each position gets the score of a
 * fixed-node search, the game result (wdl) is kept. Filtered like {@link DataGen}: positions in check, with a noisy
 * best move or a tablebase/mate score are dropped. Written as packed records ({@link PackData}) in input order.
 *
 * <p>Usage: {@code Relabel <in.txt> <out.bin> <threads> [nodesPerPosition]} (default 6000, as self-play).</p>
 *
 * <p>Used for positions from other sources (e.g. Lichess elite games), so every score the nets learn comes from
 * this engine's own search.</p>
 */
public final class Relabel {

    private Relabel() {
    }

    public static void main(String[] args) throws Exception {
        List<String> lines = Files.readAllLines(Path.of(args[0]));
        Path out = Path.of(args[1]);
        int threads = Integer.parseInt(args[2]);
        long nodes = args.length > 3 ? Long.parseLong(args[3]) : 6000;
        int n = lines.size();
        byte[] records = new byte[n * PackData.RECORD];
        boolean[] keep = new boolean[n];
        AtomicInteger next = new AtomicInteger(), done = new AtomicInteger();
        long start = System.currentTimeMillis();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                SearchManager manager = new SearchManager(16, 1);
                ByteBuffer rec = ByteBuffer.allocate(PackData.RECORD).order(ByteOrder.LITTLE_ENDIAN);
                for (int i; (i = next.getAndIncrement()) < n; done.incrementAndGet()) {
                    String[] f = lines.get(i).split(";");
                    if (f.length < 3) continue;
                    Position p;
                    try {
                        p = new Position(f[0]);
                    } catch (RuntimeException e) {
                        continue;
                    }
                    if (!p.isValid() || p.inCheck()) continue;
                    SearchLimits l = new SearchLimits();
                    l.nodes = nodes;
                    int[] r = manager.searchInThread(p, l);
                    if (r[0] == Move.NONE || Move.isNoisy(r[0]) || Math.abs(r[1]) >= Searcher.TB_BOUND) continue;
                    int whiteScore = p.side == Position.WHITE ? r[1] : -r[1];
                    if (PackData.pack(f[0] + ";" + whiteScore + ";" + f[2], rec)) {
                        System.arraycopy(rec.array(), 0, records, i * PackData.RECORD, PackData.RECORD);
                        keep[i] = true;
                    }
                }
            });
            th.start();
            workers.add(th);
        }
        Thread reporter = new Thread(() -> {
            while (done.get() < n) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    return;
                }
                long el = Math.max(1, (System.currentTimeMillis() - start) / 1000);
                System.out.printf("positions %d / %d (%d pos/s)%n", done.get(), n, done.get() / el);
            }
        });
        reporter.setDaemon(true);
        reporter.start();
        for (Thread th : workers) th.join();
        long written = 0;
        try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
            for (int i = 0; i < n; i++) {
                if (!keep[i]) continue;
                o.write(records, i * PackData.RECORD, PackData.RECORD);
                written++;
            }
        }
        System.out.println("done: " + written + " of " + n + " positions written to " + out);
    }
}
