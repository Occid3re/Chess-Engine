package julius.game.chessengine.syzygy;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.syzygy.bridge.SyzygyBridge;
import julius.game.chessengine.syzygy.bridge.SyzygyConstants;
import lombok.extern.log4j.Log4j2;

import java.util.Optional;
import java.util.OptionalInt;

@Log4j2
final class Tables {

    private final int maxPieces;
    private final int supportedPieces;
    private final String configuredPaths;

    private Tables(String configuredPaths, int maxPieces, int supportedPieces) {
        this.configuredPaths = configuredPaths;
        this.maxPieces = maxPieces;
        this.supportedPieces = supportedPieces;
    }

    static Optional<Tables> load(String directories, int maxPieces) {
        if (directories == null || directories.isBlank()) {
            log.info("Syzygy tablebase path not configured; probing disabled.");
            return Optional.empty();
        }
        if (!SyzygyBridge.isLibLoaded()) {
            log.warn("Syzygy native library is not loaded; tablebases will remain disabled.");
            return Optional.empty();
        }
        Optional<String> sanitized = SyzygyPathResolver.sanitize(directories);
        if (sanitized.isEmpty()) {
            log.warn("No valid Syzygy directories configured. Provided value: '{}'", directories);
            return Optional.empty();
        }
        int supported = SyzygyBridge.load(sanitized.get());
        if (supported <= 0) {
            log.warn("Failed to load Syzygy tablebases from {}", sanitized.get());
            return Optional.empty();
        }
        int normalizedMaxPieces = Math.max(1, maxPieces);
        log.info("Syzygy tablebases ready (directories={}, supportedPieces={}, configuredMaxPieces={})",
                sanitized.get(), supported, normalizedMaxPieces);
        return Optional.of(new Tables(sanitized.get(), normalizedMaxPieces, supported));
    }

    Optional<SyzygyProbeResult> probe(BitBoard board) {
        if (board == null) {
            return Optional.empty();
        }
        int pieceCount = Long.bitCount(board.getAllPieces());
        int limit = effectiveMaxPieces();
        if (pieceCount > limit) {
            log.debug("Skipping Syzygy probe: {} pieces exceeds configured limit {} (paths={})",
                    pieceCount, limit, configuredPaths);
            return Optional.empty();
        }

        long white = board.getWhitePieces();
        long black = board.getBlackPieces();
        long kings = board.getWhiteKing() | board.getBlackKing();
        long queens = board.getWhiteQueens() | board.getBlackQueens();
        long rooks = board.getWhiteRooks() | board.getBlackRooks();
        long bishops = board.getWhiteBishops() | board.getBlackBishops();
        long knights = board.getWhiteKnights() | board.getBlackKnights();
        long pawns = board.getWhitePawns() | board.getBlackPawns();
        int epIndex = board.getEnPassantTargetIndex();
        int epSquare = epIndex >= 0 ? epIndex + 1 : 0;
        boolean whiteToMove = board.isWhitesTurn();

        int wdlValue = SyzygyBridge.probeSyzygyWDL(white, black, kings, queens, rooks, bishops, knights, pawns, epSquare, whiteToMove);
        SyzygyWdl wdl = toWdl(wdlValue);
        if (wdl == SyzygyWdl.UNKNOWN) {
            log.debug("Syzygy probe returned unknown WDL value {} for board {}", wdlValue, board);
            return Optional.empty();
        }

        int dtzRaw = SyzygyBridge.probeSyzygyDTZ(white, black, kings, queens, rooks, bishops, knights, pawns,
                board.getHalfmoveClock(), epSquare, whiteToMove);
        OptionalInt dtz = OptionalInt.empty();
        if (SyzygyConstants.winDrawLoss(dtzRaw) == wdlValue) {
            int distance = SyzygyConstants.distanceToZero(dtzRaw);
            dtz = OptionalInt.of(distance);
        } else {
            log.debug("Syzygy DTZ probe mismatch (wdlValue={}, dtzRaw={})", wdlValue, dtzRaw);
        }

        return Optional.of(new SyzygyProbeResult(wdl, dtz, OptionalInt.empty()));
    }

    int effectiveMaxPieces() {
        if (supportedPieces <= 0) {
            return Math.max(1, maxPieces);
        }
        return Math.min(Math.max(1, maxPieces), supportedPieces);
    }

    int supportedPieces() {
        return supportedPieces;
    }

    private static SyzygyWdl toWdl(int wdlValue) {
        return switch (wdlValue) {
            case SyzygyConstants.TB_LOSS -> SyzygyWdl.LOSS;
            case SyzygyConstants.TB_BLESSED_LOSS -> SyzygyWdl.BLESSED_LOSS;
            case SyzygyConstants.TB_DRAW -> SyzygyWdl.DRAW;
            case SyzygyConstants.TB_CURSED_WIN -> SyzygyWdl.CURSED_WIN;
            case SyzygyConstants.TB_WIN -> SyzygyWdl.WIN;
            default -> SyzygyWdl.UNKNOWN;
        };
    }
}
