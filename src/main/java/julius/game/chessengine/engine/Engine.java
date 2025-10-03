package julius.game.chessengine.engine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.ai.OpeningBook;
import julius.game.chessengine.board.*;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.utils.Color;
import julius.game.chessengine.utils.MoveStack;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

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
    private int[] cachedLegalMoves = new int[0];
    private int cachedLegalMoveCount = 0;

    private static final int MOVE_BUFFER_CAPACITY = 256;
    private final IntArrayList pseudoMoveBuffer = new IntArrayList(MOVE_BUFFER_CAPACITY);
    private final IntArrayList captureMoveBuffer = new IntArrayList(MOVE_BUFFER_CAPACITY);
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
            // Users can opt-in via setOnPositionChanged on the clone.
        }
    }

    public void setOnPositionChanged(LongConsumer cb) {
        this.onPositionChanged = (cb != null ? cb : h -> {});
    }

    /** Invoke observer outside of boardLock. */
    private void notifyPositionChanged(long hash) {
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

    public IntArrayList getAllLegalMoves() {
        synchronized (boardLock) {
            long boardHash = getBoardStateHash();
            if (legalMovesNeedUpdate || cachedLegalMovesHash != boardHash) {
                // Gate on terminality only (checkmate, stalemate, 50-move, threefold).
                if (gameState.isTerminal()) {
                    IntArrayList empty = new IntArrayList(0);
                    cacheLegalMoves(boardHash, legalMoveScratch, 0);
                    return empty;
                }
                return generateLegalMoves();
            }
            return copyCachedLegalMoves();
        }
    }

    public IntArrayList getLegalCapturesAndPromotions() {
        synchronized (boardLock) {
            if (bitBoard.isInCheck(bitBoard.whitesTurn)) {
                IntArrayList captures = captureMoveBuffer;
                captures.clear();

                IntArrayList allLegalMoves = getAllLegalMoves();
                for (int i = 0; i < allLegalMoves.size(); i++) {
                    int move = allLegalMoves.getInt(i);
                    if (MoveHelper.isCapture(move) || MoveHelper.isPawnPromotionMove(move)) {
                        captures.add(move);
                    }
                }

                return new IntArrayList(captures);
            }

            IntArrayList pseudoMoves = pseudoMoveBuffer;
            pseudoMoves.clear();
            BitBoard.PinState pinState = bitBoard.generateAllPossibleMovesInto(bitBoard.whitesTurn, pseudoMoves);

            IntArrayList captures = captureMoveBuffer;
            captures.clear();

            int[] pseudoElements = pseudoMoves.elements();
            final int pseudoCount = pseudoMoves.size();
            for (int i = 0; i < pseudoCount; i++) {
                int move = pseudoElements[i];
                if (!MoveHelper.isCapture(move) && !MoveHelper.isPawnPromotionMove(move)) {
                    continue;
                }

                if (MoveHelper.isEnPassantMove(move)) {
                    if (bitBoard.isMoveLegalFast(move, pinState)) {
                        captures.add(move);
                    }
                } else {
                    captures.add(move);
                }
            }

            return new IntArrayList(captures);
        }
    }

    public void performMove(int move) {
        long notifyHash = Long.MIN_VALUE; // sentinel: only notify if we actually set it

        synchronized (boardLock) {
            // Guard: never move from a terminal node
            if (gameState.isTerminal()) {
                throw new IllegalStateException("performMove called on terminal node");
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
                cacheLegalMoves(getBoardStateHash(), legalMoveScratch, 0);
                gameState.setState(GameStateEnum.DRAW);
                // Still surface the UI/eval hint if material is insufficient.
                gameState.setDrawByInsufficientMaterial(bitBoard.hasInsufficientMaterial());
            } else {
                // Otherwise recompute normally; stalemate (terminal) or insufficient (non-terminal) will be detected here.
                IntArrayList legalMoves = generateLegalMoves();
                gameState.updateState(bitBoard, legalMoves, false);
            }

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
            if (instance != null) {
                this.openingBook = instance;
            }
            resetCachedLegalMoves();
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

            // Get pseudo moves + the already-computed PinState in one shot
            IntArrayList pseudoMoves = pseudoMoveBuffer;
            pseudoMoves.clear();
            BitBoard.PinState pinState = bitBoard.generateAllPossibleMovesInto(bitBoard.whitesTurn, pseudoMoves);

            // NEW: detect check once
            boolean inCheck = bitBoard.isInCheck(bitBoard.whitesTurn);

            int[] pseudoElements = pseudoMoves.elements();
            final int pseudoCount = pseudoMoves.size();
            int[] pseudoSnapshot = null;
            if (VERIFY_LEGAL_MOVES) {
                pseudoSnapshot = java.util.Arrays.copyOf(pseudoElements, pseudoCount);
            }
            int writeIdx = 0;
            for (int i = 0; i < pseudoCount; i++) {
                int move = pseudoElements[i];

                // Fast path when NOT in check:
                // - pins already enforced during generation
                // - king steps were prefiltered in generateKingMoves (see §2)
                // So only EP needs a special legality test.
                if (!inCheck) {
                    if (MoveHelper.isEnPassantMove(move)) {
                        if (bitBoard.isMoveLegalFast(move, pinState)) {
                            pseudoElements[writeIdx++] = move;
                        }
                    } else {
                        pseudoElements[writeIdx++] = move;
                    }
                    continue;
                }

                // If in check, fall back to the full legality test
                if (bitBoard.isMoveLegalFast(move, pinState)) {
                    pseudoElements[writeIdx++] = move;
                }
            }

            pseudoMoves.size(writeIdx);

            ensureLegalMoveScratchCapacity(writeIdx);
            if (writeIdx > 0) {
                System.arraycopy(pseudoElements, 0, legalMoveScratch, 0, writeIdx);
            }

            IntArrayList legalMoves = new IntArrayList(writeIdx);
            if (writeIdx > 0) {
                legalMoves.addElements(0, legalMoveScratch, 0, writeIdx);
            }

            if (VERIFY_LEGAL_MOVES) {
                // Optional self-check retained for debugging
                IntArrayList legacy = new IntArrayList(legalMoves.size());
                for (int i = 0; i < pseudoCount; i++) {
                    int move = pseudoSnapshot[i];
                    bitBoard.performMove(move);
                    if (!bitBoard.isInCheck(MoveHelper.isWhitesMove(move))) {
                        legacy.add(move);
                    }
                    bitBoard.undoMove(move);
                }
                if (!moveListsEqual(legalMoves, legacy)) {
                    throw new IllegalStateException(
                            "Mismatch between fast and legacy legal move filtering: fast=" + legalMoves + ", legacy=" + legacy
                    );
                }
            }
            cacheLegalMoves(boardStateHash, legalMoveScratch, writeIdx);
            return legalMoves;
        }
    }

    private void markLegalMovesStale() {
        legalMovesNeedUpdate = true;
        cachedLegalMovesHash = Long.MIN_VALUE;
    }

    private void cacheLegalMoves(long boardHash, int[] legalMoves, int legalMoveCount) {
        this.cachedLegalMovesHash = boardHash;
        this.cachedLegalMoveCount = legalMoveCount;
        if (this.cachedLegalMoves.length < legalMoveCount) {
            this.cachedLegalMoves = java.util.Arrays.copyOf(legalMoves, legalMoveCount);
        } else {
            System.arraycopy(legalMoves, 0, this.cachedLegalMoves, 0, legalMoveCount);
        }
        this.legalMovesNeedUpdate = false;
    }

    private void ensureLegalMoveScratchCapacity(int requiredSize) {
        if (legalMoveScratch.length < requiredSize) {
            int newSize = Math.max(requiredSize, legalMoveScratch.length << 1);
            legalMoveScratch = java.util.Arrays.copyOf(legalMoveScratch, newSize);
        }
    }

    private IntArrayList copyCachedLegalMoves() {
        return new IntArrayList(java.util.Arrays.copyOf(cachedLegalMoves, cachedLegalMoveCount));
    }

    private void resetCachedLegalMoves() {
        cachedLegalMoves = new int[0];
        cachedLegalMoveCount = 0;
        cachedLegalMovesHash = Long.MIN_VALUE;
        legalMovesNeedUpdate = true;
    }

    private boolean moveListsEqual(IntArrayList a, IntArrayList b) {
        return a.size() == b.size()
                && java.util.Arrays.equals(a.elements(), 0, a.size(), b.elements(), 0, b.size());
    }

    // Each of these methods would need to be implemented to handle the specific move generation for each piece type.
    public List<Move> getMovesFromIndex(int fromIndex) {
        IntArrayList legalMoves = getAllLegalMoves();
        List<Move> movesFromIndex = new ArrayList<>();
        for (int i = 0; i < legalMoves.size(); i++) {
            int m = legalMoves.getInt(i);
            int from = MoveHelper.deriveFromIndex(m); // Extract the first 6 bits
            if (from == fromIndex) {
                movesFromIndex.add(Move.convertIntToMove(m));
            }
        }
        return movesFromIndex;
    }

    public void moveRandomFigure(boolean isWhite) {
        IntArrayList moves = getAllLegalMoves();
        if (moves.size() == 0) {
            throw new RuntimeException("No moves possible for " + (isWhite ? "White" : "Black"));
        }
        Random rand = new Random();
        int randomMove = moves.getInt(rand.nextInt(moves.size()));
        performMove(randomMove);
    }

    public GameState moveFigure(int fromIndex, int toIndex, int promotionPiece) {
        return moveFigure(bitBoard, fromIndex, toIndex, promotionPiece);
    }

    // always queen
    public void moveFigure(int fromIndex, int toIndex) {
        moveFigure(bitBoard, fromIndex, toIndex, 5);
    }

    public GameState moveFigure(BitBoard bitBoard, int fromIndex, int toIndex, int promotionPiece) {
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
                return gameState;
            }
        }
        // Perform the move outside of this synchronized block to reuse performMove's own locking & notification policy
        performMove(getMove(fromIndex, toIndex, promotionPiece));
        return gameState;
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

    public List<Position> getPossibleMovesForPosition(int fromIndex) {
        return getMovesFromIndex(fromIndex).stream()
                .map(Move::getTo)
                .collect(Collectors.toList());
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
}

