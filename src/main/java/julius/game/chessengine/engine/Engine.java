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

    @Getter
    private MoveStack line = new MoveStack();
    private MoveStack redoLine = new MoveStack();
    private BitBoard bitBoard = new BitBoard();
    @Getter
    private GameState gameState = new GameState(bitBoard);

    private LongConsumer onPositionChanged = h -> {};


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
        }
    }

    public void setOnPositionChanged(LongConsumer cb) {
        this.onPositionChanged = (cb != null ? cb : h -> {});
    }
    private void notifyPositionChanged() {
        onPositionChanged.accept(getBoardStateHash());
    }

    /** Expose BitBoard's SEE to callers (AI). */
    public int see(int move) {
        synchronized (boardLock) {
            return bitBoard.see(move);
        }
    }

    public BitBoard getBitBoard() {
        return bitBoard;
    }

    public MoveList getAllLegalMoves() {
        synchronized (boardLock) {
            long boardHash = getBoardStateHash();
            if (legalMovesNeedUpdate || cachedLegalMovesHash != boardHash) {
                if (gameState.isGameOver()) {
                    cacheLegalMoves(boardHash, new MoveList());
                    return new MoveList();
                }
                return generateLegalMoves();
            }
            return copyCachedLegalMoves();
        }
    }

    public int getEnPassantTargetIndex() {
        synchronized (boardLock) {
            return bitBoard.getEnPassantTargetIndex();
        }
    }

    public void performMove(int move) {
        synchronized (boardLock) {
            boolean isOpeningMove = false;
            if (!gameState.isGameOver()) {
                long boardStateHashBeforeMove = getBoardStateHash();
                bitBoard.performMove(move);
                long newHash = getBoardStateHash();
                gameState.recordHash(newHash);
                OpeningBook book = resolveOpeningBook();
                if (book != null && book.containsMoveAndBoardStateHash(boardStateHashBeforeMove, move)) {
                    isOpeningMove = true;
                }
                MoveList legalMoves = generateLegalMoves();
                gameState.pushHalfmoveClock();
                gameState.update(bitBoard, legalMoves, move, isOpeningMove);
                gameState.getScore().applyMove(bitBoard, move, gameState.getState());
                line.push(move);
                notifyPositionChanged();
            }
        }
    }

    public void importBoardFromFen(String fen) {
        synchronized (boardLock) {
            this.bitBoard = FEN.translateFENtoBitBoard(fen);
            this.gameState = new GameState(bitBoard);

            // Ensure the imported state reflects the parsed halfmove/fullmove counters and
            // repetition baseline.  The constructor copies the counters from the bitboard, but
            // we explicitly reset the historical bookkeeping so future updates start from this
            // root position.
            gameState.setHalfmoveClock(bitBoard.getHalfmoveClock());
            gameState.setFullmoveNumber(bitBoard.getFullmoveNumber());
            gameState.getHashHistory().clear();
            gameState.getRepetition().clear();
            gameState.recordHash(getBoardStateHash());

            // For terminal draw states (e.g., fifty-move rule) skip move generation entirely so
            // callers observe an empty legal move list.
            if (gameState.isFiftyMoveRule() || gameState.isThreefoldRepetition()) {
                cacheLegalMoves(getBoardStateHash(), new MoveList());
                gameState.setState(GameStateEnum.DRAW);
            } else {
                MoveList legalMoves = generateLegalMoves();
                gameState.updateState(bitBoard, legalMoves, false);
            }
            notifyPositionChanged();
        }
    }

    public Engine createSimulation() {
        synchronized (boardLock) {
            // Safe copy
            return new Engine(this);
        }
    }

    public void copyFrom(Engine other) {
        Objects.requireNonNull(other, "other engine");
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
        }
    }

    public void startNewGame() {
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
            notifyPositionChanged();
        }
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

    private MoveList generateLegalMoves() {
        synchronized (boardLock) {
            MoveList legalMoves = new MoveList();
            final long boardStateHash = getBoardStateHash();

            IntArrayList pseudoMoves = bitBoard.getAllCurrentPossibleMoves();
            BitBoard.PinState pinState = bitBoard.computePinState(bitBoard.isWhitesTurn());

            for (int i = 0; i < pseudoMoves.size(); i++) {
                int move = pseudoMoves.getInt(i);
                if (bitBoard.isMoveLegalFast(move, pinState)) {
                    legalMoves.add(move);
                }
            }

            if (VERIFY_LEGAL_MOVES) {
                MoveList legacy = new MoveList();
                for (int i = 0; i < pseudoMoves.size(); i++) {
                    int move = pseudoMoves.getInt(i);
                    bitBoard.performMove(move);
                    if (!bitBoard.isInCheck(MoveHelper.isWhitesMove(move))) {
                        legacy.add(move);
                    }
                    bitBoard.undoMove(move);
                }
                if (!moveListsEqual(legalMoves, legacy)) {
                    throw new IllegalStateException("Mismatch between fast and legacy legal move filtering: fast="
                            + legalMoves + ", legacy=" + legacy);
                }
            }

            cacheLegalMoves(boardStateHash, legalMoves);

            return legalMoves;
        }
    }

    private void markLegalMovesStale() {
        legalMovesNeedUpdate = true;
        cachedLegalMovesHash = Long.MIN_VALUE;
    }

    private void cacheLegalMoves(long boardHash, MoveList legalMoves) {
        this.cachedLegalMovesHash = boardHash;
        this.cachedLegalMoves = legalMoves.toArray();
        this.cachedLegalMoveCount = this.cachedLegalMoves.length;
        this.legalMovesNeedUpdate = false;
    }

    private MoveList copyCachedLegalMoves() {
        MoveList copy = new MoveList();
        for (int i = 0; i < cachedLegalMoveCount; i++) {
            copy.add(cachedLegalMoves[i]);
        }
        return copy;
    }

    private void resetCachedLegalMoves() {
        cachedLegalMoves = new int[0];
        cachedLegalMoveCount = 0;
        cachedLegalMovesHash = Long.MIN_VALUE;
        legalMovesNeedUpdate = true;
    }

    private boolean moveListsEqual(MoveList a, MoveList b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.getMove(i) != b.getMove(i)) {
                return false;
            }
        }
        return true;
    }

    // Each of these methods would need to be implemented to handle the specific move generation for each piece type.
    public List<Move> getMovesFromIndex(int fromIndex) {
        MoveList legalMoves = getAllLegalMoves();
        List<Move> movesFromIndex = new ArrayList<>();
        for (int i = 0; i < legalMoves.size(); i++) {
            int m = legalMoves.getMove(i);
            int from = MoveHelper.deriveFromIndex(m); // Extract the first 6 bits
            if (from == fromIndex) {
                movesFromIndex.add(Move.convertIntToMove(m));
            }
        }
        return movesFromIndex;
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
            } else {
                performMove(move);
            }

            return gameState;
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
        // Save current en-passant target (0 means none in your codebase)
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
        synchronized (boardLock) {
            if (line.isEmpty()) throw new IllegalStateException("undoLastMoveWasNotPossible, line is empty");

            int undoMove = line.pop();

            long currentHash = getBoardStateHash();
            gameState.removeHash(currentHash);

            // 1) Undo on the board
            this.bitBoard.undoMove(undoMove);

            // 2) Recompute legal moves
            MoveList legalMoves = generateLegalMoves();
            gameState.popHalfmoveClock(bitBoard);
            gameState.updateState(bitBoard, legalMoves, false);
            gameState.getScore().undoMove(bitBoard, undoMove, gameState.getState());

            // 3) Bookkeeping
            redoLine.push(undoMove);
            notifyPositionChanged();
        }
    }


    public void redoMove() {
        synchronized (boardLock) {
            if (!redoLine.isEmpty()) {
                performMove(redoLine.pop());
                notifyPositionChanged();
            } else {
                throw new IllegalStateException("redoLastMoveWasNotPossible, redoLine is empty");
            }
        }
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
