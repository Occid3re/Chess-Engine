package julius.game.chessengine.engine;

import julius.game.chessengine.ai.OpeningBook;
import julius.game.chessengine.board.*;
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

    private final Object boardLock = new Object();

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
        startNewGame();
    }

    public Engine(Engine other) {
        synchronized (other.boardLock) {
            this.bitBoard = new BitBoard(other.bitBoard);
            this.gameState = new GameState(other.gameState);
            this.line = new ArrayList<>(other.line);
            this.redoLine = new ArrayList<>(other.redoLine);

            // ✅ light: fresh empty state for the clone; compute lazily when needed
            this.legalMoves = null;
            this.legalMovesNeedUpdate = true;

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
            return bitBoard.see(move);
        }
    }

    public MoveList getAllLegalMoves() {
        synchronized (boardLock) {
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
    }

    public void performMove(int move) {
        synchronized (boardLock) {
            boolean isOpeningMove = false;
            if (!gameState.isGameOver()) {
                long boardStateHashBeforeMove = getBoardStateHash();
                bitBoard.performMove(move);
                long newHash = getBoardStateHash();
                gameState.recordHash(newHash);
                if (openingBook.containsMoveAndBoardStateHash(boardStateHashBeforeMove, move)) {
                    isOpeningMove = true;
                }
                generateLegalMoves();
                gameState.pushHalfmoveClock();
                gameState.update(bitBoard, legalMoves, move, isOpeningMove);
                gameState.getScore().applyMove(bitBoard, move, gameState.getState());
                line.add(move);
                notifyPositionChanged();
            }
        }
    }

    public void importBoardFromFen(String fen) {
        synchronized (boardLock) {
            this.bitBoard = FEN.translateFENtoBitBoard(fen);
            this.gameState = new GameState(bitBoard);

            // Ensure the imported state reflects the parsed half-move/full-move counters and
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
                if (legalMoves == null) {
                    legalMoves = new MoveList();
                } else {
                    legalMoves.clear();
                }
                legalMovesNeedUpdate = false;
                gameState.setState(GameStateEnum.DRAW);
            } else {
                generateLegalMoves();
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

    public void startNewGame() {
        synchronized (boardLock) {
            bitBoard = new BitBoard();
            gameState = new GameState(bitBoard);
            legalMovesNeedUpdate = true;
            line = new ArrayList<>();
            redoLine = new ArrayList<>();
            this.openingBook = OpeningBook.getInstance();
            notifyPositionChanged();
        }
    }

    private void generateLegalMoves() {
        synchronized (boardLock) {
            MoveList legal = this.legalMoves;
            if (legal == null) {
                legal = new MoveList();
            } else {
                legal.clear();
            }

            // Generate pseudo-legal moves on the current board
            MoveList moves = bitBoard.getAllCurrentPossibleMoves();

            // Filter in-place by making/unmaking on the SAME bitBoard (no BitBoard copy)
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
            legalMovesNeedUpdate = true;

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
            legalMovesNeedUpdate = true;

            gameState.refreshScore(bitBoard);
        }
    }

    public void undoLastMove() {
        synchronized (boardLock) {
            if (line.isEmpty()) throw new IllegalStateException("undoLastMoveWasNotPossible, line is empty");

            Integer undoMove = line.getLast();

            long currentHash = getBoardStateHash();
            gameState.removeHash(currentHash);

            // 1) Undo on the board
            this.bitBoard.undoMove(undoMove);

            // 2) Recompute legal moves
            generateLegalMoves();
            gameState.popHalfmoveClock(bitBoard);
            gameState.updateState(bitBoard, legalMoves, false);
            gameState.getScore().undoMove(bitBoard, undoMove, gameState.getState());

            // 3) Bookkeeping
            redoLine.add(undoMove);
            line.removeLast();
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
}
