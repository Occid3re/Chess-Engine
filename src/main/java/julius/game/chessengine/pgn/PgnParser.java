package julius.game.chessengine.pgn;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.figures.PieceType;

import java.time.ZonedDateTime;
import java.util.ArrayList;

public class PgnParser {

    private enum AmbiguityType {
        NONE, // No ambiguity
        NONE_BUT_SAME_TO_SQUARE,
        FILE, // Ambiguity in file
        RANK, // Ambiguity in rank
        BOTH  // Ambiguity in both file and rank
    }
    private final Engine engine = new Engine();
    private final ArrayList<Integer> line;

    private String event = "Alieknek testing";
    private String site = "Neulengbach";
    private String date = ZonedDateTime.now().toString();
    private String round = "1";
    private String whitePlayer = "Alieknek";
    private String blackPlayer = "Alieknek";
    private String result = "-";


    boolean sameFile = false;
    boolean sameRank = false;

    public PgnParser(ArrayList<Integer> line) {
        this.line = line;
    }

    public PGN parseToPgn() {
        StringBuilder pgn = new StringBuilder();
        addMetadata(pgn);

        engine.startNewGame();
        int moveCounter = 1;

        for (int i = 0; i < line.size(); i++) {
            engine.performMove(line.get(i)); // Update the engine's state for the next move
            if (i % 2 == 0) {
                pgn.append(moveCounter++).append(". ");
            }
            String movePgn = convertMoveToPgn(line.get(i));
            pgn.append(movePgn).append(" ");

        }

        return new PGN(pgn.toString().trim());
    }

    private void addMetadata(StringBuilder pgn) {
        pgn.append("[Event \"").append(event).append("\"]\n");
        pgn.append("[Site \"").append(site).append("\"]\n");
        pgn.append("[Date \"").append(date).append("\"]\n");
        pgn.append("[Round \"").append(round).append("\"]\n");
        pgn.append("[White \"").append(whitePlayer).append("\"]\n");
        pgn.append("[Black \"").append(blackPlayer).append("\"]\n");
        pgn.append("[Result \"").append(result).append("\"]\n\n");
    }

    private AmbiguityType isMoveAmbiguous(PieceType pieceType, int fromIndex, int toIndex) {
        engine.undoLastMove();
        MoveList allMoves = engine.getAllLegalMoves();
        boolean ambiguityInFile = false;
        boolean ambiguityInRank = false;
        boolean otherPieceCanMoveToSameSquare = false;

        for (int i = 0; i < allMoves.size(); i++) {
            int move = allMoves.getMove(i);
            int moveFromIndex = MoveHelper.deriveFromIndex(move);
            int moveToIndex = MoveHelper.deriveToIndex(move);

            if (MoveHelper.derivePieceTypeBits(move) == MoveHelper.pieceTypeToInt(pieceType)
                    && moveToIndex == toIndex
                    && moveFromIndex != fromIndex) {

                otherPieceCanMoveToSameSquare = true;
                ambiguityInFile |= (moveFromIndex % 8 == fromIndex % 8); // Check file ambiguity
                ambiguityInRank |= (moveFromIndex / 8 == fromIndex / 8); // Check rank ambiguity
            }
        }
        engine.redoMove();
        // Determine the type of ambiguity
        if (otherPieceCanMoveToSameSquare) {
            if (ambiguityInFile && ambiguityInRank) {
                return AmbiguityType.BOTH;
            } else if (ambiguityInFile) {
                return AmbiguityType.FILE;
            } else if (ambiguityInRank) {
                return AmbiguityType.RANK;
            } else {
                return AmbiguityType.NONE_BUT_SAME_TO_SQUARE;
            }
        }
        return AmbiguityType.NONE;
    }




    private String convertMoveToPgn(int moveInt) {
        int fromIndex = MoveHelper.deriveFromIndex(moveInt);
        int toIndex = MoveHelper.deriveToIndex(moveInt);
        String movePgn = "";

        // If the move is a castling move, handle it first
        if (MoveHelper.isCastlingMove(moveInt)) {
            if (Math.abs(fromIndex - toIndex) == 2) {
                movePgn = "O-O"; // Kingside castling
            } else {
                movePgn = "O-O-O"; // Queenside castling
            }
        } else {
            // Handle other types of moves (non-castling)
            PieceType pieceType = MoveHelper.intToPieceType(MoveHelper.derivePieceTypeBits(moveInt));
            PieceType promotionPieceType = MoveHelper.derivePromotionPieceTypeBits(moveInt) == 0 ? null : MoveHelper.intToPieceType(MoveHelper.derivePromotionPieceTypeBits(moveInt));
            String from = MoveHelper.convertIndexToString(fromIndex);
            String to = MoveHelper.convertIndexToString(toIndex);

            if (pieceType == PieceType.PAWN) {
                if (MoveHelper.isCapture(moveInt)) {
                    movePgn += from.charAt(0) + "x"; // Pawn captures
                }
            } else {
                movePgn = getPieceAbbreviation(pieceType);
                AmbiguityType ambiguityType = isMoveAmbiguous(pieceType, fromIndex, toIndex);

                if (ambiguityType != AmbiguityType.NONE) {
                    if (ambiguityType == AmbiguityType.BOTH) {
                        movePgn += from; // Include full 'from' position
                    } else if (ambiguityType == AmbiguityType.FILE || ambiguityType == AmbiguityType.NONE_BUT_SAME_TO_SQUARE) {
                        movePgn += from.charAt(1); // Include only 'from' rank
                    } else if (ambiguityType == AmbiguityType.RANK) {
                        movePgn += from.charAt(0); // Include only 'from' file
                    }
                }
                if (MoveHelper.isCapture(moveInt)) {
                    movePgn += "x";
                }
            }
            movePgn += to;

            // Handle pawn promotion
            if (promotionPieceType != null && pieceType == PieceType.PAWN && promotionPieceType != PieceType.PAWN) {
                movePgn += "=" + getPieceAbbreviation(promotionPieceType);
            }
        }

        // Check for check and checkmate after making the move in the engine, applicable for all moves
        if (engine.getGameState().isInStateCheck()) {
            movePgn += "+";
        }
        if (engine.getGameState().isInStateCheckMate()) {
            movePgn += "#";
        }

        return movePgn;
    }

    private String getPieceAbbreviation(PieceType pieceType) {
        return switch (pieceType) {
            case PAWN -> "";
            case KNIGHT -> "N";
            case BISHOP -> "B";
            case ROOK -> "R";
            case QUEEN -> "Q";
            case KING -> "K";
        };
    }
}