package julius.game.chessengine.board;

import julius.game.chessengine.figures.PieceType;

public class MoveHelper {

    public static boolean isWhitesMove(int move) {
        return (move & (1 << 15)) != 0;
    }

    public static int deriveFromIndex(int move) {
        return move & 0x3F;
    }

    public static int deriveToIndex(int move) {
        return (move >> 6) & 0x3F;
    }

    public static int derivePieceTypeBits(int move) {
        return (move >> 12) & 0x07;
    }

    public static int deriveCapturedPieceTypeBits(int move) {
        return (move >> 21) & 0x07;
    }

    public static int derivePromotionPieceTypeBits(int move) {
        return (move >> 18) & 0x07;
    }

    public static int deriveSpecialProperty(int move) {
        return (move >> 16) & 0x03;
    }

    public static boolean isCapture(int move) {
        return (deriveSpecialProperty(move) & 0x01) != 0;
    }

    public static boolean isEnPassantMove(int move) {
        return deriveSpecialProperty(move) == 3;
    }

    public static boolean isCastlingMove(int move) {
        return deriveSpecialProperty(move) == 2;
    }

    public static boolean isKingFirstMove(int move) {
        return (move & (1 << 24)) != 0;
    }

    public static boolean isRookFirstMove(int move) {
        return (move & (1 << 25)) != 0;
    }

    public static int deriveLastMoveDoubleStepPawnIndex(int move) {
        return (move >> 26) & 0x3F; // Extract the 6 bits starting from the 26th bit
    }

    public static int createMoveInt(int fromIndex, int toIndex, PieceType pieceType, boolean isWhite, boolean isCapture, boolean isCastlingMove, boolean isEnPassantMove, PieceType promotionPieceType, PieceType capturedPieceType, boolean isKingFirstMove, boolean isRookFirstMove, int lastMoveDoubleStepPawnIndex) {
        int moveInt = 0;
        moveInt |= fromIndex; // 6 bits for 'from' position
        moveInt |= toIndex << 6; // 6 bits for 'to' position, shifted by 6 bits

        int pieceTypeBits = pieceTypeToInt(pieceType); // 3 bits for piece type
        moveInt |= pieceTypeBits << 12; // Shifted by 12 bits

        if (isWhite) {
            moveInt |= 1 << 15; // 1 bit for color, shifted by 15 bits
        }

        int specialProperty = (isCapture ? 1 : 0) | (isCastlingMove ? 2 : 0) | (isEnPassantMove ? 3 : 0);
        moveInt |= specialProperty << 16; // Shifted by 16 bits

        if (promotionPieceType != null) {
            moveInt |= pieceTypeToInt(promotionPieceType) << 18; // 3 bits for promotion piece type, shifted by 18 bits
        } else {
            moveInt &= ~(0x07 << 18); // Ensure promotion bits are set to 000 if no promotion
        }

        if (capturedPieceType != null) {
            moveInt |= pieceTypeToInt(capturedPieceType) << 21; // 3 bits for captured piece type, shifted by 21 bits
        } else {
            moveInt &= ~(0x07 << 21); // Ensure captured piece bits are set to 000 if no piece is captured
        }

        if (isKingFirstMove) {
            moveInt |= 1 << 24; // 1 bit for king's first move, shifted by 24 bits
        }

        if (isRookFirstMove) {
            moveInt |= 1 << 25; // 1 bit for rook's first move, shifted by 25 bits
        }

        moveInt |= lastMoveDoubleStepPawnIndex << 26; // Shifted by 26 bits

        return moveInt;
    }

    public static int convertStringToIndex(String positionStr) {
        if (positionStr.length() != 2) {
            throw new IllegalArgumentException("Invalid position string: " + positionStr);
        }
        char fileChar = positionStr.charAt(0);
        int rank = Character.getNumericValue(positionStr.charAt(1));

        if (fileChar < 'a' || fileChar > 'h' || rank < 1 || rank > 8) {
            throw new IllegalArgumentException("Position out of bounds: " + positionStr);
        }

        int file = fileChar - 'a'; // Convert file to 0-7 range
        rank = rank - 1; // Convert rank to 0-based index

        return rank * 8 + file;
    }

    public static String convertIndexToString(int index) {
        if (index < 0 || index >= 64) {
            throw new IllegalArgumentException("Index out of bounds: " + index);
        }

        int rank = index / 8; // Determine the rank (0 to 7)
        int file = index % 8; // Determine the file (0 to 7)

        char fileChar = (char) ('a' + file); // Convert file to 'a' to 'h'
        int rankNumber = rank + 1; // Convert rank to 1-based index

        return fileChar + String.valueOf(rankNumber);
    }

    public static int pieceTypeToInt(PieceType pieceType) {
        return switch (pieceType) {
            case PAWN -> 1;
            case KNIGHT -> 2;
            case BISHOP -> 3;
            case ROOK -> 4;
            case QUEEN -> 5;
            case KING -> 6;
        };
    }

    public static PieceType intToPieceType(int pieceTypeInt) {
        return switch (pieceTypeInt) {
            case 1 -> PieceType.PAWN;
            case 2 -> PieceType.KNIGHT;
            case 3 -> PieceType.BISHOP;
            case 4 -> PieceType.ROOK;
            case 5 -> PieceType.QUEEN;
            case 6 -> PieceType.KING;
            default -> throw new IllegalArgumentException("Invalid piece type integer: " + pieceTypeInt);
        };
    }

    public static boolean isPawnPromotionMove(Integer moveInt) {
        int promotionPieceTypeBits = derivePromotionPieceTypeBits(moveInt);
        return promotionPieceTypeBits != 0;
    }
}
