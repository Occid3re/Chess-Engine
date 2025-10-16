package julius.game.chessengine.engine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.ai.OpeningBook;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.utils.Color;
import julius.game.chessengine.utils.MoveStack;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.concurrent.atomic.LongAdder;

import static julius.game.chessengine.board.MoveHelper.convertIndexToString;

@Service
@Log4j2
public class Engine {

    private static final boolean VERIFY_LEGAL_MOVES = Boolean.getBoolean("chess.verify.movegen");

    private final Object boardLock = new Object();

    @Getter
    private OpeningBook openingBook;

    private boolean legalMovesNeedUpdate = true;
    private long cachedLegalMovesHash = Long.MIN_VALUE;
    private int[] cachedLegalMoves = new int[MOVE_BUFFER_CAPACITY];
    private int cachedLegalMoveCount = 0;

    private static final int MOVE_BUFFER_CAPACITY = 256;
    private final IntArrayList pseudoMoveBuffer = new IntArrayList(MOVE_BUFFER_CAPACITY);
    private int[] legalMoveScratch = new int[MOVE_BUFFER_CAPACITY];

    @Getter
    private MoveStack line = new MoveStack();
    private MoveStack redoLine = new MoveStack();

    /**
     * Note: Exposing BitBoard via getter allows reads but also enables callers to bypass the lock and mutate internals.
     * If you keep this, document that external users must synchronize on boardLock before touching it.
     */
    @Getter
    private BitBoard bitBoard = new BitBoard();

    @Getter
    private GameState gameState = new GameState(bitBoard);

    private volatile LongConsumer onPositionChanged = _ -> {};
    private volatile TablebaseResult lastTablebaseResult;

    public Engine() {
        startNewGame();
    }

    public Engine(Engine other) {
        synchronized (other.boardLock) {
            this.bitBoard = new BitBoard(other.bitBoard);
            this.gameState = new GameState(other.gameState);
            this.line = new MoveStack(other.line);
            this.redoLine = new MoveStack(other.redoLine);

            // Fresh empty state for the clone; legal moves will be computed lazily when needed.
            resetCachedLegalMoves();
            markLegalMovesStale();

            this.openingBook = other.openingBook;

            // Intentionally DO NOT copy the onPositionChanged observer; clones are silent by default.
            // Users can opt in via setOnPositionChanged on the clone.
        }
        updateLastTablebaseResult();
    }

    public void setOnPositionChanged(LongConsumer cb) {
        this.onPositionChanged = (cb != null ? cb : _ -> {});
    }

    /** Invoke observer outside boardLock. */
    private void notifyPositionChanged(long hash) {
        updateLastTablebaseResult();
        LongConsumer cb = this.onPositionChanged; // read volatile once
        try {
            cb.accept(hash);
        } catch (Throwable t) {
            log.warn("onPositionChanged callback threw", t);
        }
    }

    /**
     * Expose BitBoard's SEE to callers (AI).
     */
    public int see(int move) {
        synchronized (boardLock) {
            return bitBoard.see(move);
        }
    }

    public int see(int move, int margin) {
        synchronized (boardLock) {
            return bitBoard.see(move, margin);
        }
    }

    public IntArrayList getAllLegalMoves() {
        synchronized (boardLock) {
            long boardHash = getBoardStateHash();
            if (legalMovesNeedUpdate || cachedLegalMovesHash != boardHash) {
                // Gate on terminality only (checkmate, stalemate, 50-move, threefold).
                if (gameState.isTerminal()) {
                    IntArrayList empty = new IntArrayList(0);
                    cacheLegalMoves(boardHash, 0);
                    return empty;
                }
                return generateLegalMoves();
            }
            MoveGenerationProfiler.recordCacheHit();
            return copyCachedLegalMoves();
        }
    }

    public boolean hasAnyCaptureOrPromotion() {
        synchronized (boardLock) {
            if (gameState.isTerminal()) {
                return false;
            }
            return bitBoard.hasAnyCaptureOrPromotion();
        }
    }

    public void performMove(int move) {
        long notifyHash; // sentinel: only notify if we actually set it

        synchronized (boardLock) {
            // Guard: never move from a terminal node
            if (gameState.isTerminal()) {
                throw new IllegalStateException("performMove called on terminal node");
            }

            if (!redoLine.isEmpty()) {
                redoLine.clear();
            }

            boolean isOpeningMove = false;
            long boardStateHashBeforeMove = getBoardStateHash();

            // 1) Apply on board
            bitBoard.performMove(move);

            // 2) Repetition/Zobrist bookkeeping
            long newHash = getBoardStateHash();
            gameState.recordHash(newHash);

            // 3) Opening book hint
            OpeningBook book = resolveOpeningBook();
            if (book != null && book.containsMoveAndBoardStateHash(boardStateHashBeforeMove, move)) {
                isOpeningMove = true;
            }

            // 4) Recompute legal moves & update state/score
            IntArrayList legalMoves = generateLegalMoves();
            gameState.pushHalfmoveClock();
            gameState.update(bitBoard, legalMoves, move, isOpeningMove);
            gameState.getScore().applyMove(bitBoard, move, gameState.getState());
            gameState.captureTablebaseState();
            updateLastTablebaseResult();

            // 5) Line/history
            line.push(move);

            // Defer notify until after releasing the lock
            notifyHash = newHash;
        }

        if (notifyHash != Long.MIN_VALUE) {
            notifyPositionChanged(notifyHash);
        }
    }

    public void importBoardFromFen(String fen) {
        long notifyHash;

        synchronized (boardLock) {
            this.bitBoard = FEN.translateFENtoBitBoard(fen);
            this.gameState = new GameState(bitBoard);

            // Reset history to make this FEN the new root.
            gameState.setHalfmoveClock(bitBoard.getHalfmoveClock());
            gameState.setFullmoveNumber(bitBoard.getFullmoveNumber());
            gameState.getHashHistory().clear();
            gameState.getRepetition().clear();
            gameState.recordHash(getBoardStateHash());

            // If you auto-claim 50-move / threefold, these are terminal draws.
            if (gameState.isFiftyMoveRule() || gameState.isThreefoldRepetition()) {
                cacheLegalMoves(getBoardStateHash(), 0);
                gameState.setState(GameStateEnum.DRAW);
                // Still surface the UI/eval hint if material is insufficient.
                gameState.setDrawByInsufficientMaterial(bitBoard.hasInsufficientMaterial());
            } else {
                // Otherwise recompute normally; stalemate (terminal) or insufficient (non-terminal) will be detected here.
                IntArrayList legalMoves = generateLegalMoves();
                gameState.updateState(bitBoard, legalMoves, false);
            }

            gameState.captureTablebaseState();
            updateLastTablebaseResult();

            notifyHash = getBoardStateHash();
        }

        notifyPositionChanged(notifyHash);
    }


    public Engine createSimulation() {
        synchronized (boardLock) {
            // Safe copy
            return new Engine(this);
        }
    }

    public void copyFrom(Engine other) {
        Objects.requireNonNull(other, "other engine");
        long notifyHash;

        synchronized (boardLock) {
            synchronized (other.boardLock) {
                this.bitBoard = new BitBoard(other.bitBoard);
                this.gameState = new GameState(other.gameState);
                this.line = new MoveStack(other.line);
                this.redoLine = new MoveStack(other.redoLine);
                resetCachedLegalMoves();
                markLegalMovesStale();
                this.openingBook = other.openingBook;
            }
            notifyHash = getBoardStateHash();
        }

        notifyPositionChanged(notifyHash);
    }

    public void startNewGame() {
        long notifyHash;
        synchronized (boardLock) {
            bitBoard = new BitBoard();
            gameState = new GameState(bitBoard);
            markLegalMovesStale();
            line = new MoveStack();
            redoLine = new MoveStack();
            OpeningBook instance = OpeningBook.getInstance();
            if (instance != null) openingBook = instance;
            resetCachedLegalMoves();

            gameState.getHashHistory().clear();
            gameState.getRepetition().clear();
            gameState.recordHash(getBoardStateHash());
            updateLastTablebaseResult();

            notifyHash = getBoardStateHash();
        }
        notifyPositionChanged(notifyHash);
    }

    private OpeningBook resolveOpeningBook() {
        if (openingBook == null) {
            OpeningBook instance = OpeningBook.getInstance();
            if (instance != null) {
                openingBook = instance;
            }
        }
        return openingBook;
    }

    private IntArrayList generateLegalMoves() {
        synchronized (boardLock) {
            final long boardStateHash = getBoardStateHash();
            final long profilerStart = MoveGenerationProfiler.onGenerationStart();

            // 1) fill pseudo buffer + pins once
            IntArrayList pseudo = pseudoMoveBuffer;
            pseudo.clear();
            BitBoard.PinState pinState = bitBoard.generateAllPossibleMovesInto(bitBoard.whitesTurn, pseudo);

            final boolean inCheck = bitBoard.isInCheck(bitBoard.whitesTurn);
            final int[] a = pseudo.elements();
            final int n = pseudo.size();

            // 2) in-place filter (branch-light)
            int w = 0;
            int[] snapshot = null;
            if (VERIFY_LEGAL_MOVES) snapshot = java.util.Arrays.copyOf(a, n);

            for (int i = 0; i < n; i++) {
                final int m = a[i];
                if (!inCheck) {
                    if (!MoveHelper.isEnPassantMove(m) || bitBoard.isMoveLegalFast(m, pinState)) {
                        a[w++] = m;
                    }
                } else if (bitBoard.isMoveLegalFast(m, pinState)) {
                    a[w++] = m;
                }
            }
            // shrink logical size in O(1)
            pseudo.size(w);

            if (VERIFY_LEGAL_MOVES) {
                IntArrayList legacy = new IntArrayList(w);
                for (int i = 0; i < n; i++) {
                    int m = snapshot[i];
                    bitBoard.performMove(m);
                    if (!bitBoard.isInCheck(MoveHelper.isWhitesMove(m))) legacy.add(m);
                    bitBoard.undoMove(m);
                }
                if (!(legacy.size() == w &&
                        java.util.Arrays.equals(legacy.elements(), 0, w, a, 0, w))) {
                    throw new IllegalStateException("Mismatch between fast and legacy legal move filtering");
                }
            }

            // 3) cache (reuse double-buffered arrays via swap)
            ensureLegalMoveScratchCapacity(w);
            System.arraycopy(a, 0, legalMoveScratch, 0, w);
            cacheLegalMoves(boardStateHash, w);

            // 4) return a defensive copy for callers (cache retains reusable buffers)
            IntArrayList copy = copyCachedLegalMoves();
            MoveGenerationProfiler.onGenerationEnd(profilerStart, w);
            return copy;
        }
    }



    private void markLegalMovesStale() {
        legalMovesNeedUpdate = true;
        cachedLegalMovesHash = Long.MIN_VALUE;
    }

    private void cacheLegalMoves(long boardHash, int legalMoveCount) {
        ensureCachedLegalMoveCapacity(legalMoveCount);

        int[] previousCached = this.cachedLegalMoves;
        this.cachedLegalMoves = this.legalMoveScratch;
        this.legalMoveScratch = previousCached;

        this.cachedLegalMovesHash = boardHash;
        this.cachedLegalMoveCount = legalMoveCount;
        this.legalMovesNeedUpdate = false;
    }


    private void ensureLegalMoveScratchCapacity(int requiredSize) {
        if (legalMoveScratch.length < requiredSize) {
            int base = (legalMoveScratch.length == 0 ? MOVE_BUFFER_CAPACITY : legalMoveScratch.length << 1);
            int newSize = Math.max(requiredSize, base);
            legalMoveScratch = java.util.Arrays.copyOf(legalMoveScratch, newSize);
        }
    }

    private void ensureCachedLegalMoveCapacity(int requiredSize) {
        if (cachedLegalMoves.length < requiredSize) {
            int base = (cachedLegalMoves.length == 0 ? MOVE_BUFFER_CAPACITY : cachedLegalMoves.length << 1);
            int newSize = Math.max(requiredSize, base);
            cachedLegalMoves = java.util.Arrays.copyOf(cachedLegalMoves, newSize);
        }
    }

    private IntArrayList copyCachedLegalMoves() {
        int[] snapshot = java.util.Arrays.copyOf(cachedLegalMoves, cachedLegalMoveCount);
        return new IntArrayList(snapshot);
    }


    private void resetCachedLegalMoves() {
        cachedLegalMoves = new int[MOVE_BUFFER_CAPACITY];
        legalMoveScratch = new int[MOVE_BUFFER_CAPACITY];
        cachedLegalMoveCount = 0;
        cachedLegalMovesHash = Long.MIN_VALUE;
        legalMovesNeedUpdate = true;
    }

    public void moveRandomFigure(boolean isWhite) {
        IntArrayList moves = getAllLegalMoves();
        if (moves.isEmpty()) throw new RuntimeException("No moves possible for " + (isWhite ? "White" : "Black"));
        int idx = java.util.concurrent.ThreadLocalRandom.current().nextInt(moves.size());
        performMove(moves.getInt(idx));
    }

    // always queen
    public void moveFigure(int fromIndex, int toIndex) {
        moveFigure(bitBoard, fromIndex, toIndex, 5);
    }

    public void moveFigure(BitBoard bitBoard, int fromIndex, int toIndex, int promotionPiece) {
        final int move;
        synchronized (boardLock) {
            PieceType pt = bitBoard.getPieceTypeAtIndex(fromIndex);
            Color color = bitBoard.getPieceColorAtIndex(fromIndex);
            if (pt == null || color == null) throw new IllegalStateException("No piece at the starting position");

            Color mover = bitBoard.getPieceColorAtIndex(fromIndex);
            if ((mover == Color.WHITE && !bitBoard.whitesTurn) || (mover == Color.BLACK && bitBoard.whitesTurn)) {
                bitBoard.logBoard();
                throw new IllegalStateException("It's not " + mover + "'s turn");
            }

            move = getMove(fromIndex, toIndex, promotionPiece);
            if (move == -1) {
                log.warn("Move not legal!");
                return;
            }
        }
        performMove(move); // reuse the computed move
    }


    private int getMove(int fromIndex, int toIndex, int promotionPiece) {
        IntArrayList legalMoves = getAllLegalMoves();
        for (int i = 0; i < legalMoves.size(); i++) {
            int m = legalMoves.getInt(i);
            if (MoveHelper.deriveFromIndex(m) != fromIndex) continue;
            if (MoveHelper.deriveToIndex(m) != toIndex) continue;
            int promo = MoveHelper.derivePromotionPieceTypeBits(m);
            if (promo == 0 || promo == promotionPiece) return m; // exact or non-promo
        }
        return -1;
    }

    public long getBoardStateHash() {
        synchronized (boardLock) {
            return bitBoard.getBoardStateHash();
        }
    }

    public void logBoard() {
        synchronized (boardLock) {
            bitBoard.logBoard();
        }
    }

    public boolean whitesTurn() {
        synchronized (boardLock) {
            return bitBoard.whitesTurn;
        }
    }

    // Engine.java
    public int doNullMoveForSearch() {
        synchronized (boardLock) {
            int previousDoubleStep = bitBoard.getLastMoveDoubleStepPawnIndex();

            // EP is not valid across a null move: clear it
            bitBoard.setLastMoveDoubleStepPawnIndex(0);

            // Flip side to move
            bitBoard.flipSideToMove();

            // Mark move list stale so the next search ply regenerates for the new side.
            markLegalMovesStale();

            gameState.refreshScore(bitBoard);
            updateLastTablebaseResult();

            return previousDoubleStep;
        }
    }

    // Engine.java
    public void undoNullMoveForSearch(int previousDoubleStep) {
        synchronized (boardLock) {
            // Flip side back
            bitBoard.flipSideToMove();

            // Restore EP target
            bitBoard.setLastMoveDoubleStepPawnIndex(previousDoubleStep);

            // Mark stale again so we rebuild for the restored side.
            markLegalMovesStale();

            gameState.refreshScore(bitBoard);
            updateLastTablebaseResult();
        }
    }

    public void undoLastMove() {
        long notifyHash;

        synchronized (boardLock) {
            if (line.isEmpty()) throw new IllegalStateException("undoLastMoveWasNotPossible, line is empty");

            int undoMove = line.pop();

            long currentHash = getBoardStateHash();
            gameState.removeHash(currentHash);

            // 1) Undo on the board
            this.bitBoard.undoMove(undoMove);

            // 2) Recompute legal moves
            IntArrayList legalMoves = generateLegalMoves();
            gameState.popHalfmoveClock(bitBoard);
            gameState.updateState(bitBoard, legalMoves, false);
            gameState.getScore().undoMove(bitBoard, undoMove, gameState.getState());
            gameState.captureTablebaseState();
            updateLastTablebaseResult();

            // 3) Bookkeeping
            redoLine.push(undoMove);

            notifyHash = getBoardStateHash();
        }

        notifyPositionChanged(notifyHash);
    }

    public void redoMove() {
        int moveToRedo;

        synchronized (boardLock) {
            if (redoLine.isEmpty()) {
                throw new IllegalStateException("redoLastMoveWasNotPossible, redoLine is empty");
            }
            moveToRedo = redoLine.pop();
        }

        // Use performMove which handles locking and deferred notification.
        performMove(moveToRedo);
    }

    public int getLastMove() {
        if (!line.isEmpty()) {
            return line.peek();
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

    public Optional<TablebaseResult> getLastTablebaseResult() {
        return Optional.ofNullable(lastTablebaseResult);
    }

    private void updateLastTablebaseResult() {
        GameState currentState = this.gameState;
        this.lastTablebaseResult = (currentState != null)
                ? currentState.getLastTablebaseResult().orElse(null)
                : null;
    }

    public static void enableMoveGenerationProfiling() {
        MoveGenerationProfiler.enable();
    }

    public static void disableMoveGenerationProfiling() {
        MoveGenerationProfiler.disable();
    }

    public static void resetMoveGenerationProfiling() {
        MoveGenerationProfiler.reset();
    }

    public static boolean isMoveGenerationProfilingEnabled() {
        return MoveGenerationProfiler.isEnabled();
    }

    public static MoveGenerationStats snapshotMoveGenerationStats() {
        return MoveGenerationProfiler.snapshot();
    }

    public record MoveGenerationStats(long generationCalls, long cacheHits, long generatedMoves, long generationNanos) {
    }

    private static final class MoveGenerationProfiler {
        private static final LongAdder generationCalls = new LongAdder();
        private static final LongAdder cacheHits = new LongAdder();
        private static final LongAdder generatedMoves = new LongAdder();
        private static final LongAdder generationNanos = new LongAdder();
        private static volatile boolean enabled = Boolean.getBoolean("chessengine.movegen.profile");

        private MoveGenerationProfiler() {
        }

        static boolean isEnabled() {
            return enabled;
        }

        static long onGenerationStart() {
            return enabled ? System.nanoTime() : 0L;
        }

        static void onGenerationEnd(long start, int moveCount) {
            if (!enabled) {
                return;
            }
            generationCalls.increment();
            generatedMoves.add(moveCount);
            if (start != 0L) {
                generationNanos.add(System.nanoTime() - start);
            }
        }

        static void recordCacheHit() {
            if (enabled) {
                cacheHits.increment();
            }
        }

        static void enable() {
            enabled = true;
        }

        static void disable() {
            enabled = false;
            reset();
        }

        static void reset() {
            generationCalls.reset();
            cacheHits.reset();
            generatedMoves.reset();
            generationNanos.reset();
        }

        static MoveGenerationStats snapshot() {
            if (!enabled) {
                return new MoveGenerationStats(0, 0, 0, 0);
            }
            return new MoveGenerationStats(
                    generationCalls.sum(),
                    cacheHits.sum(),
                    generatedMoves.sum(),
                    generationNanos.sum()
            );
        }
    }
}

