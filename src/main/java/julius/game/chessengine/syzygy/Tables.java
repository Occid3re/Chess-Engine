package julius.game.chessengine.syzygy;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.BitBoard.MoveGenResult;
import julius.game.chessengine.board.BitBoard.PinState;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.syzygy.bridge.SyzygyBridge;
import julius.game.chessengine.syzygy.bridge.SyzygyConstants;
import lombok.extern.log4j.Log4j2;

import java.util.Optional;
import java.util.OptionalInt;

@Log4j2
final class Tables {

    private static final int MAX_DTM_DEPTH = 600;

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

        return probeNative(board).map(data -> {
            OptionalInt dtm = computeDtm(new BitBoard(board), data);
            return new SyzygyProbeResult(data.wdl(), data.dtz(), dtm, data.recommendedMove());
        });
    }

    private Optional<ProbeData> probeNative(BitBoard board) {
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

        return Optional.of(new ProbeData(wdl, dtz, recommendedMove));
    }

    private Optional<SyzygyMove> decodeRecommendedMove(int dtzRaw) {
        int fromSquare = SyzygyConstants.fromSquare(dtzRaw);
        int toSquare = SyzygyConstants.toSquare(dtzRaw);
        if (fromSquare <= 0 || toSquare <= 0) {
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
            return Optional.of(new SyzygyMove(fromSquare - 1, toSquare - 1, promotionBits));
        } catch (IllegalArgumentException ex) {
            log.debug("Ignoring invalid Syzygy move suggestion (dtzRaw={}, from={}, to={}, promo={})",
                    dtzRaw, fromSquare, toSquare, promotionBits, ex);
            return Optional.empty();
        }
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

    private OptionalInt computeDtm(BitBoard board, ProbeData rootData) {
        SyzygyWdl wdl = rootData.wdl();
        if (wdl == SyzygyWdl.UNKNOWN || wdl == SyzygyWdl.DRAW) {
            return OptionalInt.empty();
        }

        boolean whiteToMove = board.isWhitesTurn();
        boolean winningWhite = wdl.score() > 0 ? whiteToMove : !whiteToMove;

        Long2IntOpenHashMap memo = new Long2IntOpenHashMap();
        memo.defaultReturnValue(Integer.MIN_VALUE);
        LongOpenHashSet visiting = new LongOpenHashSet();
        Long2ObjectOpenHashMap<ProbeData> probeCache = new Long2ObjectOpenHashMap<>();
        long rootKey = board.getBoardStateHash();
        probeCache.put(rootKey, rootData);

        int distance = computeMateDistance(board, rootData, winningWhite, memo, visiting, probeCache, 0);
        if (distance == Integer.MAX_VALUE) {
            return OptionalInt.empty();
        }

        int signedDistance = wdl.score() > 0 ? distance : -distance;
        return OptionalInt.of(signedDistance);
    }

    private int computeMateDistance(BitBoard board,
                                    ProbeData probeData,
                                    boolean winningWhite,
                                    Long2IntOpenHashMap memo,
                                    LongOpenHashSet visiting,
                                    Long2ObjectOpenHashMap<ProbeData> probeCache,
                                    int depth) {
        if (depth >= MAX_DTM_DEPTH) {
            return Integer.MAX_VALUE;
        }

        long key = board.getBoardStateHash();
        int cached = memo.getOrDefault(key, Integer.MIN_VALUE);
        if (cached != Integer.MIN_VALUE) {
            return cached;
        }

        if (!visiting.add(key)) {
            return Integer.MAX_VALUE;
        }

        boolean whiteToMove = board.isWhitesTurn();
        MoveGenResult generation = board.generateAllPossibleMovesWithPins(whiteToMove);
        IntArrayList moves = generation.moves();
        PinState pinState = generation.pinState();

        if (moves.isEmpty()) {
            visiting.remove(key);
            int terminal = board.isInCheck(whiteToMove) ? 0 : Integer.MAX_VALUE;
            memo.put(key, terminal);
            return terminal;
        }

        if (probeData != null && probeData.recommendedMove().isPresent()) {
            prioritizeRecommendedMove(moves, probeData.recommendedMove().get());
        }

        boolean currentSideIsWinner = whiteToMove == winningWhite;
        int best = currentSideIsWinner ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        boolean hasFiniteChild = false;
        boolean foundLegalMove = false;

        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getInt(i);
            if (!board.isMoveLegalFast(move, pinState)) {
                continue;
            }
            foundLegalMove = true;

            board.performMove(move);
            long childKey = board.getBoardStateHash();
            ProbeData childProbe = probeCache.get(childKey);
            if (childProbe == null) {
                Optional<ProbeData> resolved = probeNative(board);
                if (resolved.isPresent()) {
                    childProbe = resolved.get();
                    probeCache.put(childKey, childProbe);
                }
            }

            if (childProbe != null) {
                boolean nextSideIsWinner = board.isWhitesTurn() == winningWhite;
                int childScore = childProbe.wdl().score();
                boolean maintainsResult = (nextSideIsWinner && childScore > 0)
                        || (!nextSideIsWinner && childScore < 0);
                if (maintainsResult) {
                    int childDistance = computeMateDistance(board, childProbe, winningWhite,
                            memo, visiting, probeCache, depth + 1);
                    if (childDistance != Integer.MAX_VALUE) {
                        hasFiniteChild = true;
                        int candidate = childDistance + 1;
                        if (currentSideIsWinner) {
                            if (candidate < best) {
                                best = candidate;
                            }
                        } else {
                            if (candidate > best) {
                                best = candidate;
                            }
                        }
                    }
                }
            }

            board.undoMove(move);

            if (currentSideIsWinner && best == 1) {
                // Can't improve over mate in one for the winning side.
                break;
            }
        }

        visiting.remove(key);

        if (!foundLegalMove) {
            int terminal = board.isInCheck(whiteToMove) ? 0 : Integer.MAX_VALUE;
            memo.put(key, terminal);
            return terminal;
        }

        int result = hasFiniteChild ? best : Integer.MAX_VALUE;
        memo.put(key, result);
        return result;
    }

    private void prioritizeRecommendedMove(IntArrayList moves, SyzygyMove recommendation) {
        int size = moves.size();
        for (int i = 0; i < size; i++) {
            int candidate = moves.getInt(i);
            if (MoveHelper.deriveFromIndex(candidate) == recommendation.fromIndex()
                    && MoveHelper.deriveToIndex(candidate) == recommendation.toIndex()
                    && MoveHelper.derivePromotionPieceTypeBits(candidate) == recommendation.promotionPieceTypeBits()) {
                if (i != 0) {
                    int first = moves.getInt(0);
                    moves.set(0, candidate);
                    moves.set(i, first);
                }
                break;
            }
        }
    }

    private record ProbeData(SyzygyWdl wdl, OptionalInt dtz, Optional<SyzygyMove> recommendedMove) {
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
