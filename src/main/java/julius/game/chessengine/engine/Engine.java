package julius.game.chessengine.engine;

import julius.game.chessengine.ai.OpeningBook;
import julius.game.chessengine.board.*;
import julius.game.chessengine.cache.TimedLRUCache;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.utils.Color;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import static julius.game.chessengine.board.MoveHelper.convertIndexToString;

@Service
@Log4j2
public class Engine {

    /*
     * Adaptive cache configuration
     * ---------------------------
     * By default we:
     *  - Allocate ~8% of max heap (min 64MB) for the legal-moves cache.
     *  - Assume ~256 bytes per entry (heuristic), clamped between 50k and 500k entries.
     *  - Use a long TTL (24h). Legal move lists for a board state don't "age" by wall-clock,
     *    so eviction should primarily be LRU. You can disable time expiry by setting TTL <= 0.
     *
     * Overrides (priority: System Property > Env Var > Heuristic):
     *  - chess.cache.maxSize (int)
     *  - chess.cache.maxAgeMs (long)
     *  - CHESS_CACHE_MAX_SIZE (int)
     *  - CHESS_CACHE_MAX_AGE_MS (long)
     *
     * This makes it easy to run multiple app versions with different cache sizing.
     */
    private record CacheConfig(int maxSize, int maxAgeMs) {
        static CacheConfig of(int maxSize, int maxAgeMs) {
            return new CacheConfig(maxSize, maxAgeMs);
        }
    }

    private static CacheConfig computeCacheConfig() {
        // 1) Explicit overrides
        Integer sysInt = getIntProp();
        Long sysLong = getLongProp();
        Integer envInt = getIntEnv();
        Long envLong = getLongEnv();

        Integer overrideMax = (sysInt != null ? sysInt : envInt);
        Long overrideAge = (sysLong != null ? sysLong : envLong);

        // 2) Heuristic baseline
        long maxHeap = Runtime.getRuntime().maxMemory();
        long targetBytes = Math.max((long) (maxHeap * 0.08), 64L * 1024 * 1024); // at least 64MB
        // assume ~256 bytes per entry
        int heuristicMax = (int) Math.max(50_000, Math.min(targetBytes / 256, 500_000));
        int maxSize = (overrideMax != null) ? overrideMax : heuristicMax;

        int defaultTtlMs = 24 * 60 * 60 * 1000;
        int maxAgeMs = (overrideAge != null) ? (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, overrideAge)) : defaultTtlMs;

        return CacheConfig.of(maxSize, maxAgeMs);
    }

    private static Integer getIntProp() {
        String v = System.getProperty("chess.cache.maxSize");
        return parseInt(v);
    }

    private static Long getLongProp() {
        String v = System.getProperty("chess.cache.maxAgeMs");
        return parseLong(v);
    }

    private static Integer getIntEnv() {
        String v = System.getenv("CHESS_CACHE_MAX_SIZE");
        return parseInt(v);
    }

    private static Long getLongEnv() {
        String v = System.getenv("CHESS_CACHE_MAX_AGE_MS");
        return parseLong(v);
    }

    private static Integer parseInt(String v) {
        if (v == null || v.isEmpty()) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long parseLong(String v) {
        if (v == null || v.isEmpty()) return null;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // --- Cache instance based on computed configuration ---
    private static final CacheConfig CACHE_CFG = computeCacheConfig();

    // May be null when caching disabled
    private TimedLRUCache<MoveList> legalMovesCache;

    private final Object boardLock = new Object();

    // Feature flag: disable LRU cache and synchronization for lightweight, thread-confined search engines
    private final boolean useMoveCache;

    // Scratch buffer reused when caching/sync is disabled.
    // IMPORTANT: We never expose this buffer to callers; we publish snapshots instead.
    private final MoveList scratchMoveList;

    @Getter
    private OpeningBook openingBook;

    private boolean legalMovesNeedUpdate = true;

    // Current position's legal moves (always a fresh per-position list; callers get a snapshot copy).
    private MoveList legalMoves;

    @Getter
    private ArrayList<Integer> line = new ArrayList<>();
    private ArrayList<Integer> redoLine = new ArrayList<>();
    @Getter
    private BitBoard bitBoard = new BitBoard();
    @Getter
    private GameState gameState = new GameState(bitBoard);

    private LongConsumer onPositionChanged = _ -> {
    };

    // --- Constructors ---

    public Engine() {
        this(true);
    }

    public Engine(boolean useMoveCache) {
        this.useMoveCache = useMoveCache;
        this.scratchMoveList = useMoveCache ? null : new MoveList();
        startNewGame();
    }

    public Engine(Engine other) {
        this(other, other.useMoveCache);
    }

    private Engine(Engine other, boolean useMoveCache) {
        this.useMoveCache = useMoveCache;
        this.scratchMoveList = useMoveCache ? null : new MoveList();
        withBoardLock(other.boardLock, () -> {
            this.bitBoard = new BitBoard(other.bitBoard);
            this.gameState = new GameState(other.gameState);
            this.line = new ArrayList<>(other.line);
            this.redoLine = new ArrayList<>(other.redoLine);

            // Fresh state for the clone: compute lazily
            this.legalMoves = null;
            this.legalMovesNeedUpdate = true;
            this.legalMovesCache = useMoveCache ? new TimedLRUCache<>(CACHE_CFG.maxSize, CACHE_CFG.maxAgeMs) : null;

            this.openingBook = other.openingBook;
            if (useMoveCache) {
                LongConsumer callback = other.onPositionChanged;
                this.onPositionChanged = (callback != null) ? callback : _ -> {
                };
            } else {
                this.onPositionChanged = _ -> {
                };
            }
        });
    }

    // --- Lock helpers (no sync when useMoveCache == false) ---

    private void withBoardLock(Runnable action) {
        if (useMoveCache) {
            synchronized (boardLock) {
                action.run();
            }
        } else {
            action.run();
        }
    }

    private static void withBoardLock(Object lock, Runnable action) {
        synchronized (lock) {
            action.run();
        }
    }

    private <T> T withBoardLock(Supplier<T> action) {
        if (useMoveCache) {
            synchronized (boardLock) {
                return action.get();
            }
        }
        return action.get();
    }

    // --- Lifecycle / notifications ---

    public void setOnPositionChanged(LongConsumer cb) {
        this.onPositionChanged = (cb != null ? cb : _ -> {
        });
    }

    private void notifyPositionChanged() {
        onPositionChanged.accept(getBoardStateHash());
    }

    private void startNewGameLocked() {
        bitBoard = new BitBoard();
        gameState = new GameState(bitBoard);
        legalMovesNeedUpdate = true;
        line = new ArrayList<>();
        redoLine = new ArrayList<>();
        this.openingBook = OpeningBook.getInstance();

        if (useMoveCache) {
            legalMovesCache = new TimedLRUCache<>(CACHE_CFG.maxSize, CACHE_CFG.maxAgeMs);
            legalMovesCache.cleanup();
        } else {
            legalMovesCache = null;
            if (scratchMoveList != null) scratchMoveList.clear();
        }

        // Publish a clean per-position list.
        legalMoves = new MoveList();

        notifyPositionChanged();
    }

    public void startNewGame() {
        withBoardLock(this::startNewGameLocked);
    }

    // --- SEE passthrough ---

    /**
     * Expose BitBoard's SEE to callers (AI).
     */
    public int see(int move) {
        return withBoardLock(() -> bitBoard.see(move));
    }

    // --- Legal-move access (returns a stable snapshot) ---

    public MoveList getAllLegalMoves() {
        return withBoardLock(() -> {
            if (gameState.isGameOver()) {
                // Ensure we publish an empty list for terminal nodes
                if (legalMoves == null || legalMoves.size() != 0) {
                    legalMoves = new MoveList();
                }
            } else if (legalMovesNeedUpdate) {
                generateLegalMovesInternal();
            }
            // Return a snapshot so callers can sort/iterate safely
            return new MoveList(legalMoves);
        });
    }

    // --- Move making ---

    public void performMove(int move) {
        withBoardLock(() -> performMoveLocked(move));
    }

    private void performMoveLocked(int move) {
        if (gameState.isGameOver()) return;

        boolean isOpeningMove = false;
        long boardStateHashBeforeMove = bitBoard.getBoardStateHash();

        bitBoard.performMove(move);
        long newHash = bitBoard.getBoardStateHash();
        gameState.recordHash(newHash);

        if (openingBook.containsMoveAndBoardStateHash(boardStateHashBeforeMove, move)) {
            isOpeningMove = true;
        }

        generateLegalMovesInternal();
        gameState.pushHalfmoveClock();
        gameState.update(bitBoard, legalMoves, move, isOpeningMove);
        gameState.getScore().applyMove(bitBoard, move, gameState.getState());
        line.add(move);
        notifyPositionChanged();
    }

    public void importBoardFromFen(String fen) {
        withBoardLock(() -> {
            this.bitBoard = FEN.translateFENtoBitBoard(fen);
            this.gameState = new GameState(bitBoard);

            gameState.setHalfmoveClock(bitBoard.getHalfmoveClock());
            gameState.setFullmoveNumber(bitBoard.getFullmoveNumber());
            gameState.getHashHistory().clear();
            gameState.getRepetition().clear();
            gameState.recordHash(bitBoard.getBoardStateHash());

            if (gameState.isFiftyMoveRule() || gameState.isThreefoldRepetition()) {
                // terminal draw – publish empty move list
                legalMoves = new MoveList();
                legalMovesNeedUpdate = false;
                gameState.setState(GameStateEnum.DRAW);
            } else {
                generateLegalMovesInternal();
                gameState.updateState(bitBoard, legalMoves, false);
            }
            notifyPositionChanged();
        });
    }

    /**
     * Creates a lightweight, thread-confined clone for search:
     * - caching disabled
     * - no synchronization
     * - uses an internal scratch list for generation, but always publishes snapshots
     */
    public Engine createSimulation() {
        return withBoardLock(() -> new Engine(this, false));
    }

    // --- Core move generation ---

    private void generateLegalMovesInternal() {
        final long boardStateHash = bitBoard.getBoardStateHash();

        // Cache fast-path (only when enabled)
        if (useMoveCache && legalMovesCache != null) {
            MoveList cached = legalMovesCache.get(boardStateHash);
            if (cached != null) {
                this.legalMoves = new MoveList(cached); // publish fresh instance
                legalMovesNeedUpdate = false;
                return;
            }
        }

        if (gameState.isGameOver()) {
            this.legalMoves = new MoveList(); // fresh empty
            legalMovesNeedUpdate = false;

            if (useMoveCache && legalMovesCache != null) {
                legalMovesCache.put(boardStateHash, new MoveList(this.legalMoves));
            }
            return;
        }

        // Generate pseudo-legal moves
        MoveList pseudo = bitBoard.getAllCurrentPossibleMoves();

        // Build into a buffer that we DO NOT expose to callers.
        // When caching is disabled, reuse scratchMoveList to avoid allocations;
        // otherwise use a new list since we’ll likely cache a snapshot anyway.
        MoveList target = (useMoveCache ? new MoveList() : scratchMoveList);
        target.clear();

        for (int i = 0; i < pseudo.size(); i++) {
            int move = pseudo.getMove(i);
            bitBoard.performMove(move);
            if (!bitBoard.isInCheck(MoveHelper.isWhitesMove(move))) {
                target.add(move);
            }
            bitBoard.undoMove(move);
        }

        // Publish as a fresh per-position list so readers can never see in-place mutations.
        this.legalMoves = new MoveList(target);
        legalMovesNeedUpdate = false;

        // Populate cache with an immutable snapshot when enabled.
        if (useMoveCache && legalMovesCache != null) {
            legalMovesCache.put(boardStateHash, new MoveList(this.legalMoves));
            int size = legalMovesCache.size();
            if (size > CACHE_CFG.maxSize) {
                throw new RuntimeException(String.format(
                        "LegalMovesCache size %s is larger than configured MAX_SIZE %s", size, CACHE_CFG.maxSize));
            }
        }
    }

    // --- Misc helpers used by UI and search ---

    public void moveRandomFigure(boolean isWhite) {
        MoveList moves = getAllLegalMoves();
        if (moves.size() == 0) {
            throw new RuntimeException("No moves possible for " + (isWhite ? "White" : "Black"));
        }
        Random rand = new Random();
        int randomMove = moves.getMove(rand.nextInt(moves.size()));
        performMove(randomMove);
    }

    // always queen
    public void moveFigure(int fromIndex, int toIndex) {
        moveFigure(bitBoard, fromIndex, toIndex, 5);
    }

    public void moveFigure(BitBoard bitBoard, int fromIndex, int toIndex, int promotionPiece) {
        int move = getMove(fromIndex, toIndex, promotionPiece);
        if (move != -1) {
            performMove(move);
        } else {
            // special case: if it's a promotion, try explicit promotion piece
            if (promotionPiece != 0) {
                int promMove = getMove(fromIndex, toIndex, promotionPiece);
                if (promMove != -1) {
                    performMove(promMove);
                } else {
                    log.warn("No legal move found from {} to {} (promotion {}), ignoring.",
                            fromIndex, toIndex, promotionPiece);
                }
            } else {
                log.warn("No legal move found from {} to {}, ignoring.", fromIndex, toIndex);
            }
        }
    }

    private int getMove(int fromIndex, int toIndex, int promotionPiece) {
        MoveList legalMoves = getAllLegalMoves();

        int move = -1;

        for (int i = 0; i < legalMoves.size(); i++) {
            int m = legalMoves.getMove(i);
            int from = MoveHelper.deriveFromIndex(m); // Extract the first 6 bits
            int to = MoveHelper.deriveToIndex(m);     // Extract the next 6 bits
            int promotionPieceTypeBits = MoveHelper.derivePromotionPieceTypeBits(m);

            if (from == fromIndex && to == toIndex && (promotionPieceTypeBits == 0 || promotionPieceTypeBits == promotionPiece)) {
                move = m;
            }
        }
        return move;
    }

    public long getBoardStateHash() {
        return withBoardLock(() -> bitBoard.getBoardStateHash());
    }

    public void logBoard() {
        withBoardLock(bitBoard::logBoard);
    }

    public boolean whitesTurn() {
        return withBoardLock(() -> bitBoard.whitesTurn);
    }

    // Null move (search)

    public int doNullMoveForSearch() {
        return withBoardLock(() -> {
            int previousDoubleStep = bitBoard.getLastMoveDoubleStepPawnIndex();

            // EP is not valid across a null move: clear it
            bitBoard.setLastMoveDoubleStepPawnIndex(0);

            // Flip side to move
            bitBoard.flipSideToMove();

            // Mark move list stale so the next search ply regenerates for the new side.
            legalMovesNeedUpdate = true;

            gameState.refreshScore(bitBoard);

            return previousDoubleStep;
        });
    }

    public void undoNullMoveForSearch(int previousDoubleStep) {
        withBoardLock(() -> {
            // Flip side back
            bitBoard.flipSideToMove();

            // Restore EP target
            bitBoard.setLastMoveDoubleStepPawnIndex(previousDoubleStep);

            // Mark stale again so we rebuild for the restored side.
            legalMovesNeedUpdate = true;

            gameState.refreshScore(bitBoard);
        });
    }

    public void undoLastMove() {
        withBoardLock(() -> {
            if (line.isEmpty()) {
                if (useMoveCache) {
                    throw new IllegalStateException("undoLastMoveWasNotPossible, line is empty");
                } else {
                    // Simulation mode: be tolerant to mis-paired undo during pruning.
                    // Just recompute state to keep evaluation consistent and return.
                    legalMovesNeedUpdate = true;
                    gameState.refreshScore(bitBoard);
                    return;
                }
            }

            Integer undoMove = line.getLast();

            long currentHash = bitBoard.getBoardStateHash();
            gameState.removeHash(currentHash);

            // 1) Undo on the board
            this.bitBoard.undoMove(undoMove);

            // 2) Recompute legal moves
            generateLegalMovesInternal();
            gameState.popHalfmoveClock(bitBoard);
            gameState.updateState(bitBoard, legalMoves, false);
            gameState.getScore().undoMove(bitBoard, undoMove, gameState.getState());

            // 3) Bookkeeping
            redoLine.add(undoMove);
            line.removeLast();
            notifyPositionChanged();
        });
    }

    public void redoMove() {
        withBoardLock(() -> {
            if (!redoLine.isEmpty()) {
                performMove(redoLine.getLast());
                redoLine.removeLast();
                notifyPositionChanged();
            } else {
                throw new IllegalStateException("redoLastMoveWasNotPossible, redoLine is empty");
            }
        });
    }

    public Integer getLastMove() {
        if (!line.isEmpty()) {
            return line.getLast();
        } else {
            return -1;
        }
    }

    public FEN translateBoardToFen() {
        return withBoardLock(() -> FEN.translateBoardToFEN(bitBoard, gameState));
    }

    public Map<String, String> buildRenderBoard() {
        return withBoardLock(() -> {
            Map<String, String> map = new HashMap<>(64);
            for (int i = 0; i < 64; i++) {
                PieceType t = bitBoard.getPieceTypeAtIndex(i);
                if (t == null) continue;
                Color c = bitBoard.getPieceColorAtIndex(i);
                String sq = convertIndexToString(i);
                String code =
                        (c == Color.WHITE ? "w" : "b") +
                                switch (t) {
                                    case PAWN -> "P";
                                    case KNIGHT -> "N";
                                    case BISHOP -> "B";
                                    case ROOK -> "R";
                                    case QUEEN -> "Q";
                                    case KING -> "K";
                                };
                map.put(sq, code);
            }

            int epIdx = bitBoard.getEnPassantTargetIndex();
            if (epIdx >= 0) {
                map.remove(convertIndexToString(epIdx));
            }

            return map;
        });
    }

    public boolean isEndgame() {
        return withBoardLock(() -> bitBoard.isEndgame());
    }
}
