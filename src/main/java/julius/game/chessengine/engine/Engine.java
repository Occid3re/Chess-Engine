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
    }

    private static CacheConfig computeCacheConfig() {
        // Heuristic baseline
        long maxHeap = Runtime.getRuntime().maxMemory();          // bytes
        long budget  = Math.max(64L << 20, (maxHeap * 8) / 100);  // >=64MB or 8% of heap
        long estimatedBytesPerEntry = 256;                        // adjust if profiling suggests otherwise

        long computedSize = Math.max(1, budget / estimatedBytesPerEntry);
        int heuristicMaxSize = (int) Math.min(500_000, computedSize);
        int heuristicMaxAgeMs = 86_400_000; // 24h; set <=0 to disable time expiry

        // Overrides via System Properties
        Integer sysMaxSize = getIntSysProp();
        Long sysMaxAgeMs   = getLongSysProp();

        // Fallback to environment variables if system properties absent
        Integer envMaxSize = (sysMaxSize == null) ? getIntEnv() : null;
        Long envMaxAgeMs   = (sysMaxAgeMs == null) ? getLongEnv() : null;

        int maxSize = (sysMaxSize != null)
                ? sysMaxSize
                : (envMaxSize != null)
                ? envMaxSize
                : heuristicMaxSize;
        long maxAge = (sysMaxAgeMs != null)
                ? sysMaxAgeMs
                : (envMaxAgeMs != null)
                ? envMaxAgeMs
                : (long) heuristicMaxAgeMs;

        // Defensive clamps
        if (maxSize <= 0) maxSize = heuristicMaxSize;
        // maxAge can be <=0 to mean "no time-based expiry"

        if (log.isInfoEnabled()) {
            log.info("LegalMovesCache config -> maxSize={}, maxAgeMs={} (heap={}, budget~{}MB, heuristicSize={})",
                    maxSize, maxAge, maxHeap, budget >> 20, heuristicMaxSize);
        }
        return new CacheConfig(maxSize, (int) Math.min(Integer.MAX_VALUE, Math.max(Integer.MIN_VALUE, maxAge)));
    }

    private static Integer getIntSysProp() {
        String v = System.getProperty("chess.cache.maxSize");
        return parseInt(v);
    }

    private static Long getLongSysProp() {
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
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException ignored) { return null; }
    }

    private static Long parseLong(String v) {
        if (v == null || v.isEmpty()) return null;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException ignored) { return null; }
    }

    // --- Cache instance based on computed configuration ---
    private static final CacheConfig CACHE_CFG = computeCacheConfig();
    private TimedLRUCache<MoveList> legalMovesCache = new TimedLRUCache<>(CACHE_CFG.maxSize, CACHE_CFG.maxAgeMs);

    private final Object boardLock = new Object();

    private final Access access;

    @Getter
    private OpeningBook openingBook;

    private boolean legalMovesNeedUpdate = true;
    private MoveList legalMoves;

    @Getter
    private ArrayList<Integer> line = new ArrayList<>();
    private ArrayList<Integer> redoLine = new ArrayList<>();
    @Getter
    private BitBoard bitBoard = new BitBoard();
    @Getter
    private GameState gameState = new GameState(bitBoard);

    private LongConsumer onPositionChanged = _ -> {};


    public Engine() {
        this.access = new Access();
        startNewGame();
    }

    public Engine(Engine other) {
        this.access = new Access();
        synchronized (other.boardLock) {
            this.bitBoard = new BitBoard(other.bitBoard);
            this.gameState = new GameState(other.gameState);
            this.line = new ArrayList<>(other.line);
            this.redoLine = new ArrayList<>(other.redoLine);

            // ❌ heavy: cloning the current legal list and cache
            // this.legalMoves = other.legalMoves == null ? null : new MoveList(other.legalMoves);
            // this.legalMovesNeedUpdate = other.legalMovesNeedUpdate;
            // this.legalMovesCache = new TimedLRUCache<>(CACHE_CFG.maxSize, CACHE_CFG.maxAgeMs);
            // other.legalMovesCache.forEach((k, v) -> this.legalMovesCache.put(k, new MoveList(v)));

            // ✅ light: fresh empty state for the clone; compute lazily when needed
            this.legalMoves = null;
            this.legalMovesNeedUpdate = true;
            this.legalMovesCache = new TimedLRUCache<>(CACHE_CFG.maxSize, CACHE_CFG.maxAgeMs);

            this.openingBook = other.openingBook;
            LongConsumer callback = other.onPositionChanged;
            this.onPositionChanged = (callback != null) ? callback : _ -> {};
        }
    }

    public void setOnPositionChanged(LongConsumer cb) {
        this.onPositionChanged = (cb != null ? cb : _ -> {});
    }
    private void notifyPositionChanged() {
        onPositionChanged.accept(getBoardStateHash());
    }

    /** Expose BitBoard's SEE to callers (AI). */
    public int see(int move) {
        synchronized (boardLock) {
            return seeUnsafe(move);
        }
    }

    public MoveList getAllLegalMoves() {
        synchronized (boardLock) {
            return getAllLegalMovesUnsafe();
        }
    }

    public void performMove(int move) {
        synchronized (boardLock) {
            if (!gameState.isGameOver()) {
                long boardStateHashBeforeMove = getBoardStateHashUnsafe();
                boolean isOpeningMove = openingBook.containsMoveAndBoardStateHash(boardStateHashBeforeMove, move);
                performMoveUnsafe(move, isOpeningMove);
                notifyPositionChanged();
            }
        }
    }

    public void importBoardFromFen(String fen) {
        synchronized (boardLock) {
            importBoardFromFenUnsafe(fen);
            notifyPositionChanged();
        }
    }

    public Engine createSimulation() {
        synchronized (boardLock) {
            // Safe copy
            return new Engine(this);
        }
    }

    public void startNewGame() {
        synchronized (boardLock) {
            startNewGameUnsafe();
            notifyPositionChanged();
        }
    }

    private void generateLegalMoves() {
        synchronized (boardLock) {
            generateLegalMovesUnsafe();
        }
    }

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
        synchronized (boardLock) {
            // Determine the piece type and color from the bitboard based on the 'from' position
            PieceType pieceType = bitBoard.getPieceTypeAtIndex(fromIndex);
            Color color = bitBoard.getPieceColorAtIndex(fromIndex);

            if (pieceType == null || color == null) {
                throw new IllegalStateException("No piece at the starting position");
            }

            // Check if it's the correct player's turn
            Color pieceColor = bitBoard.getPieceColorAtIndex(fromIndex);
            if ((pieceColor == Color.WHITE && !bitBoard.whitesTurn) || (pieceColor == Color.BLACK && bitBoard.whitesTurn)) {
                bitBoard.logBoard();
                throw new IllegalStateException("It's not " + pieceColor + "'s turn");
            }

            int move = getMove(fromIndex, toIndex, promotionPiece);

            if (move == -1) {
                log.warn("Move not legal!");
            } else {
                performMove(move);
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
        synchronized (boardLock) {
            return getBoardStateHashUnsafe();
        }
    }

    public void logBoard() {
        synchronized (boardLock) {
            bitBoard.logBoard();
        }
    }

    public boolean whitesTurn() {
        synchronized (boardLock) {
            return whitesTurnUnsafe();
        }
    }

    // Engine.java
    public int doNullMoveForSearch() {
        // Save current en-passant target (0 means none in your codebase)
        synchronized (boardLock) {
            return doNullMoveForSearchUnsafe();
        }
    }

    // Engine.java
    public void undoNullMoveForSearch(int previousDoubleStep) {
        synchronized (boardLock) {
            undoNullMoveForSearchUnsafe(previousDoubleStep);
        }
    }

    public void undoLastMove() {
        synchronized (boardLock) {
            if (line.isEmpty()) throw new IllegalStateException("undoLastMoveWasNotPossible, line is empty");
            undoLastMoveUnsafe();
            notifyPositionChanged();
        }
    }


    public void redoMove() {
        synchronized (boardLock) {
            if (!redoLine.isEmpty()) {
                performMove(redoLine.getLast());
                redoLine.removeLast();
                notifyPositionChanged();
            } else {
                throw new IllegalStateException("redoLastMoveWasNotPossible, redoLine is empty");
            }
        }
    }

    public Integer getLastMove() {
        if (!line.isEmpty()) {
            return line.getLast();
        } else {
            return -1;
        }
    }

    public FEN translateBoardToFen() {
        synchronized (boardLock) {
            return FEN.translateBoardToFEN(bitBoard, gameState);
        }
    }

    // Engine.java
    public Map<String, String> buildRenderBoard() {
        synchronized (boardLock) {
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
        }
    }


    public boolean isEndgame() {
        synchronized (boardLock) {
            return bitBoard.isEndgame();
        }
    }

    public Access access() {
        return access;
    }

    public final class Access {
        private Access() {
        }

        public MoveList getAllLegalMoves() {
            return getAllLegalMovesUnsafe();
        }

        public void performMove(int move) {
            performMoveUnsafe(move, false);
        }

        public void undoLastMove() {
            undoLastMoveUnsafe();
        }

        public long getBoardStateHash() {
            return getBoardStateHashUnsafe();
        }

        public GameState getGameState() {
            return gameState;
        }

        public BitBoard getBitBoard() {
            return bitBoard;
        }

        public boolean whitesTurn() {
            return whitesTurnUnsafe();
        }

        public int see(int move) {
            return seeUnsafe(move);
        }

        public int doNullMoveForSearch() {
            return doNullMoveForSearchUnsafe();
        }

        public void undoNullMoveForSearch(int previousDoubleStep) {
            undoNullMoveForSearchUnsafe(previousDoubleStep);
        }

        public boolean isEndgame() {
            return bitBoard.isEndgame();
        }
    }

    private int seeUnsafe(int move) {
        return bitBoard.see(move);
    }

    private MoveList getAllLegalMovesUnsafe() {
        if (gameState.isGameOver()) {
            if (legalMoves == null) {
                legalMoves = new MoveList();
            } else {
                legalMoves.clear();
            }
        } else if (legalMovesNeedUpdate) {
            generateLegalMovesUnsafe();
        }
        return legalMoves;
    }

    private void performMoveUnsafe(int move, boolean isOpeningMove) {
        if (gameState.isGameOver()) {
            return;
        }
        bitBoard.performMove(move);
        long newHash = getBoardStateHashUnsafe();
        gameState.recordHash(newHash);
        performMovePostUpdate(move, isOpeningMove);
    }

    private void performMovePostUpdate(int move, boolean isOpeningMove) {
        generateLegalMovesUnsafe();
        gameState.pushHalfmoveClock();
        gameState.update(bitBoard, legalMoves, move, isOpeningMove);
        gameState.getScore().applyMove(bitBoard, move, gameState.getState());
        line.add(move);
    }

    private void importBoardFromFenUnsafe(String fen) {
        this.bitBoard = FEN.translateFENtoBitBoard(fen);
        this.gameState = new GameState(bitBoard);

        gameState.setHalfmoveClock(bitBoard.getHalfmoveClock());
        gameState.setFullmoveNumber(bitBoard.getFullmoveNumber());
        gameState.getHashHistory().clear();
        gameState.getRepetition().clear();
        gameState.recordHash(getBoardStateHashUnsafe());

        if (gameState.isFiftyMoveRule() || gameState.isThreefoldRepetition()) {
            if (legalMoves == null) {
                legalMoves = new MoveList();
            } else {
                legalMoves.clear();
            }
            legalMovesNeedUpdate = false;
            gameState.setState(GameStateEnum.DRAW);
        } else {
            generateLegalMovesUnsafe();
            gameState.updateState(bitBoard, legalMoves, false);
        }
    }

    private void startNewGameUnsafe() {
        bitBoard = new BitBoard();
        gameState = new GameState(bitBoard);
        legalMovesNeedUpdate = true;
        line = new ArrayList<>();
        redoLine = new ArrayList<>();
        this.openingBook = OpeningBook.getInstance();
        legalMovesCache = new TimedLRUCache<>(CACHE_CFG.maxSize, CACHE_CFG.maxAgeMs);
        legalMovesCache.cleanup();
    }

    private void generateLegalMovesUnsafe() {
        final long boardStateHash = getBoardStateHashUnsafe();

        MoveList cached = legalMovesCache.get(boardStateHash);
        if (cached != null) {
            this.legalMoves = new MoveList(cached);
            legalMovesNeedUpdate = false;
            return;
        }

        if (gameState.isGameOver()) {
            this.legalMoves = new MoveList();
            legalMovesNeedUpdate = false;
            return;
        }

        MoveList moves = bitBoard.getAllCurrentPossibleMoves();

        MoveList legal = new MoveList();
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getMove(i);

            bitBoard.performMove(move);
            if (!bitBoard.isInCheck(MoveHelper.isWhitesMove(move))) {
                legal.add(move);
            }
            bitBoard.undoMove(move);
        }

        this.legalMoves = legal;
        legalMovesNeedUpdate = false;
        legalMovesCache.put(boardStateHash, new MoveList(legal));

        int size = legalMovesCache.size();
        if (size > CACHE_CFG.maxSize) {
            throw new RuntimeException(String.format(
                    "LegalMovesCache size %s is larger than configured MAX_SIZE %s", size, CACHE_CFG.maxSize));
        }
    }

    private void undoLastMoveUnsafe() {
        if (line.isEmpty()) {
            throw new IllegalStateException("undoLastMoveWasNotPossible, line is empty");
        }

        Integer undoMove = line.getLast();

        long currentHash = getBoardStateHashUnsafe();
        gameState.removeHash(currentHash);

        this.bitBoard.undoMove(undoMove);

        generateLegalMovesUnsafe();
        gameState.popHalfmoveClock(bitBoard);
        gameState.updateState(bitBoard, legalMoves, false);
        gameState.getScore().undoMove(bitBoard, undoMove, gameState.getState());

        redoLine.add(undoMove);
        line.removeLast();
    }

    private long getBoardStateHashUnsafe() {
        return bitBoard.getBoardStateHash();
    }

    private boolean whitesTurnUnsafe() {
        return bitBoard.whitesTurn;
    }

    private int doNullMoveForSearchUnsafe() {
        int previousDoubleStep = bitBoard.getLastMoveDoubleStepPawnIndex();
        bitBoard.setLastMoveDoubleStepPawnIndex(0);
        bitBoard.flipSideToMove();
        legalMovesNeedUpdate = true;
        gameState.refreshScore(bitBoard);
        return previousDoubleStep;
    }

    private void undoNullMoveForSearchUnsafe(int previousDoubleStep) {
        bitBoard.flipSideToMove();
        bitBoard.setLastMoveDoubleStepPawnIndex(previousDoubleStep);
        legalMovesNeedUpdate = true;
        gameState.refreshScore(bitBoard);
    }
}
