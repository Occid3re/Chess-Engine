package julius.game.chessengine.controller;

import julius.game.chessengine.ai.AI;
import julius.game.chessengine.ai.MoveAndScore;
import julius.game.chessengine.ai.OpeningBook;
import julius.game.chessengine.board.*;
import julius.game.chessengine.engine.GameState;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.pgn.PGN;
import julius.game.chessengine.pgn.PgnParser;
import julius.game.chessengine.utils.Score;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static julius.game.chessengine.board.MoveHelper.convertIndexToString;
import static julius.game.chessengine.board.MoveHelper.convertStringToIndex;

@Log4j2
@Controller
@RequestMapping(value = "/chess")
@RequiredArgsConstructor
public class ChessController {

    private final AI ai;
    private final OpeningBook openingBook;

    @GetMapping(value = "/score")
    public ResponseEntity<Score> getScore() {
        return ResponseEntity.ok( ai.getMainEngine().getGameState().getScore());
    }

    @PutMapping(value = "/reset")
    public ResponseEntity<?> resetBoard() {
        ai.reset();
        return ResponseEntity.ok().build();
    }

    @PatchMapping(value = "/fen")
    public ResponseEntity<?> setBoardToFEN(@RequestParam("fen") String fen) {
        ai.getMainEngine().importBoardFromFen(fen);
        return ResponseEntity.ok().build();
    }

    @GetMapping(value = "/autoplay")
    public ResponseEntity<?> autoplay() {
        ai.startAutoPlay(true, true);
        return ResponseEntity.ok().build();
    }

    @PatchMapping(value = "/autoplay/timelimit/{timeLimit}")
    public ResponseEntity<?> autoplaySetTimelimit(@PathVariable("timeLimit") long timeLimit)  {
        ai.setTimeLimit(timeLimit);
        log.debug("setting to: " + timeLimit);
        return ResponseEntity.ok().build();
    }
    @PatchMapping(value = "/autoplay/{color}")
    public ResponseEntity<?> calculateMoveForColor(@PathVariable("color") String color) {
        if (color != null) {
            log.debug(color);
            ai.startAutoPlay(color.equalsIgnoreCase("WHITE"), color.equalsIgnoreCase("BLACK"));
            return ResponseEntity.ok().build();
        } else return ResponseEntity.status(406).build();
    }

    @GetMapping(value = "/autoplay/lastMove")
    public ResponseEntity<ApiMove> getLastMove() {
        GameStateEnum state = ai.getMainEngine().getGameState().getState();
        int lastMove = ai.getMainEngine().getLine().getLast();
        int fromIndex = MoveHelper.deriveFromIndex(lastMove);
        int toIndex = MoveHelper.deriveToIndex(lastMove);

        ApiMove move = new ApiMove(state, convertIndexToString(fromIndex),convertIndexToString(toIndex));
        return ResponseEntity.ok(move);
    }

    @GetMapping(value = "/undo")
    public ResponseEntity<?> undoLastMove() {
        ai.getMainEngine().undoLastMove();
        return ResponseEntity.ok().build();
    }

    @GetMapping(value = "/redo")
    public ResponseEntity<?> redoLastMove() {
        ai.getMainEngine().redoMove();
        return ResponseEntity.ok().build();
    }

    @GetMapping(value = "/pgn")
    public ResponseEntity<PGN> getPGN() {
        PgnParser pgnParser = new PgnParser(ai.getMainEngine().getLine());
        return ResponseEntity.ok(pgnParser.parseToPgn());
    }

    @GetMapping(value = "/field/possible/white")
    public ResponseEntity<List<Move>> getAllPossibleFieldsWhite() {
        MoveList moves =  ai.getMainEngine().getAllLegalMoves();
        List<Move> restApiMoves = new ArrayList<>();
        for (int i = 0; i < moves.size(); i++) {
            restApiMoves.add(Move.convertIntToMove(moves.getMove(i)));
        }

        return ResponseEntity.ok(restApiMoves);
    }

    @GetMapping(value = "/field/possible/black")
    public ResponseEntity<List<Move>> getAllPossibleFieldsBlack() {
        MoveList moves = ai.getMainEngine().getAllLegalMoves();
        List<Move> restApiMoves = new ArrayList<>();
        for (int i = 0; i < moves.size(); i++) {
            restApiMoves.add(Move.convertIntToMove(moves.getMove(i)));
        }

        return ResponseEntity.ok(restApiMoves);
    }

    @GetMapping(value = "/figure/frontend")
    public ResponseEntity<FEN> getFiguresFrontend() {
        return ResponseEntity.ok( ai.getMainEngine().translateBoardToFen());
    }

    @PatchMapping(value = "/figure/move/{from}/{to}")
    public ResponseEntity<GameState> moveFigure(@PathVariable("from") String from,
                                                @PathVariable("to") String to,
                                                @RequestParam(value = "saveToOpeningBook", defaultValue = "false") boolean saveToOpeningBook) {
        if (from == null || to == null) {
            return ResponseEntity.status(406).build(); // Not Acceptable if from or to is null
        }

        long boardStateHash = -1;
        if (saveToOpeningBook) {
            // Capture the board state hash before the move
            boardStateHash =  ai.getMainEngine().getBoardStateHash();
        }

        // Perform the move on the engine
        int fromIndex = convertStringToIndex(from);
        int toIndex = convertStringToIndex(to);
        GameState state =  ai.getMainEngine().moveFigure(fromIndex, toIndex, 5); // Replace 5 with the actual promotion piece type, if applicable

        if (saveToOpeningBook && boardStateHash != -1) {
            // Save the opening if required
            int lastMove =  ai.getMainEngine().getLastMove();
            if(lastMove != -1) {
                openingBook.addOpening(lastMove, boardStateHash);
            }
        }

        ai.updateBoardStateHash(); // Update AI's board state hash
        return ResponseEntity.ok(state);
    }

    @GetMapping(value = "/figure/move/possible/{from}")
    public ResponseEntity<List<Position>> getPossibleToPositions(@PathVariable("from") String from) {
        if (from != null) {
            return ResponseEntity.ok(ai.getMainEngine().getPossibleMovesForPosition(convertStringToIndex(from)));
        } else return ResponseEntity
                .status(406)
                .build();
    }

    @GetMapping(value = "/state")
    public ResponseEntity<BoardState> getBoardState() {
        List<MoveAndScore> moveAndScores = ai.getCalculatedLine();
        GameState gameState = ai.getMainEngine().getGameState();

        BoardState boardState = new BoardState();
        boardState.setGameState(gameState);
        int lastMove = ai.getMainEngine().getLastMove();
        if(lastMove != -1) {
            boardState.setLastMove(Move.convertIntToMove(lastMove).getTo().toString());
        }

        if (!moveAndScores.isEmpty()) {
            String moves = moveAndScores.stream()
                    .map(ms -> Move.convertIntToMove(ms.getMove()).toString())
                    .collect(Collectors.joining(", "));
            boardState.setMove(moves);

            double lastScore = moveAndScores.get(moveAndScores.size() - 1).getScore();
            boardState.setScore(lastScore);
        }

        return ResponseEntity.ok(boardState);
    }


}
