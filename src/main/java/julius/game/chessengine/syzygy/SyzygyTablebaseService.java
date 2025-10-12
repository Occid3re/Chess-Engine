package julius.game.chessengine.syzygy;

import julius.game.chessengine.board.BitBoard;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe Syzygy probe cache. Consumers can feed {@link BitBoard} states and receive
 * cached tablebase answers. The service now forwards bitboards directly to the underlying
 * tablebase client, avoiding intermediate FEN translation overhead.
 */
@Log4j2
public class SyzygyTablebaseService {

    private final ConcurrentMap<SyzygyCacheKey, Optional<SyzygyProbeResult>> cache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SyzygyCacheKey> evictionOrder = new ConcurrentLinkedQueue<>();
    private final AtomicInteger entryCount = new AtomicInteger();
    private final int maxEntries;
    private volatile TablebaseClient client;
    private volatile String configuredDirectories;
    private volatile int configuredMaxPieces;

    public SyzygyTablebaseService(
            @Value("${chessengine.syzygy.paths:${chessengine.syzygy.path:}}") String directories,
            @Value("${chessengine.syzygy.maxPieces:7}") int maxPieces,
            @Value("${chessengine.syzygy.cacheSize:65536}") int cacheSize) {
        this(resolveClient(directories, maxPieces), cacheSize);
        this.configuredDirectories = directories == null ? "" : directories;
        this.configuredMaxPieces = Math.max(1, maxPieces);
    }

    SyzygyTablebaseService(TablebaseClient client, int cacheSize) {
        this.maxEntries = Math.max(1024, cacheSize);
        this.client = client;
        this.configuredDirectories = "";
        this.configuredMaxPieces = 7;
        log.info("Syzygy tablebase cache initialised with capacity {} entries", this.maxEntries);
    }

    private static TablebaseClient resolveClient(String directories, int maxPieces) {
        return FathomTablebaseClient.create(directories, maxPieces).orElseGet(NoopClient::new);
    }

    public Optional<SyzygyProbeResult> probe(BitBoard board) {
        if (board == null) {
            return Optional.empty();
        }
        SyzygyCacheKey key = SyzygyCacheKey.from(board);
        Optional<SyzygyProbeResult> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<SyzygyProbeResult> resolved = client.probe(board)
                .filter(result -> result.wdl() != SyzygyWdl.UNKNOWN || result.dtz().isPresent() || result.dtm().isPresent());
        cacheAndEvict(key, resolved);
        return resolved;
    }

    public synchronized void configure(String directories) {
        configure(directories, this.configuredMaxPieces);
    }

    public synchronized void configure(String directories, int maxPieces) {
        String sanitized = (directories == null) ? "" : directories;
        this.client = resolveClient(sanitized, maxPieces);
        this.configuredDirectories = sanitized;
        this.configuredMaxPieces = Math.max(1, maxPieces);
        cache.clear();
        evictionOrder.clear();
        entryCount.set(0);
        if (client instanceof NoopClient) {
            log.info("Syzygy tablebase probing disabled (directories='{}').", sanitized);
        } else {
            log.info("Syzygy tablebase probing configured (directories='{}', maxPieces={}).",
                    sanitized, this.configuredMaxPieces);
        }
    }

    public void ensureReady() {
        // The current implementation loads tables synchronously when configured, so probing once is
        // sufficient to confirm readiness. Keeping this hook allows future asynchronous backends to
        // surface their own readiness semantics.
    }

    public int getConfiguredMaxPieces() {
        return configuredMaxPieces;
    }

    public String getConfiguredDirectories() {
        return configuredDirectories;
    }

    private void cacheAndEvict(SyzygyCacheKey key, Optional<SyzygyProbeResult> result) {
        Optional<SyzygyProbeResult> existing = cache.putIfAbsent(key, result);
        if (existing != null) {
            cache.replace(key, existing, result);
            return;
        }

        evictionOrder.add(key);
        if (entryCount.incrementAndGet() > maxEntries) {
            evictOverflowEntries();
        }
    }

    private void evictOverflowEntries() {
        while (entryCount.get() > maxEntries) {
            SyzygyCacheKey eldest = evictionOrder.poll();
            if (eldest == null) {
                return;
            }
            if (cache.remove(eldest) != null) {
                entryCount.decrementAndGet();
            }
        }
    }

    private record SyzygyCacheKey(long zobrist, int halfmoveClock, int fullmoveNumber) {

        static SyzygyCacheKey from(BitBoard board) {
            return new SyzygyCacheKey(board.getBoardStateHash(), board.getHalfmoveClock(), board.getFullmoveNumber());
        }
    }

    private static final class NoopClient implements TablebaseClient {
        @Override
        public Optional<SyzygyProbeResult> probe(BitBoard board) {
            return Optional.empty();
        }
    }
}
