package julius.game.chessengine.engine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.syzygy.TablebaseResult;
import julius.game.chessengine.utils.Score;
import lombok.Data;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

@Data
@Log4j2
public class GameState {

    private final LongArrayList hashHistory = new LongArrayList(256);
    private final Long2IntOpenHashMap repetition;
    private final IntArrayList halfmoveStack = new IntArrayList();
    private final IntArrayList fullmoveStack = new IntArrayList();

    private GameStateEnum state;

    private Score score;

    private boolean drawByInsufficientMaterial;

    private TablebaseResult lastTablebaseResult;

    @Getter
    private int halfmoveClock = 0;          // resets on pawn move or capture
    @Getter
    private int fullmoveNumber = 1;
    private long lastZobrist = 0L;          // last committed root hash

    public GameState(BitBoard bitBoard) {
        this.repetition = createRepetitionMap();
        state = GameStateEnum.PLAY;
        score = Score.initializeScore(bitBoard);
        this.drawByInsufficientMaterial = bitBoard.hasInsufficientMaterial();
        this.halfmoveClock = bitBoard.getHalfmoveClock();
        this.fullmoveNumber = bitBoard.getFullmoveNumber();
        recordHash(bitBoard.getBoardStateHash());
        captureTablebaseState();
    }

    public GameState(GameState other) {
        this.repetition = copyRepetitionMap(other.repetition);
        this.state = other.state; // Enum, so a direct copy is fine
        this.score = new Score(other.score);
        this.halfmoveClock = other.halfmoveClock;
        this.fullmoveNumber = other.fullmoveNumber;
        this.lastZobrist = other.lastZobrist;
        this.drawByInsufficientMaterial = other.drawByInsufficientMaterial;
        this.hashHistory.addAll(other.hashHistory);
        this.halfmoveStack.addAll(other.halfmoveStack);
        this.fullmoveStack.addAll(other.fullmoveStack);
        this.lastTablebaseResult = other.lastTablebaseResult;
    }

    public void refreshScore(BitBoard bitBoard) {
        score.refresh(bitBoard, state);
        captureTablebaseState();
    }
    public void update(BitBoard bitBoard, IntArrayList legalMoves, int move, boolean isOpeningMove) {
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

    public void updateState(BitBoard bitBoard, IntArrayList legalMoves, boolean isOpeningMove) {
        drawByInsufficientMaterial = bitBoard.hasInsufficientMaterial();

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
        } else if (isStalemate(bitBoard, legalMoves)) {
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

    public boolean isInStateCheck() {
        // The BitBoard class already has a method to check if a king is in check
        return state.equals(GameStateEnum.BLACK_IN_CHECK) || state.equals(GameStateEnum.WHITE_IN_CHECK);
    }

    public boolean isInStateCheckMate() {
        return state.equals(GameStateEnum.WHITE_WON) || state.equals(GameStateEnum.BLACK_WON);
    }

    // NEW: use this everywhere to decide if the node is terminal for move-handling.
    public boolean isTerminal() {
        if (isInStateCheckMate()) return true;
        if (state.equals(GameStateEnum.DRAW)) return true; // stalemate, 50-move, threefold set this
        // IMPORTANT: insufficient material is NOT terminal
        return false;
    }

    // Keep this strictly as "terminal draw?"
    public boolean isInStateDraw() {
        return state.equals(GameStateEnum.DRAW); // do NOT include insufficient material here
    }

    // For UI/evaluation: safe to show draw symbol/score without stopping search.
    public boolean isDrawForUIOrEval() {
        return state.equals(GameStateEnum.DRAW) || drawByInsufficientMaterial;
    }

    // Optional: keep isGameOver as a thin wrapper to avoid misuse elsewhere.
    public boolean isGameOver() {
        return isTerminal();
    }


    private boolean whiteInCheck(BitBoard bitBoard) {
        return bitBoard.isInCheck(true);
    }

    private boolean blackInCheck(BitBoard bitBoard) {
        return bitBoard.isInCheck(false);
    }

    private boolean whiteLost(IntArrayList legalMoves) {
        return state.equals(GameStateEnum.WHITE_IN_CHECK) && legalMoves.isEmpty();
    }

    private boolean blackLost(IntArrayList legalMoves) {
        return state.equals(GameStateEnum.BLACK_IN_CHECK) && legalMoves.isEmpty();
    }


    private boolean isStalemate(BitBoard bitBoard, IntArrayList legalMoves) {
        boolean noLegalMoves = legalMoves.isEmpty();
        boolean inCheck = bitBoard.isInCheck(bitBoard.isWhitesTurn());
        return noLegalMoves && !inCheck;
    }

    /**
     * Threefold Repetition Logic
     */
    public void recordHash(long zKey) {
        hashHistory.add(zKey);
        repetition.addTo(zKey, 1);
        lastZobrist = zKey;
    }

    public void removeHash(long zKey) {
        // Called on undo at the current head
        int count = repetition.get(zKey);
        if (count > 0) {
            if (count <= 1) repetition.remove(zKey);
            else repetition.put(zKey, count - 1);
        }
        // We only ever remove the most recent
        if (!hashHistory.isEmpty()) {
            hashHistory.removeLong(hashHistory.size() - 1);
        }
        lastZobrist = hashHistory.isEmpty() ? 0L : hashHistory.getLong(hashHistory.size() - 1);
    }

    public long getLastZobrist() {
        return lastZobrist;
    }

    public boolean isThreefoldRepetition() {
        return repetition.get(lastZobrist) >= 3;
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

    public java.util.Optional<TablebaseResult> getLastTablebaseResult() {
        return java.util.Optional.ofNullable(lastTablebaseResult);
    }

    public void captureTablebaseState() {
        this.lastTablebaseResult = score != null
                ? score.getTablebaseResult().orElse(null)
                : null;
    }

    @Override
    public String toString() {
        StringBuilder hashHistoryBuilder = new StringBuilder("[");
        for (int i = 0; i < hashHistory.size(); i++) {
            if (i > 0) {
                hashHistoryBuilder.append(',').append(' ');
            }
            hashHistoryBuilder.append(hashHistory.getLong(i));
        }
        hashHistoryBuilder.append(']');

        return "GameState {" +
                "\n  State: " + state +
                "\n  Midgame Score: " + score.getMidgameScore() +
                "\n  Endgame Score: " + score.getEndgameScore() +
                "\n  Blended Score: " + score.getBlendedScore() +
                "\n  Score Difference: " + score.getScoreDifference() +
                "\n  Last Zobrist: " + lastZobrist +
                "\n  Hash History: " + hashHistoryBuilder +
                "\n  Repetition Count: " + repetition +
                "\n}";
    }

    private static Long2IntOpenHashMap createRepetitionMap() {
        Long2IntOpenHashMap map = new Long2IntOpenHashMap(256);
        map.defaultReturnValue(0);
        return map;
    }

    private static Long2IntOpenHashMap copyRepetitionMap(Long2IntOpenHashMap other) {
        Long2IntOpenHashMap map = new Long2IntOpenHashMap(other);
        map.defaultReturnValue(other.defaultReturnValue());
        return map;
    }
}
