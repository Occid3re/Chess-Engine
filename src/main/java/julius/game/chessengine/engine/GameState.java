package julius.game.chessengine.engine;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.utils.Score;
import lombok.Data;
import lombok.extern.log4j.Log4j2;

import java.util.concurrent.ConcurrentHashMap;

@Data
@Log4j2
public class GameState {

    private ConcurrentHashMap<Long, Integer> repetitionCounter;

    private GameStateEnum state;

    private Score score;

    public GameState(BitBoard bitBoard) {
        repetitionCounter = new ConcurrentHashMap<>();
        state = GameStateEnum.PLAY;
        score = new Score();
        initializeScore(bitBoard);
    }

    public GameState(GameState other) {
        this.repetitionCounter = new ConcurrentHashMap<>(other.repetitionCounter); // Deep copy of the map
        this.state = other.state; // Enum, so a direct copy is fine
        this.score = new Score(other.score);
    }


    private void initializeScore(BitBoard bitBoard) {
        score.initializeScore(bitBoard);
    }

    public void update(BitBoard bitBoard, MoveList legalMoves, int move, boolean isOpeningMove) {
        updateState(bitBoard, legalMoves, isOpeningMove);
        updateScore(bitBoard, move);
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
        } else {
            if(isOpeningMove) {
                state = GameStateEnum.PLAY_OPENING;
            }
            else {
                state = GameStateEnum.PLAY;
            }
            incrementHashCount(bitBoard.getBoardStateHash());
        }
    }

    public void updateScore(BitBoard bitBoard, int move) {
        //reset cached score
        score.resetCachedScoreDifference();

        boolean isWhite = MoveHelper.isWhitesMove(move);
        int pieceTypeBits = MoveHelper.derivePieceTypeBits(move);
        int capturedPieceTypeBits = MoveHelper.deriveCapturedPieceTypeBits(move);
        int promotionPieceTypeBits = MoveHelper.derivePromotionPieceTypeBits(move);

        updatePieceValues(isWhite, pieceTypeBits, bitBoard, state);

        if (capturedPieceTypeBits != 0) {
            updateCapturedPieceValues(isWhite, capturedPieceTypeBits, bitBoard);
        }
        if (promotionPieceTypeBits != 0) {
            updatePromotionPieceValues(isWhite, promotionPieceTypeBits, bitBoard);
        }

        log.debug("Piecetype: {}, CapturedType: {}, ScoreWhite: {}, ScoreBlack: {}",
                pieceTypeBits, capturedPieceTypeBits, score.calculateTotalWhiteScore(), score.calculateTotalBlackScore());
    }

    private void updatePieceValues(boolean isWhite, int pieceTypeBits, BitBoard bitBoard, GameStateEnum state) {
        if (isWhite) {
/*            //TODO check if this could be done more efficient
            int agilityWhite = bitBoard.generateAllPossibleMoves(true).size();*/

            updateValuesForWhite(pieceTypeBits, bitBoard);
/*            score.updateAgilityBonusWhite(agilityWhite);*/
            score.updateStateValuesWhite(state);

        } else {
/*            //TODO check if this could be done more efficient
            int agilityBlack = bitBoard.generateAllPossibleMoves(false).size();*/

            updateValuesForBlack(pieceTypeBits, bitBoard);
/*            score.updateAgilityBonusBlack(agilityBlack);*/
            score.updateStateValuesBlack(state);
        }


    }

    private void updateValuesForWhite(int pieceTypeBits, BitBoard bitBoard) {
        boolean isEndgame = bitBoard.isEndgame();
        switch (pieceTypeBits) {
            case 1: score.updateWhitePawnValues(bitBoard); break;
            case 2: score.updateWhiteKnightValues(bitBoard.getWhiteKnights(), bitBoard.getWhiteBishops(), bitBoard.getWhiteRooks()); break;
            case 3: score.updateWhiteBishopValues(bitBoard.getWhiteBishops(), bitBoard.getWhiteKnights(), bitBoard.getWhiteRooks()); break;
            case 4: score.updateWhiteRookValues(bitBoard); break;
            case 5: score.updateWhiteQueenValues(bitBoard.getWhiteQueens()); break;
            case 6: score.updateKingValuesWhite(bitBoard.getWhiteKing(), bitBoard.isWhiteKingHasCastled(), bitBoard.isWhiteKingMoved(), bitBoard.isWhiteRookA1Moved(), bitBoard.isWhiteRookH1Moved(), isEndgame); break;
            default: break; // Optionally handle default case
        }
    }

    private void updateValuesForBlack(int pieceTypeBits, BitBoard bitBoard) {
        boolean isEndgame = bitBoard.isEndgame();
        switch (pieceTypeBits) {
            case 1: score.updateBlackPawnValues(bitBoard); break;
            case 2: score.updateBlackKnightValues(bitBoard.getBlackKnights(), bitBoard.getBlackBishops(), bitBoard.getBlackRooks()); break;
            case 3: score.updateBlackBishopValues(bitBoard.getBlackBishops(), bitBoard.getBlackKnights(), bitBoard.getBlackRooks()); break;
            case 4: score.updateBlackRookValues(bitBoard); break;
            case 5: score.updateBlackQueenValues(bitBoard.getBlackQueens()); break;
            case 6: score.updateKingValuesBlack(bitBoard.getBlackKing(), bitBoard.isBlackKingHasCastled(), bitBoard.isBlackKingMoved(), bitBoard.isBlackRookA8Moved(), bitBoard.isBlackRookH8Moved(), isEndgame); break;
            default: break; // Optionally handle default case
        }
    }

    private void updateCapturedPieceValues(boolean isWhite, int capturedPieceTypeBits, BitBoard bitBoard) {


        if (isWhite) {
            //TODO check if this could be done more efficient
/*
            int agilityBlack = bitBoard.generateAllPossibleMoves(false).size();
            score.updateAgilityBonusBlack(agilityBlack);
*/

            updateValuesForBlack(capturedPieceTypeBits, bitBoard); // Update black pieces if white is capturing
        } else {
            //TODO check if this could be done more efficient
/*
            int agilityWhite = bitBoard.generateAllPossibleMoves(true).size();
            score.updateAgilityBonusBlack(agilityWhite);
*/

            updateValuesForWhite(capturedPieceTypeBits, bitBoard); // Update white pieces if black is capturing
        }
    }

    private void updatePromotionPieceValues(boolean isWhite, int promotionPieceTypeBits, BitBoard bitBoard) {
        if (isWhite) {
            updateValuesForWhite(promotionPieceTypeBits, bitBoard); // Update white pieces if black is capturing

        } else {
            updateValuesForBlack(promotionPieceTypeBits, bitBoard); // Update black pieces if white is capturing
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
        boolean isThreeFoldRepetition = isThreeFoldRepetition(bitBoard.getBoardStateHash());
        return legalMoves.size() == 0 || insufficientMaterial || isThreeFoldRepetition;
    }

    /**
     * Threefold Repetition Logic
     */
    private void incrementHashCount(long hash) {
        repetitionCounter.put(hash, repetitionCounter.getOrDefault(hash, 0) + 1);
    }

    private void decrementHashCount(long hash) {
        // Check if the hash exists in the map
        if (repetitionCounter.containsKey(hash)) {
            int count = repetitionCounter.get(hash);

            // Decrement the count
            if (count > 1) {
                repetitionCounter.put(hash, count - 1);
            } else {
                // If the count reaches zero, remove the hash from the map
                repetitionCounter.remove(hash);
            }
        }
    }

    private boolean isThreeFoldRepetition(long hash) {
        int repCount = repetitionCounter.getOrDefault(hash, 0);
        if (repCount > 3) {
            throw new IllegalStateException(String.format("Repetition count can't be higher then 3, was %s", repCount));
        }
        return repCount == 3;
    }

    public void undo(long hash) {
        decrementHashCount(hash);
        state = GameStateEnum.PLAY;
    }

    @Override
    public String toString() {
        String sb = "GameState {" +
                "\n  State: " + state +
                "\n  White Score: " + score.calculateTotalWhiteScore() +
                "\n  Black Score: " + score.calculateTotalBlackScore() +
                "\n  Score Difference: " + score.getScoreDifference() +
                "\n  Repetition Count: " + repetitionCounter +
                "\n}";
        return sb;
    }
}