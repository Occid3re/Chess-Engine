package julius.game.chessengine.board;

import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.utils.Color;
import lombok.Getter;

public class Move {

    //pieceType
    //001 -> Pawn, 010 -> Knight, 011 -> Bishop, 100 -> Rook, 101 -> Queen, 110 -> King

    //isCastling
    //special property (sp)
    //111 capture/castling/enpassant --> cannot be true for a move at once
    //capture & enpassant --> 101
    //capture -> 100
    //castling -> 010
    //is normal move 000
    //4 possible scenarios normal move: 00, capture: 01, castling: 10, enpassant: 11

    //promotion pieceType (pp)
    //<000> no promotion
    //other is same like pieceType


    //captured pieceType
    //<000> no capture
    //same like pieceType


    //  from      to   pieceColor   pieceType      sp     pp   isKingsfirstMove isRooksFirstMove  isCheck  isCheckMate   isAmbigousMove
    //<111111> <111111>   <1>         <111>       <11>   <000>        <1>             <1>           <1>         <1>           <1>

    // Getters for all the fields
    @Getter
    private final Position from;
    @Getter
    private final Position to;
    @Getter
    private final PieceType pieceType;
    @Getter
    private final boolean colorWhite; // Color of the piece being moved
    private final boolean isCapture;
    private final boolean isCastlingMove;
    private final boolean isEnPassantMove;
    @Getter
    private final PieceType promotionPieceType; // This can be null if no promotion
    @Getter
    private final PieceType capturedPieceType;

    private final boolean isKingFirstMove;
    private final boolean isRookFirstMove;

    public Move(Position from, Position to, PieceType pieceType, boolean isWhite,
                boolean isCapture, boolean isCastlingMove, boolean isEnPassantMove, PieceType promotionPieceType, PieceType capturedPieceType,
                boolean isKingFirstMove, boolean isRookFirstMove) {
        this.from = from;
        this.to = to;
        this.pieceType = pieceType;
        this.colorWhite = isWhite;
        this.isCapture = isCapture || isEnPassantMove; // Capture is true if it's a regular capture or an en passant capture
        this.isCastlingMove = isCastlingMove;
        this.isEnPassantMove = isEnPassantMove;
        this.promotionPieceType = promotionPieceType;
        this.capturedPieceType = isCapture ? capturedPieceType : null;
        this.isKingFirstMove = isKingFirstMove;
        this.isRookFirstMove = isRookFirstMove;
    }

    public Move(Move originalMove) {
        this.from = new Position(originalMove.getFrom()); // Assuming Position also has a copy constructor
        this.to = new Position(originalMove.getTo()); // Assuming Position also has a copy constructor
        this.pieceType = originalMove.getPieceType();
        this.colorWhite = originalMove.isColorWhite();
        this.isCapture = originalMove.isCapture();
        this.isCastlingMove = originalMove.isCastlingMove();
        this.isEnPassantMove = originalMove.isEnPassantMove();
        this.promotionPieceType = originalMove.getPromotionPieceType();
        this.capturedPieceType = originalMove.getCapturedPieceType();
        this.isKingFirstMove = originalMove.isKingFirstMove();
        this.isRookFirstMove = originalMove.isRookFirstMove();
    }

    public boolean isCapture() {
        return isCapture;
    }

    public boolean isCastlingMove() {
        return isCastlingMove;
    }

    public boolean isEnPassantMove() {
        return isEnPassantMove;
    }

    public boolean isPromotionMove() {
        return promotionPieceType != null;
    }

    public boolean isKingFirstMove() {
        return isKingFirstMove;
    }

    public boolean isRookFirstMove() {
        return isRookFirstMove;
    }


    // You may want to override toString() for easy move printing.
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        // For all pieces except pawns, include the piece type
        if (pieceType != PieceType.PAWN) {
            sb.append(pieceType.getNotation()); // Assuming getNotation() returns a single character like 'R' for Rook, etc.
        }

        // Include the origin square if needed to disambiguate
        // This part can be complex as you need to check whether other pieces of the same type could move to the same square.
        // It's often omitted in simpler implementations.

        // For captures, include 'x', and for pawns include the file of departure
        if (isCapture) {
            if (pieceType == PieceType.PAWN) {
                sb.append(from.getX()); // getFile() should return the file ('a' through 'h') of the position
            }
            sb.append("x");
        }

        // Add the destination square
        sb.append(to);

        // If it's a pawn promotion, include the promotion piece type
        if (isPromotionMove()) {
            sb.append("=").append(promotionPieceType.getNotation());
        }

        // For castling, use the special notation
        if (isCastlingMove) {
            if (to.getX() == 'g') { // Assuming kingside castling results in the king on the 'g' file
                sb.setLength(0); // Clear the StringBuilder
                sb.append("O-O");
            } else if (to.getX() == 'c') { // Assuming queenside castling results in the king on the 'c' file
                sb.setLength(0); // Clear the StringBuilder
                sb.append("O-O-O");
            }
        }

        // Optionally, you can add check/checkmate indicators, but that requires more game state information.

        return sb.toString();
    }

    public static Move convertIntToMove(int moveInt) {
        int fromIndex = moveInt & 0x3F; // Extract the first 6 bits
        Position from = bitIndexToPosition(fromIndex);

        int toIndex = (moveInt >> 6) & 0x3F; // Extract the next 6 bits
        Position to = bitIndexToPosition(toIndex);

        int pieceTypeBits = (moveInt >> 12) & 0x07; // Extract the next 3 bits
        PieceType pieceType = intToPieceType(pieceTypeBits);

        boolean isWhite = (moveInt & (1 << 15)) != 0; // Extract the color bit

        int specialProperty = (moveInt >> 16) & 0x03; // Extract the next 2 bits
        boolean isCapture = (specialProperty & 0x01) != 0;
        boolean isEnPassantMove = specialProperty == 3;
        boolean isCastlingMove = specialProperty == 2;

        int promotionPieceTypeBits = (moveInt >> 18) & 0x07; // Extract the next 3 bits
        PieceType promotionPieceType = intToPieceType(promotionPieceTypeBits);
        if (promotionPieceTypeBits == 0) {
            promotionPieceType = null; // No promotion
        }

        int capturedPieceTypeBits = (moveInt >> 21) & 0x07; // Extract the next 3 bits
        PieceType capturedPieceType = intToPieceType(capturedPieceTypeBits);
        if (capturedPieceTypeBits == 0) {
            capturedPieceType = null; // No capture
        }

        boolean isKingFirstMove = (moveInt & (1 << 24)) != 0; // Extract the king's first move bit
        boolean isRookFirstMove = (moveInt & (1 << 25)) != 0; // Extract the rook's first move bit

        return new Move(from, to, pieceType, isWhite, isCapture, isCastlingMove, isEnPassantMove, promotionPieceType, capturedPieceType, isKingFirstMove, isRookFirstMove);
    }

    private static Position bitIndexToPosition(int bitIndex) {
        char file = (char) ('a' + (bitIndex % 8));
        int rank = 1 + (bitIndex / 8);
        return new Position(file, rank);
    }

    private static PieceType intToPieceType(int intVal) {
        return switch (intVal) {
            case 1 -> PieceType.PAWN;
            case 2 -> PieceType.KNIGHT;
            case 3 -> PieceType.BISHOP;
            case 4 -> PieceType.ROOK;
            case 5 -> PieceType.QUEEN;
            case 6 -> PieceType.KING;
            default -> null; // Assuming null is an acceptable default, adjust as necessary
        };
    }
}
