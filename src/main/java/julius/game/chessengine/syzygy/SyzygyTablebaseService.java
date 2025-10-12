package julius.game.chessengine.syzygy;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.utils.Color;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe Syzygy probe cache. Consumers can feed {@link BitBoard} states and receive
 * cached tablebase answers. The service translates the engine specific representation into
 * the FEN strings expected by standard Syzygy backends.
 */
@Service
@Log4j2
public class SyzygyTablebaseService {

    private final ConcurrentMap<SyzygyCacheKey, Optional<SyzygyProbeResult>> cache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SyzygyCacheKey> evictionOrder = new ConcurrentLinkedQueue<>();
    private final int maxEntries;
    private volatile TablebaseClient client;
    private volatile String configuredDirectories;
    private volatile int configuredMaxPieces;

    public SyzygyTablebaseService(
            @Value("${chessengine.syzygy.paths:}") String directories,
            @Value("${chessengine.syzygy.maxPieces:7}") int maxPieces,
            @Value("${chessengine.syzygy.cacheSize:65536}") int cacheSize) {
        this(resolveClient(directories == null ? "" : directories, maxPieces), cacheSize);
        this.configuredDirectories = directories == null ? "" : directories;
        this.configuredMaxPieces = Math.max(1, maxPieces);
    }

    SyzygyTablebaseService(TablebaseClient client, int cacheSize) {
        this.maxEntries = Math.max(1024, cacheSize);
        this.client = client;
        this.configuredDirectories = "";
        this.configuredMaxPieces = 7;
        if (client instanceof NoopClient) {
            log.info("Syzygy tablebase client disabled. Configure 'chessengine.syzygy.paths' to enable probing.");
        } else {
            log.info("Syzygy tablebase client initialised with cache size {}", this.maxEntries);
        }
    }

    private static TablebaseClient resolveClient(String directories, int maxPieces) {
        return ChesslibSyzygyClient.tryCreate(directories, maxPieces)
                .map(TablebaseClient.class::cast)
                .orElseGet(NoopClient::new);
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
        String fen = toFen(board);
        Optional<SyzygyProbeResult> resolved = client.probe(fen)
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
        if (client instanceof NoopClient) {
            log.info("Syzygy tablebase client disabled. Configure 'chessengine.syzygy.paths' to enable probing.");
        } else {
            log.info("Syzygy tablebase client initialised with cache size {}", this.maxEntries);
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
        cache.put(key, result);
        evictionOrder.add(key);
        while (evictionOrder.size() > maxEntries) {
            SyzygyCacheKey eldest = evictionOrder.poll();
            if (eldest == null) {
                break;
            }
            cache.remove(eldest);
        }
    }

    private String toFen(BitBoard board) {
        StringBuilder fen = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int index = rank * 8 + file;
                var pieceType = board.getPieceTypeAtIndex(index);
                if (pieceType == null) {
                    empty++;
                    continue;
                }
                if (empty > 0) {
                    fen.append(empty);
                    empty = 0;
                }
                Color color = board.getPieceColorAtIndex(index);
                char notation = pieceType.getNotation();
                fen.append(color == Color.WHITE ? notation : Character.toLowerCase(notation));
            }
            if (empty > 0) {
                fen.append(empty);
            }
            if (rank > 0) {
                fen.append('/');
            }
        }
        fen.append(' ');
        fen.append(board.isWhitesTurn() ? 'w' : 'b');
        fen.append(' ');
        fen.append(buildCastlingAvailability(board));
        fen.append(' ');
        int enPassant = board.getEnPassantTargetIndex();
        fen.append(enPassant < 0 ? '-' : MoveHelper.convertIndexToString(enPassant));
        fen.append(' ');
        fen.append(Math.max(0, board.getHalfmoveClock()));
        fen.append(' ');
        fen.append(Math.max(1, board.getFullmoveNumber()));
        return fen.toString();
    }

    private String buildCastlingAvailability(BitBoard board) {
        StringBuilder castling = new StringBuilder();
        if (!board.isWhiteKingMoved() && !board.isWhiteRookH1Moved()) {
            castling.append('K');
        }
        if (!board.isWhiteKingMoved() && !board.isWhiteRookA1Moved()) {
            castling.append('Q');
        }
        if (!board.isBlackKingMoved() && !board.isBlackRookH8Moved()) {
            castling.append('k');
        }
        if (!board.isBlackKingMoved() && !board.isBlackRookA8Moved()) {
            castling.append('q');
        }
        return castling.length() == 0 ? "-" : castling.toString();
    }

    private record SyzygyCacheKey(long zobrist, int halfmoveClock, int fullmoveNumber) {

        static SyzygyCacheKey from(BitBoard board) {
            return new SyzygyCacheKey(board.getBoardStateHash(), board.getHalfmoveClock(), board.getFullmoveNumber());
        }
    }

    private static final class NoopClient implements TablebaseClient {
        @Override
        public Optional<SyzygyProbeResult> probe(String fen) {
            return Optional.empty();
        }
    }
}
