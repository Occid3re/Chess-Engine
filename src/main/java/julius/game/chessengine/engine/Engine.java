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
import java.util.stream.Collectors;

@Service
@Log4j2
public class Engine {

    private static final int MAX_SIZE = 5_000_000;
    private static final int MAX_AGE = 10_000;

    private TimedLRUCache<MoveList> legalMovesCache = new TimedLRUCache<>(MAX_SIZE, MAX_AGE);

    @Getter
    private OpeningBook openingBook;

    private boolean legalMovesNeedUpdate = true;
    private MoveList legalMoves;

    @Getter
    private ArrayList<Integer> line = new ArrayList<>();
    private ArrayList<Integer> redoLine = new ArrayList<>();
    private BitBoard bitBoard = new BitBoard();
    @Getter
    private GameState gameState = new GameState(bitBoard);

    public Engine() {
        startNewGame();
    }

    public Engine(Engine other) {
        this.bitBoard = new BitBoard(other.bitBoard); // Assuming BitBoard's constructor is a deep copy constructor
        this.gameState = new GameState(other.gameState); // Assuming GameState's constructor is a deep copy constructor
        this.line = new ArrayList<>(other.line);
        this.redoLine = new ArrayList<>(other.redoLine);
        this.legalMoves = other.legalMoves == null ? null : new MoveList(other.legalMoves);
        this.legalMovesNeedUpdate = other.legalMovesNeedUpdate;
        this.openingBook = other.openingBook;
        this.legalMovesCache = new TimedLRUCache<>(MAX_SIZE, MAX_AGE);
        other.legalMovesCache.forEach((k, v) -> this.legalMovesCache.put(k, new MoveList(v)));
    }

    public MoveList getAllLegalMoves() {
        if (gameState.isGameOver()) {
            if (legalMoves == null) {
                legalMoves = new MoveList();  // Create only if null
            } else {
                legalMoves.clear();  // Clear existing list instead of creating a new one
            }
        } else if (legalMovesNeedUpdate) {
            generateLegalMoves();
        }
        return legalMoves;
    }

    public synchronized void performMove(int move) {
        boolean isOpeningMove = false;
        if (!gameState.isGameOver()) {
            long boardStateHashBeforeMove = getBoardStateHash();
            bitBoard.performMove(move);
            if (openingBook.containsMoveAndBoardStateHash(boardStateHashBeforeMove, move)) {
                isOpeningMove = true;
            }
            generateLegalMoves();
            gameState.update(bitBoard, legalMoves, move, isOpeningMove);
            line.add(move);
        }
    }

    public void importBoardFromFen(String fen) {
        this.bitBoard = FEN.translateFENtoBitBoard(fen);
        this.gameState = new GameState(bitBoard);
        generateLegalMoves();
        gameState.updateState(bitBoard, legalMoves, false);
    }

    public synchronized Engine createSimulation() {
        // Safe copy
        return new Engine(this);
    }

    public void startNewGame() {
        bitBoard = new BitBoard();
        gameState = new GameState(bitBoard);
        legalMovesNeedUpdate = true;
        line = new ArrayList<>();
        redoLine = new ArrayList<>();
        this.openingBook = OpeningBook.getInstance();
        legalMovesCache = new TimedLRUCache<>(MAX_SIZE, MAX_AGE);
    }

    private void generateLegalMoves() {
        final long boardStateHash = getBoardStateHash();

        // Use cached result if available
        MoveList cached = legalMovesCache.get(boardStateHash);
        if (cached != null) {
            this.legalMoves = cached;
            legalMovesNeedUpdate = false;
            return;
        }

        if (gameState.isGameOver()) {
            this.legalMoves = new MoveList();
            legalMovesNeedUpdate = false;
            return;
        }

        // Generate pseudo-legal moves on the current board
        MoveList moves = bitBoard.getAllCurrentPossibleMoves();

        // Filter in-place by making/unmaking on the SAME bitBoard (no BitBoard copy)
        MoveList legal = new MoveList();
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getMove(i);

            bitBoard.performMove(move);
            // If the mover's king is not left in check, the move is legal
            if (!bitBoard.isInCheck(MoveHelper.isWhitesMove(move))) {
                legal.add(move);
            }
            bitBoard.undoMove(move);
        }

        this.legalMoves = legal;
        legalMovesNeedUpdate = false;
        legalMovesCache.put(boardStateHash, legal);

        int size = legalMovesCache.size();
        if (size > MAX_SIZE) {
            throw new RuntimeException(String.format("LegalMovesCache size %s is larger than MAX_SIZE %s", size, MAX_SIZE));
        }
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
        // Now, the color parameter is used to determine which moves to generate
        MoveList moves = getAllLegalMoves();

        if (moves.size() == 0) {
            throw new RuntimeException("No moves possible for " + (isWhite ? "White" : "Black"));
        }

        Random rand = new Random();
        int randomMove = moves.getMove(rand.nextInt(moves.size()));

        // Execute the move on the bitboard
        performMove(randomMove);

    }

    public synchronized GameState moveFigure(int fromIndex, int toIndex, int promotionPiece) {
        return moveFigure(bitBoard, fromIndex, toIndex, promotionPiece);
    }

    //always queen
    public synchronized void moveFigure(int fromIndex, int toIndex) {
        moveFigure(bitBoard, fromIndex, toIndex, 5);
    }

    public synchronized GameState moveFigure(BitBoard bitBoard, int fromIndex, int toIndex, int promotionPiece) {
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
            // Perform the move on the bitboard
            performMove(move);
        }

        return gameState;
    }

    private int getMove(int fromIndex, int toIndex, int promotionPiece) {
        MoveList legalMoves = getAllLegalMoves();

        int move = -1;

        for (int i = 0; i < legalMoves.size(); i++) {
            int m = legalMoves.getMove(i);
            int from = MoveHelper.deriveFromIndex(m); // Extract the first 6 bits
            int to = MoveHelper.deriveToIndex(m); // Extract the next 6 bits
            int promotionPieceTypeBits = MoveHelper.derivePromotionPieceTypeBits(m);

            if (from == fromIndex && to == toIndex && (promotionPieceTypeBits == 0 | promotionPieceTypeBits == promotionPiece)) {
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


    public synchronized long getBoardStateHash() {
        return bitBoard.getBoardStateHash();
    }


    public void logBoard() {
        bitBoard.logBoard();
    }

    public boolean whitesTurn() {
        return bitBoard.whitesTurn;
    }

    // Engine.java
    public int doNullMoveForSearch() {
        // Save current en-passant target (0 means none in your codebase)
        int previousDoubleStep = bitBoard.getLastMoveDoubleStepPawnIndex();

        // EP is not valid across a null move: clear it (no move-gen)
        bitBoard.setLastMoveDoubleStepPawnIndex(0);

        // Flip side to move (no legalMoves recompute, no GameState updates)
        bitBoard.whitesTurn = !bitBoard.whitesTurn;

        return previousDoubleStep;
    }


    // Engine.java
    public void undoNullMoveForSearch(int previousDoubleStep) {
        // Flip side back (still no move-gen / GameState work)
        bitBoard.whitesTurn = !bitBoard.whitesTurn;

        // Restore EP target
        bitBoard.setLastMoveDoubleStepPawnIndex(previousDoubleStep);
    }


    public void undoLastMove() {
        if (!line.isEmpty()) {
            gameState.undo(bitBoard.getBoardStateHash());
            Integer undoMove = line.getLast();
            this.bitBoard.undoMove(undoMove);
            gameState.updateScore(bitBoard, undoMove);
            generateLegalMoves();
            redoLine.add(undoMove);
            line.removeLast();
        } else {
            throw new IllegalStateException("undoLastMoveWasNotPossible, line is empty");
        }
    }

    public void redoMove() {
        if (!redoLine.isEmpty()) {
            performMove(redoLine.getLast());
            redoLine.removeLast();
        } else {
            throw new IllegalStateException("redoLastMoveWasNotPossible, redoLine is empty");
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
        return FEN.translateBoardToFEN(bitBoard);
    }

    public boolean isEndgame() {
        return bitBoard.isEndgame();
    }

}
