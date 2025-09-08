package julius.game.chessengine.board;

import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.utils.Color;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class FEN {
    private final String renderBoard;

    public static FEN translateBoardToFEN(BitBoard board) {
        StringBuilder fenBuilder = new StringBuilder();
        for (int rank = 8; rank >= 1; rank--) {
            int emptyCount = 0;
            for (int file = 0; file < 8; file++) {
                int index = (rank - 1) * 8 + file;
                PieceType pieceType = board.getPieceTypeAtIndex(index);
                Color color = board.getPieceColorAtIndex(index);

                if (pieceType != null) {
                    if (emptyCount > 0) {
                        fenBuilder.append(emptyCount);
                        emptyCount = 0;
                    }
                    char fenChar = getFenCharacter(pieceType, color);
                    fenBuilder.append(fenChar);
                } else {
                    emptyCount++;
                }
            }
            if (emptyCount > 0) {
                fenBuilder.append(emptyCount);
            }
            if (rank > 1) {
                fenBuilder.append('/');
            }
        }
        // You would add the active color, castling availability, en passant target square,
        // halfmove clock, and fullmove number after this
        return new FEN(fenBuilder.toString());
    }

    private static char getFenCharacter(PieceType pieceType, Color color) {
        char fenChar = pieceType.getNotation();
        if (color == Color.BLACK) {
            fenChar = Character.toLowerCase(fenChar);
        }
        return fenChar;
    }

    public static BitBoard translateFENtoBitBoard(String fen) {
        String[] parts = fen.split(" ");
        String[] ranks = parts[0].split("/");

        long whitePawns = 0, blackPawns = 0, whiteKnights = 0, blackKnights = 0, whiteBishops = 0, blackBishops = 0;
        long whiteRooks = 0, blackRooks = 0, whiteQueens = 0, blackQueens = 0, whiteKing = 0, blackKing = 0;
        long whitePieces = 0, blackPieces = 0, allPieces = 0;

        for (int i = 0; i < ranks.length; i++) {
            int file = 0;
            for (char c : ranks[i].toCharArray()) {
                if (Character.isDigit(c)) {
                    file += Character.getNumericValue(c);
                } else {
                    int rank = 8 - i;
                    int index = (rank - 1) * 8 + file;
                    long bit = 1L << index;

                    switch (Character.toLowerCase(c)) {
                        case 'p':
                            if (c == 'p') blackPawns |= bit;
                            else whitePawns |= bit;
                            break;
                        case 'n':
                            if (c == 'n') blackKnights |= bit;
                            else whiteKnights |= bit;
                            break;
                        case 'b':
                            if (c == 'b') blackBishops |= bit;
                            else whiteBishops |= bit;
                            break;
                        case 'r':
                            if (c == 'r') blackRooks |= bit;
                            else whiteRooks |= bit;
                            break;
                        case 'q':
                            if (c == 'q') blackQueens |= bit;
                            else whiteQueens |= bit;
                            break;
                        case 'k':
                            if (c == 'k') blackKing |= bit;
                            else whiteKing |= bit;
                            break;
                    }

                    if (Character.isUpperCase(c)) whitePieces |= bit;
                    else blackPieces |= bit;
                    allPieces |= bit;

                    file++;
                }
            }
        }

        boolean whitesTurn = parts[1].equals("w");
        // You'll need to parse the other FEN parts like castling availability, en passant, etc.

        // Set castling and en passant flags...
        boolean whiteKingMoved = !parts[2].contains("K");
        boolean blackKingMoved = !parts[2].contains("k");
        boolean whiteRookA1Moved = !parts[2].contains("Q");
        boolean whiteRookH1Moved = !parts[2].contains("K");
        boolean blackRookA8Moved = !parts[2].contains("q");
        boolean blackRookH8Moved = !parts[2].contains("k");

        int lastMoveDoubleStepPawnIndex = 0;
        if (!parts[3].equals("-")) {
            char fileChar = parts[3].charAt(0);
            int file = fileChar - 'a'; // Convert file to 0-7 range
            int rank = Character.getNumericValue(parts[3].charAt(1)) - 1; // Convert rank to 0-based index

            lastMoveDoubleStepPawnIndex = rank * 8 + file;
        }

        // Constants for starting positions (assuming 0-based indexing)
        boolean whiteKingHasCastled = false;
        boolean blackKingHasCastled = false;

        if (whiteKingMoved) {
            // If white king has moved, check if it's in a typical castled position
            whiteKingHasCastled = ((whiteKing & (1L << 6)) != 0) || ((whiteKing & (1L << 2)) != 0);
        }
        if (blackKingMoved) {
            // If black king has moved, check if it's in a typical castled position
            blackKingHasCastled = ((blackKing & (1L << 62)) != 0) || ((blackKing & (1L << 58)) != 0);
        }

        // Pass these inferred values to the BitBoard constructor
        return new BitBoard(whitesTurn, whitePawns, blackPawns, whiteKnights, blackKnights, whiteBishops, blackBishops, whiteRooks, blackRooks, whiteQueens, blackQueens, whiteKing, blackKing, whitePieces, blackPieces, allPieces, lastMoveDoubleStepPawnIndex, whiteKingMoved, blackKingMoved, whiteRookA1Moved, whiteRookH1Moved, blackRookA8Moved, blackRookH8Moved, whiteKingHasCastled, blackKingHasCastled);
    }

}
