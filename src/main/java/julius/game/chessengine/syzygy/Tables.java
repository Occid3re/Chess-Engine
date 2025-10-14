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
        Optional<SyzygyMove> recommendedMove = Optional.empty();
        if (SyzygyConstants.winDrawLoss(dtzRaw) == wdlValue) {
            int distance = SyzygyConstants.distanceToZero(dtzRaw);
            dtz = OptionalInt.of(distance);
            recommendedMove = decodeRecommendedMove(dtzRaw);
        } else {
            log.debug("Syzygy DTZ probe mismatch (wdlValue={}, dtzRaw={})", wdlValue, dtzRaw);
        }

        return Optional.of(new SyzygyProbeResult(wdl, dtz, OptionalInt.empty(), recommendedMove));
    }

    private Optional<SyzygyMove> decodeRecommendedMove(int dtzRaw) {
        int fromIndex = toEngineIndex(SyzygyConstants.fromSquare(dtzRaw));
        int toIndex = toEngineIndex(SyzygyConstants.toSquare(dtzRaw));
        if (fromIndex < 0 || toIndex < 0) {
            return Optional.empty();
        }

        int promotionBits = switch (SyzygyConstants.promoteInto(dtzRaw)) {
            case SyzygyConstants.TB_PROMOTES_QUEEN -> 5;
            case SyzygyConstants.TB_PROMOTES_ROOK -> 4;
            case SyzygyConstants.TB_PROMOTES_BISHOP -> 3;
            case SyzygyConstants.TB_PROMOTES_KNIGHT -> 2;
            default -> 0;
        };

        try {
            return Optional.of(new SyzygyMove(fromIndex, toIndex, promotionBits));
        } catch (IllegalArgumentException ex) {
            log.debug("Ignoring invalid Syzygy move suggestion (dtzRaw={}, from={}, to={}, promo={})",
                    dtzRaw, fromIndex, toIndex, promotionBits, ex);
            return Optional.empty();
        }
    }

    /**
     * Syzygy squares are encoded with {@code a8 = 1} increasing by file then rank.
     * The engine however uses {@code a1 = 0}. This helper mirrors the rank so the
     * returned index matches {@link julius.game.chessengine.board.MoveHelper}'s layout.
     */
    static int toEngineIndex(int syzygySquare) {
        if (syzygySquare <= 0) {
            return -1;
        }
        int zeroBased = syzygySquare - 1;
        int file = zeroBased & 7; // modulo 8
        int rankFromTop = zeroBased >>> 3; // divide by 8 with a8 == 0
        int engineRank = 7 - rankFromTop;
        if (engineRank < 0 || engineRank >= 8) {
            return -1;
        }
        return engineRank * 8 + file;
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
