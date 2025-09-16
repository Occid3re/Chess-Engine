package julius.game.chessengine.engine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.utils.Score;
import lombok.Data;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;


@Data
@Log4j2
public class GameState {

    private final Deque<Long> hashHistory = new ArrayDeque<>(256);
    private final HashMap<Long, Integer> repetition = new HashMap<>();
    private final IntArrayList halfmoveStack = new IntArrayList();
    private final IntArrayList fullmoveStack = new IntArrayList();

    private GameStateEnum state;

    private Score score;

    @Getter
    private int halfmoveClock = 0;          // resets on pawn move or capture
    @Getter
    private int fullmoveNumber = 1;
    private long lastZobrist = 0L;          // last committed root hash

    public GameState(BitBoard bitBoard) {
        state = GameStateEnum.PLAY;
        score = Score.initializeScore(bitBoard);
        this.halfmoveClock = bitBoard.getHalfmoveClock();
        this.fullmoveNumber = bitBoard.getFullmoveNumber();
        recordHash(bitBoard.getBoardStateHash());
    }

    public GameState(GameState other) {
        this.state = other.state; // Enum, so a direct copy is fine
        this.score = new Score(other.score);
        this.halfmoveClock = other.halfmoveClock;
        this.fullmoveNumber = other.fullmoveNumber;
        this.lastZobrist = other.lastZobrist;
        this.hashHistory.addAll(other.hashHistory);
        this.repetition.putAll(other.repetition);
        this.halfmoveStack.addAll(other.halfmoveStack);
        this.fullmoveStack.addAll(other.fullmoveStack);
    }
    public void update(BitBoard bitBoard, MoveList legalMoves, int move, boolean isOpeningMove) {
        updateState(bitBoard, legalMoves, isOpeningMove);

        // 50-move clock maintenance
        boolean isCapture = MoveHelper.isCapture(move);
        boolean isPawnMove = (MoveHelper.derivePieceTypeBits(move) == 1);
        if (isCapture || isPawnMove) resetHalfmoveClock();
        else incHalfmoveClock();

        if (!MoveHelper.isWhitesMove(move)) {
            fullmoveNumber++;
        }

        bitBoard.setHalfmoveClock(halfmoveClock);
        bitBoard.setFullmoveNumber(fullmoveNumber);

        // Threefold / 50-move adjudication
        if (isThreefoldRepetition() || isFiftyMoveRule()) {
            this.state = GameStateEnum.DRAW;
        }
    }

    public void updateState(BitBoard bitBoard, MoveList legalMoves, boolean isOpeningMove) {
        if (whiteInCheck(bitBoard)) {
            state = GameStateEnum.WHITE_IN_CHECK;
            if (whiteLost(legalMoves)) {
                state = GameStateEnum.BLACK_WON;
            }
        } else if (blackInCheck(bitBoard)) {
            state = GameStateEnum.BLACK_IN_CHECK;
            if (blackLost(legalMoves)) {
                state = GameStateEnum.WHITE_WON;
            }
        } else if (isDraw(bitBoard, legalMoves)) {
            state = GameStateEnum.DRAW;
        } else if (isFiftyMoveRule() || isThreefoldRepetition()) {
            state = GameStateEnum.DRAW;
        } else {
            if(isOpeningMove) {
                state = GameStateEnum.PLAY_OPENING;
            }
            else {
                state = GameStateEnum.PLAY;
            }
        }
    }



    /**
     * State mechanisms of the Game
     */

    public boolean isGameOver() {
        return isInStateCheckMate() || isInStateDraw();
    }

    public boolean isInStateCheck() {
        // The BitBoard class already has a method to check if a king is in check
        return state.equals(GameStateEnum.BLACK_IN_CHECK) || state.equals(GameStateEnum.WHITE_IN_CHECK);
    }

    public boolean isInStateCheckMate() {
        return state.equals(GameStateEnum.WHITE_WON) || state.equals(GameStateEnum.BLACK_WON);
    }

    public boolean isInStateDraw() {
        return state.equals(GameStateEnum.DRAW);
    }

    private boolean whiteInCheck(BitBoard bitBoard) {
        return bitBoard.isInCheck(true);
    }

    private boolean blackInCheck(BitBoard bitBoard) {
        return bitBoard.isInCheck(false);
    }

    private boolean whiteLost(MoveList legalMoves) {
        return state.equals(GameStateEnum.WHITE_IN_CHECK) && legalMoves.size() == 0;
    }

    private boolean blackLost(MoveList legalMoves) {
        return state.equals(GameStateEnum.BLACK_IN_CHECK) && legalMoves.size() == 0;
    }


    private boolean isDraw(BitBoard bitBoard, MoveList legalMoves) {
        boolean insufficientMaterial = bitBoard.hasInsufficientMaterial();
        boolean noLegalMoves = legalMoves.size() == 0;
        boolean inCheck = bitBoard.isInCheck(bitBoard.isWhitesTurn());
        return (noLegalMoves && !inCheck) || insufficientMaterial;
    }

    /**
     * Threefold Repetition Logic
     */
    public void recordHash(long zKey) {
        hashHistory.addLast(zKey);
        repetition.merge(zKey, 1, Integer::sum);
        lastZobrist = zKey;
    }

    public void removeHash(long zKey) {
        // Called on undo at the current head
        Integer c = repetition.get(zKey);
        if (c != null) {
            if (c <= 1) repetition.remove(zKey);
            else repetition.put(zKey, c - 1);
        }
        // We only ever remove the most recent
        if (!hashHistory.isEmpty()) hashHistory.removeLast();
        lastZobrist = hashHistory.isEmpty() ? 0L : hashHistory.getLast();
    }

    public boolean isThreefoldRepetition() {
        return repetition.getOrDefault(lastZobrist, 0) >= 3;
    }

    public void resetHalfmoveClock() { halfmoveClock = 0; }
    public void incHalfmoveClock() { halfmoveClock++; }
    public boolean isFiftyMoveRule() { return halfmoveClock >= 100; }

    public void pushHalfmoveClock() {
        halfmoveStack.addLast(halfmoveClock);
        fullmoveStack.addLast(fullmoveNumber);
    }
    public void popHalfmoveClock(BitBoard bitBoard)  {
        if (!halfmoveStack.isEmpty()) {
            halfmoveClock = halfmoveStack.removeLast();
        }
        if (!fullmoveStack.isEmpty()) {
            fullmoveNumber = fullmoveStack.removeLast();
        }
        bitBoard.setHalfmoveClock(halfmoveClock);
        bitBoard.setFullmoveNumber(fullmoveNumber);
    }

    @Override
    public String toString() {
        return "GameState {" +
                "\n  State: " + state +
                "\n  White Score: " + score.calculateTotalWhiteScore() +
                "\n  Black Score: " + score.calculateTotalBlackScore() +
                "\n  Score Difference: " + score.getScoreDifference() +
                "\n  Repetition Count: " + repetition +
                "\n}";
    }
}