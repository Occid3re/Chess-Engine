package julius.game.chessengine.board;

import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.KnightHelper;
import julius.game.chessengine.helper.RookHelper;
import julius.game.chessengine.helper.ZobristTable;
import julius.game.chessengine.utils.Color;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.Arrays;
import java.util.Objects;

import static julius.game.chessengine.board.MoveHelper.createMoveInt;
import static julius.game.chessengine.helper.BitHelper.*;
import static julius.game.chessengine.helper.BitboardHelper.lineBetweenIndices;
import static julius.game.chessengine.helper.KingHelper.KING_ATTACKS;
import static julius.game.chessengine.helper.KnightHelper.knightMoveTable;
import static julius.game.chessengine.helper.PawnMoveTables.PAWN_ATTACKS;
import static julius.game.chessengine.helper.PawnMoveTables.PAWN_PUSHES;

@Log4j2
@Getter
public class BitBoard {

    BishopHelper bishopHelper = BishopHelper.getInstance();
    RookHelper rookHelper = RookHelper.getInstance();

    public boolean whitesTurn = true;
    // Add score field to the BitBoard class
    private long whitePawns = 0L;
    private long blackPawns = 0L;
    private long whiteKnights = 0L;
    private long blackKnights = 0L;
    private long whiteBishops = 0L;
    private long blackBishops = 0L;
    private long whiteRooks = 0L;
    private long blackRooks = 0L;
    private long whiteQueens = 0L;
    private long blackQueens = 0L;
    private long whiteKing = 0L;
    private long blackKing = 0L;
    private long whitePieces = 0L;
    private long blackPieces = 0L;
    private long allPieces = 0L;
    /**
     * Bitboards caching the squares attacked by each side.
     */
    private long whiteAttackMap = 0L;
    private long blackAttackMap = 0L;
    private PieceType[] pieceBoard = new PieceType[64];

    // Reusable buffer for move generation to avoid frequent allocations.
    private final MoveList moveGenerationBuffer = new MoveList();

    // This variable needs to be set whenever a move is made
    //TODO only write to it if en passant is possible then you can also hash it
    @Getter
    private int lastMoveDoubleStepPawnIndex;

    // Flags to track if the king and rooks have moved
    private boolean whiteKingMoved = false;
    private boolean blackKingMoved = false;
    private boolean whiteRookA1Moved = false;
    private boolean whiteRookH1Moved = false;
    private boolean blackRookA8Moved = false;
    private boolean blackRookH8Moved = false;

    //Warning those booleans do not fully work for FEN imported games, because it's impossible to determine if a king castled
    //I just need those values to give the Engine a better score if it castles
    private boolean whiteKingHasCastled = false;
    private boolean blackKingHasCastled = false;

    public BitBoard(boolean whitesTurn, long whitePawns, long blackPawns, long whiteKnights, long blackKnights, long whiteBishops, long blackBishops, long whiteRooks, long blackRooks, long whiteQueens, long blackQueens, long whiteKing, long blackKing, long whitePieces, long blackPieces, long allPieces, int lastMoveDoubleStepPawnIndex, boolean whiteKingMoved, boolean blackKingMoved, boolean whiteRookA1Moved, boolean whiteRookH1Moved, boolean blackRookA8Moved, boolean blackRookH8Moved, boolean whiteKingHasCastled, boolean blackKingHasCastled) {
        this.whitesTurn = whitesTurn;
        this.whitePawns = whitePawns;
        this.blackPawns = blackPawns;
        this.whiteKnights = whiteKnights;
        this.blackKnights = blackKnights;
        this.whiteBishops = whiteBishops;
        this.blackBishops = blackBishops;
        this.whiteRooks = whiteRooks;
        this.blackRooks = blackRooks;
        this.whiteQueens = whiteQueens;
        this.blackQueens = blackQueens;
        this.whiteKing = whiteKing;
        this.blackKing = blackKing;
        this.whitePieces = whitePieces;
        this.blackPieces = blackPieces;
        this.allPieces = allPieces;
        this.lastMoveDoubleStepPawnIndex = lastMoveDoubleStepPawnIndex;
        this.whiteKingMoved = whiteKingMoved;
        this.blackKingMoved = blackKingMoved;
        this.whiteRookA1Moved = whiteRookA1Moved;
        this.whiteRookH1Moved = whiteRookH1Moved;
        this.blackRookA8Moved = blackRookA8Moved;
        this.blackRookH8Moved = blackRookH8Moved;
        this.whiteKingHasCastled = whiteKingHasCastled;
        this.blackKingHasCastled = blackKingHasCastled;
        initPieceBoardFromBitboards();
    }

    public BitBoard() {
        setInitialPosition();
    }

    public BitBoard(BitBoard other) {
        // Copying all the long fields representing the pieces
        this.bishopHelper = other.bishopHelper;
        this.rookHelper = other.rookHelper;

        this.whitePawns = other.whitePawns;
        this.blackPawns = other.blackPawns;
        this.whiteKnights = other.whiteKnights;
        this.blackKnights = other.blackKnights;
        this.whiteBishops = other.whiteBishops;
        this.blackBishops = other.blackBishops;
        this.whiteRooks = other.whiteRooks;
        this.blackRooks = other.blackRooks;
        this.whiteQueens = other.whiteQueens;
        this.blackQueens = other.blackQueens;
        this.whiteKing = other.whiteKing;
        this.blackKing = other.blackKing;

        // Copying the combined bitboards
        this.whitePieces = other.whitePieces;
        this.blackPieces = other.blackPieces;
        this.allPieces = other.allPieces;

        // Copying the flags
        this.whiteKingMoved = other.whiteKingMoved;
        this.blackKingMoved = other.blackKingMoved;
        this.whiteRookA1Moved = other.whiteRookA1Moved;
        this.whiteRookH1Moved = other.whiteRookH1Moved;
        this.blackRookA8Moved = other.blackRookA8Moved;
        this.blackRookH8Moved = other.blackRookH8Moved;

        this.whitesTurn = other.whitesTurn;

        this.lastMoveDoubleStepPawnIndex = other.lastMoveDoubleStepPawnIndex;

        this.blackKingHasCastled = other.blackKingHasCastled;
        this.whiteKingHasCastled = other.whiteKingHasCastled;

        this.pieceBoard = Arrays.copyOf(other.pieceBoard, other.pieceBoard.length);
    }

    public void setLastMoveDoubleStepPawnIndex(int index) {
        this.lastMoveDoubleStepPawnIndex = index;
    }


    public boolean hasInsufficientMaterial() {
        // Early return if any side has pawns, rooks, or queens, as these can achieve checkmate
        if ((whitePawns | blackPawns | whiteRooks | blackRooks | whiteQueens | blackQueens) != 0) {
            return false;
        }

        // Count knights and bishops for both sides
        int whiteMinorPieces = Long.bitCount(whiteKnights) + Long.bitCount(whiteBishops);
        int blackMinorPieces = Long.bitCount(blackKnights) + Long.bitCount(blackBishops);

        // Check if both sides have insufficient material
        return (whiteMinorPieces <= 1) && (blackMinorPieces <= 1);
    }

    public MoveList getAllCurrentPossibleMoves() {
        return generateAllPossibleMoves(whitesTurn);
    }

    // Method to set up the initial position
    private void setInitialPosition() {
        // Setting white pawns on the second rank
        whitePawns = 0x000000000000FF00L;
        // Setting black pawns on the seventh rank
        blackPawns = 0x00FF000000000000L;

        // Setting white knights on b1 and g1
        whiteKnights = (1L << bitIndex('b', 1)) | (1L << bitIndex('g', 1));
        // Setting black knights on b8 and g8
        blackKnights = (1L << bitIndex('b', 8)) | (1L << bitIndex('g', 8));

        // Setting white bishops on c1 and f1
        whiteBishops = (1L << bitIndex('c', 1)) | (1L << bitIndex('f', 1));
        // Setting black bishops on c8 and f8
        blackBishops = (1L << bitIndex('c', 8)) | (1L << bitIndex('f', 8));

        // Setting white rooks on a1 and h1
        whiteRooks = (1L << bitIndex('a', 1)) | (1L << bitIndex('h', 1));
        // Setting black rooks on a8 and h8
        blackRooks = (1L << bitIndex('a', 8)) | (1L << bitIndex('h', 8));

        // Setting white queen on d1
        whiteQueens = 1L << bitIndex('d', 1);
        // Setting black queen on d8
        blackQueens = 1L << bitIndex('d', 8);

        // Setting white king on e1
        whiteKing = 1L << bitIndex('e', 1);
        // Setting black king on e8
        blackKing = 1L << bitIndex('e', 8);

        // Setting all white pieces by combining the bitboards
        whitePieces = whitePawns | whiteKnights | whiteBishops | whiteRooks | whiteQueens | whiteKing;
        // Setting all black pieces by combining the bitboards
        blackPieces = blackPawns | blackKnights | blackBishops | blackRooks | blackQueens | blackKing;

        // Setting all pieces on the board
        allPieces = whitePieces | blackPieces;

        lastMoveDoubleStepPawnIndex = 0;
        initPieceBoardFromBitboards();
    }

    private void initPieceBoardFromBitboards() {
        Arrays.fill(pieceBoard, null);
        setPieces(whitePawns, PieceType.PAWN);
        setPieces(blackPawns, PieceType.PAWN);
        setPieces(whiteKnights, PieceType.KNIGHT);
        setPieces(blackKnights, PieceType.KNIGHT);
        setPieces(whiteBishops, PieceType.BISHOP);
        setPieces(blackBishops, PieceType.BISHOP);
        setPieces(whiteRooks, PieceType.ROOK);
        setPieces(blackRooks, PieceType.ROOK);
        setPieces(whiteQueens, PieceType.QUEEN);
        setPieces(blackQueens, PieceType.QUEEN);
        setPieces(whiteKing, PieceType.KING);
        setPieces(blackKing, PieceType.KING);
    }

    private void setPieces(long bitboard, PieceType type) {
        while (bitboard != 0) {
            int index = Long.numberOfTrailingZeros(bitboard);
            pieceBoard[index] = type;
            bitboard &= bitboard - 1;
        }
    }

    // Method to get the bitboard for a specific piece type and color
    public long intToPiecesBitboard(int pieceTypeBits, boolean isWhite) {
        if (isWhite) {
            return switch (pieceTypeBits) {
                case 1 -> whitePawns;
                case 2 -> whiteKnights;
                case 3 -> whiteBishops;
                case 4 -> whiteRooks;
                case 5 -> whiteQueens;
                case 6 -> whiteKing;
                default -> 0;
            };
        } else {
            return switch (pieceTypeBits) {
                case 1 -> blackPawns;
                case 2 -> blackKnights;
                case 3 -> blackBishops;
                case 4 -> blackRooks;
                case 5 -> blackQueens;
                case 6 -> blackKing;
                default -> 0;
            };
        }
    }

    private PieceType pieceTypeFromBits(int pieceTypeBits) {
        return switch (pieceTypeBits) {
            case 1 -> PieceType.PAWN;
            case 2 -> PieceType.KNIGHT;
            case 3 -> PieceType.BISHOP;
            case 4 -> PieceType.ROOK;
            case 5 -> PieceType.QUEEN;
            case 6 -> PieceType.KING;
            default -> null;
        };
    }

    private void setBitboardForPiece(int pieceTypeBits, boolean isWhite, long bitboard) {
        if (isWhite) {
            switch (pieceTypeBits) {
                case 1 -> whitePawns = bitboard;
                case 2 -> whiteKnights = bitboard;
                case 3 -> whiteBishops = bitboard;
                case 4 -> whiteRooks = bitboard;
                case 5 -> whiteQueens = bitboard;
                case 6 -> whiteKing = bitboard;
                default -> throw new IllegalArgumentException("Unknown piece type: " + pieceTypeBits);
            }

        } else {
            switch (pieceTypeBits) {
                case 1 -> blackPawns = bitboard;
                case 2 -> blackKnights = bitboard;
                case 3 -> blackBishops = bitboard;
                case 4 -> blackRooks = bitboard;
                case 5 -> blackQueens = bitboard;
                case 6 -> blackKing = bitboard;
                default -> throw new IllegalArgumentException("Unknown piece type: " + pieceTypeBits);
            }
        }

        // After setting the bitboard, update the aggregated bitboards
        updateAggregatedBitboards();
    }
    // Call these methods within the movePiece method when a king or rook moves

    private void markKingAsMoved(boolean isWhite) {
        if (isWhite) {
            whiteKingMoved = true;
        } else {
            blackKingMoved = true;
        }
    }

    public MoveList generateAllPossibleMoves(boolean whitesTurn) {
        // Reuse the internal buffer to cut down on object creation and garbage
        // collection pressure during heavy search operations.
        MoveList moves = moveGenerationBuffer;
        moves.clear();

        // Pre-compute attack maps for both sides once and reuse them during move
        // generation.  This avoids repeatedly recalculating the same information
        // for castling checks and attack lookups.
        whiteAttackMap = generateAttackBitboard(true);
        blackAttackMap = generateAttackBitboard(false);

        generatePawnMoves(whitesTurn, moves);
        generateKnightMoves(whitesTurn, moves);
        generateBishopMoves(whitesTurn, moves);
        generateRookMoves(whitesTurn, moves);
        generateQueenMoves(whitesTurn, moves);
        generateKingMoves(whitesTurn, moves);

        return moves;
    }

    /**
     * Generates a bitboard of all squares attacked by the given side.
     */
    public long generateAttackBitboard(boolean colorWhite) {
        long attacks = 0L;

        // Pawn attacks
        if (colorWhite) {
            attacks |= ((whitePawns & ~FileMasks[0]) << 7);
            attacks |= ((whitePawns & ~FileMasks[7]) << 9);
        } else {
            attacks |= ((blackPawns & ~FileMasks[7]) >>> 7);
            attacks |= ((blackPawns & ~FileMasks[0]) >>> 9);
        }

        // Knight attacks
        long knights = colorWhite ? whiteKnights : blackKnights;
        while (knights != 0) {
            int index = Long.numberOfTrailingZeros(knights);
            attacks |= knightAttackBitmask(index);
            knights &= knights - 1;
        }

        // Bishop attacks
        long bishops = colorWhite ? whiteBishops : blackBishops;
        while (bishops != 0) {
            int index = Long.numberOfTrailingZeros(bishops);
            attacks |= bishopAttackBitmask(index);
            bishops &= bishops - 1;
        }

        // Rook attacks
        long rooks = colorWhite ? whiteRooks : blackRooks;
        while (rooks != 0) {
            int index = Long.numberOfTrailingZeros(rooks);
            attacks |= rookAttackBitmask(index);
            rooks &= rooks - 1;
        }

        // Queen attacks
        long queens = colorWhite ? whiteQueens : blackQueens;
        while (queens != 0) {
            int index = Long.numberOfTrailingZeros(queens);
            attacks |= queenAttackBitmask(index);
            queens &= queens - 1;
        }

        // King attacks
        long king = colorWhite ? whiteKing : blackKing;
        if (king != 0) {
            int index = Long.numberOfTrailingZeros(king);
            attacks |= KING_ATTACKS[index];
        }

        return attacks;
    }

    // Method to set the bitboard for a specific piece type and color
    void updateAggregatedBitboards() {
        whitePieces = whitePawns | whiteKnights | whiteBishops | whiteRooks | whiteQueens | whiteKing;
        blackPieces = blackPawns | blackKnights | blackBishops | blackRooks | blackQueens | blackKing;
        allPieces = whitePieces | blackPieces;
    }

    private void generatePawnMoves(boolean whitesTurn, MoveList moves) {
        long pawnBitboard = whitesTurn ? whitePawns : blackPawns;
        long pawns = pawnBitboard;
        long opponentPieces = whitesTurn ? blackPieces : whitePieces;
        long emptySquares = ~(whitePieces | blackPieces);
        int colorIndex = whitesTurn ? 0 : 1;

        while (pawns != 0) {
            int fromIndex = Long.numberOfTrailingZeros(pawns);
            pawns &= pawns - 1;

            long singlePush = PAWN_PUSHES[colorIndex][fromIndex] & emptySquares;
            if (singlePush != 0) {
                int toIndex = Long.numberOfTrailingZeros(singlePush);
                boolean isPromotion = whitesTurn ? toIndex >= 56 : toIndex < 8;
                if (isPromotion) {
                    addPromotionMoves(moves, fromIndex, toIndex, whitesTurn, false, null);
                } else {
                    moves.add(createMoveInt(fromIndex, toIndex, PieceType.PAWN, whitesTurn, false, false, false, null, null, false, false, lastMoveDoubleStepPawnIndex));
                }

                int rank = fromIndex / 8;
                if ((whitesTurn && rank == 1) || (!whitesTurn && rank == 6)) {
                    long doublePush = PAWN_PUSHES[colorIndex][toIndex] & emptySquares;
                    if (doublePush != 0) {
                        int doubleTo = Long.numberOfTrailingZeros(doublePush);
                        moves.add(createMoveInt(fromIndex, doubleTo, PieceType.PAWN, whitesTurn, false, false, false, null, null, false, false, lastMoveDoubleStepPawnIndex));
                    }
                }
            }

            long attacks = PAWN_ATTACKS[colorIndex][fromIndex] & opponentPieces;
            while (attacks != 0) {
                int toIndex = Long.numberOfTrailingZeros(attacks);
                PieceType capturedType = getPieceTypeAtIndex(toIndex);
                boolean isPromotion = whitesTurn ? toIndex >= 56 : toIndex < 8;
                if (isPromotion) {
                    addPromotionMoves(moves, fromIndex, toIndex, whitesTurn, true, capturedType);
                } else {
                    moves.add(createMoveInt(fromIndex, toIndex, PieceType.PAWN, whitesTurn, true, false, false, null, capturedType, false, false, lastMoveDoubleStepPawnIndex));
                }
                attacks &= attacks - 1;
            }
        }

        if (lastMoveDoubleStepPawnIndex != 0) {
            generateEnPassantMoves(moves, pawnBitboard, whitesTurn);
        }

    }

    private void generateEnPassantMoves(MoveList moves, long pawns, boolean whitesTurn) {
        int enPassantRank = whitesTurn ? 5 : 2;
        int fileIndexOfDoubleSteppedPawn = lastMoveDoubleStepPawnIndex % 8;
        int enPassantTargetIndex = (enPassantRank * 8) + fileIndexOfDoubleSteppedPawn;
        long enPassantTargetSquare = 1L << enPassantTargetIndex;
        long potentialEnPassantAttackers = pawns & RankMasks[whitesTurn ? 4 : 3];

        if (fileIndexOfDoubleSteppedPawn > 0) {
            long leftAttackers = potentialEnPassantAttackers & FileMasks[fileIndexOfDoubleSteppedPawn - 1];
            if (whitesTurn ?
                    ((leftAttackers << 9 & enPassantTargetSquare) != 0) :
                    ((leftAttackers >> 7 & enPassantTargetSquare) != 0)) {
                addEnPassantMove(moves, Long.numberOfTrailingZeros(leftAttackers), enPassantTargetIndex, whitesTurn);
            }
        }

        if (fileIndexOfDoubleSteppedPawn < 7) {
            long rightAttackers = potentialEnPassantAttackers & FileMasks[fileIndexOfDoubleSteppedPawn + 1];
            if (whitesTurn ?
                    ((rightAttackers << 7 & enPassantTargetSquare) != 0) :
                    ((rightAttackers >> 9 & enPassantTargetSquare) != 0)) {
                addEnPassantMove(moves, Long.numberOfTrailingZeros(rightAttackers), enPassantTargetIndex, whitesTurn);
            }
        }
    }


    private void addEnPassantMove(MoveList moves, int fromIndex, int toIndex, boolean whitesTurn) {
        moves.add(
                createMoveInt(fromIndex, toIndex, PieceType.PAWN, whitesTurn, true, false, true, null, PieceType.PAWN, false, false, lastMoveDoubleStepPawnIndex));
    }

    private void addPromotionMoves(MoveList moves, int fromIndex, int toIndex, boolean whitesTurn, boolean isCapture, PieceType capturedType) {
        PieceType[] promotionPieces = {PieceType.ROOK, PieceType.QUEEN, PieceType.BISHOP, PieceType.KNIGHT};
        for (PieceType promotionPiece : promotionPieces) {
            moves.add(createMoveInt(fromIndex, toIndex, PieceType.PAWN, whitesTurn, isCapture, false, false, promotionPiece, capturedType, false, false, lastMoveDoubleStepPawnIndex));
        }
    }


    private void generateKnightMoves(boolean whitesTurn, MoveList moves) {
        long knights = whitesTurn ? whiteKnights : blackKnights;
        long opponentPieces = whitesTurn ? blackPieces : whitePieces;
        long ownPieces = whitesTurn ? whitePieces : blackPieces;

        while (knights != 0) {
            int knightIndex = Long.numberOfTrailingZeros(knights);
            long potentialMoves = knightMoveTable[knightIndex] & ~ownPieces; // Pre-filter moves that land on own pieces

            while (potentialMoves != 0) {
                int targetIndex = Long.numberOfTrailingZeros(potentialMoves);
                boolean isCapture = (opponentPieces & (1L << targetIndex)) != 0;

                PieceType capturedPieceType = isCapture ? getPieceTypeAtIndex(targetIndex) : null;
                moves.add(createMoveInt(knightIndex, targetIndex, PieceType.KNIGHT, whitesTurn, isCapture, false, false, null, capturedPieceType, false, false, lastMoveDoubleStepPawnIndex));

                potentialMoves &= potentialMoves - 1; // Clear the lowest set bit
            }

            knights &= knights - 1; // Clear the lowest set bit
        }
    }


    private void generateBishopMoves(boolean isWhite, MoveList moves) {
        long bishops = isWhite ? whiteBishops : blackBishops;
        long ownPieces = isWhite ? whitePieces : blackPieces;
        long opponentPieces = isWhite ? blackPieces : whitePieces;

        while (bishops != 0) {
            int bishopSquare = Long.numberOfTrailingZeros(bishops);
            bishops &= bishops - 1; // Remove the least significant bit representing a bishop

            long occupancy = allPieces & bishopHelper.bishopMasks[bishopSquare];
            long attacks = bishopHelper.calculateMovesUsingBishopMagic(bishopSquare, occupancy) & ~ownPieces;

            while (attacks != 0) {
                int targetSquare = Long.numberOfTrailingZeros(attacks);
                attacks &= attacks - 1; // Remove the least significant bit representing an attack


                boolean isCapture = (opponentPieces & (1L << targetSquare)) != 0;
                moves.add(createMoveInt(bishopSquare, targetSquare, PieceType.BISHOP, isWhite, isCapture, false, false, null, isCapture ? getPieceTypeAtIndex(targetSquare) : null, false, false, lastMoveDoubleStepPawnIndex));
            }
        }
    }

    private void generateRookMoves(boolean whitesTurn, MoveList moves) {
        long rooks = whitesTurn ? whiteRooks : blackRooks;
        long ownPieces = whitesTurn ? whitePieces : blackPieces;
        long opponentPieces = whitesTurn ? blackPieces : whitePieces;

        while (rooks != 0) {
            int rookSquare = Long.numberOfTrailingZeros(rooks);
            rooks &= rooks - 1; // Remove the least significant bit representing a rook

            // Use RookHelper to calculate rook moves using magic bitboards
            long occupancy = allPieces & rookHelper.rookMasks[rookSquare];
            long attacks = rookHelper.calculateMovesUsingRookMagic(rookSquare, occupancy) & ~ownPieces;

            while (attacks != 0) {
                int targetSquare = Long.numberOfTrailingZeros(attacks);
                attacks &= attacks - 1; // Remove the least significant bit representing an attack
                boolean isFirstRookMove = !hasRookMoved(rookSquare);
                boolean isCapture = (opponentPieces & (1L << targetSquare)) != 0;
                moves.add(createMoveInt(rookSquare, targetSquare, PieceType.ROOK, whitesTurn, isCapture, false, false, null, isCapture ? getPieceTypeAtIndex(targetSquare) : null, false, isFirstRookMove, lastMoveDoubleStepPawnIndex));
            }
        }
    }

    private void generateQueenMoves(boolean whitesTurn, MoveList moves) {
        long queens = whitesTurn ? whiteQueens : blackQueens;
        long ownPieces = whitesTurn ? whitePieces : blackPieces;
        long opponentPieces = whitesTurn ? blackPieces : whitePieces;

        while (queens != 0) {
            int queenSquare = Long.numberOfTrailingZeros(queens);
            queens &= queens - 1;
            long occupancyBishop = allPieces & bishopHelper.bishopMasks[queenSquare];
            long occupancyRook = allPieces & rookHelper.rookMasks[queenSquare];

            long attacks = (
                    bishopHelper.calculateMovesUsingBishopMagic(queenSquare, occupancyBishop) |
                            rookHelper.calculateMovesUsingRookMagic(queenSquare, occupancyRook)
            ) & ~ownPieces;
            while (attacks != 0) {
                int targetSquare = Long.numberOfTrailingZeros(attacks);
                attacks &= attacks - 1; // Remove the least significant bit representing an attack
                boolean isCapture = (opponentPieces & (1L << targetSquare)) != 0;
                moves.add(createMoveInt(queenSquare, targetSquare, PieceType.QUEEN, whitesTurn, isCapture, false, false, null, isCapture ? getPieceTypeAtIndex(targetSquare) : null, false, false, lastMoveDoubleStepPawnIndex));
            }
        }
    }

    private void generateKingMoves(boolean whitesTurn, MoveList moves) {
        long kingBitboard = whitesTurn ? whiteKing : blackKing;
        if (kingBitboard == 0L) {
            return; // No king present for the given color; cannot generate moves
        }
        int kingPositionIndex = Long.numberOfTrailingZeros(kingBitboard);
        long kingAttacks = KING_ATTACKS[kingPositionIndex];
        boolean isFirstKingMove = hasKingNotMoved(whitesTurn);

        for (long possibleMoves = kingAttacks; possibleMoves != 0; possibleMoves &= possibleMoves - 1) {
            int targetIndex = Long.numberOfTrailingZeros(possibleMoves);
            if (!isOccupiedByColor(targetIndex, whitesTurn)) {
                boolean isCapture = isOccupiedByOpponent(targetIndex, whitesTurn);
                moves.add(createMoveInt(kingPositionIndex, targetIndex, PieceType.KING, whitesTurn, isCapture, false, false, null, isCapture ? getPieceTypeAtIndex(targetIndex) : null, isFirstKingMove, false, lastMoveDoubleStepPawnIndex));
            }
        }

        addCastlingMoves(whitesTurn, kingPositionIndex, moves);
    }

    private void addCastlingMoves(boolean whitesTurn, int kingPositionIndex, MoveList moves) {
        if (canKingCastle(whitesTurn)) {
            if (canCastleKingside(whitesTurn, kingPositionIndex)) {
                moves.add(createMoveInt(kingPositionIndex, kingPositionIndex + 2, PieceType.KING, whitesTurn, false, true, false, null, null, true, true, lastMoveDoubleStepPawnIndex));
            }
            if (canCastleQueenside(whitesTurn, kingPositionIndex)) {
                moves.add(createMoveInt(kingPositionIndex, kingPositionIndex - 2, PieceType.KING, whitesTurn, false, true, false, null, null, true, true, lastMoveDoubleStepPawnIndex));
            }
        }
    }


    private boolean canKingCastle(boolean whitesTurn) {
        // The king must not have moved and must not currently be under attack
        int kingIndex = findKingIndex(whitesTurn);
        return hasKingNotMoved(whitesTurn) && !isSquareUnderAttack(kingIndex, whitesTurn);
    }

    private boolean canCastleKingside(boolean colorWhite, int kingPositionIndex) {
        // Ensure the squares between the king and the rook are unoccupied and not under attack
        int[] kingsideSquares = {kingPositionIndex + 1, kingPositionIndex + 2};
        for (int square : kingsideSquares) {
            if (isOccupied(square) || isSquareUnderAttack(square, colorWhite)) {
                return false;
            }
        }

        int rookIndex = colorWhite ? 7 : 63;

        // Check if the rook has moved
        if (hasRookMoved(rookIndex)) {
            return false;
        }

        // Check if the rook still exists

        return isRookAtIndex(rookIndex);
    }

    private boolean canCastleQueenside(boolean colorWhite, int kingPositionIndex) {
        // Ensure the squares between the king and the rook are unoccupied and not under attack
        int[] queensideSquares = {kingPositionIndex - 1, kingPositionIndex - 2, kingPositionIndex - 3};
        for (int square : queensideSquares) {
            if (isOccupied(square) || (square != kingPositionIndex - 3 && isSquareUnderAttack(square, colorWhite))) {
                return false;
            }
        }

        int rookIndex = colorWhite ? 0 : 56;

        if (hasRookMoved(rookIndex)) {
            return false;
        }
        return isRookAtIndex(rookIndex);
    }

    private boolean isRookAtIndex(int index) {
        PieceType pieceAtPosition = getPieceTypeAtIndex(index);
        return pieceAtPosition == PieceType.ROOK;
    }

    private boolean isSquareUnderAttack(int index, boolean colorWhite) {
        long attacks = colorWhite ? blackAttackMap : whiteAttackMap;
        return (attacks & (1L << index)) != 0;
    }

    public void performMove(int move) {
        int fromIndex = MoveHelper.deriveFromIndex(move); // Extract the first 6 bits
        int toIndex = MoveHelper.deriveToIndex(move); // Extract the next 6 bits
        int pieceTypeBits = MoveHelper.derivePieceTypeBits(move); // Extract the next 3 bits
        boolean isWhite = MoveHelper.isWhitesMove(move); // Extract the color bit
        boolean isCapture = MoveHelper.isCapture(move);
        boolean isEnPassantMove = MoveHelper.isEnPassantMove(move);
        boolean isCastlingMove = MoveHelper.isCastlingMove(move);
        int promotionPieceTypeBits = MoveHelper.derivePromotionPieceTypeBits(move); // Extract the next 3 bits

        long pieceBitboard = intToPiecesBitboard(pieceTypeBits, isWhite);
        PieceType movingPiece = pieceTypeFromBits(pieceTypeBits);

        if (isCapture) {
            clearSquare(toIndex, !isWhite);
        }

        // If the move is a castling move, move both the king and the rook
        if (isCastlingMove) {
            if (isWhite) {
                whiteKingHasCastled = true;
            } else {
                blackKingHasCastled = true;
            }
            // Determine if this is kingside or queenside castling
            boolean kingside = toIndex > fromIndex;
            int rookFromIndex, rookToIndex;
            if (kingside) {
                rookFromIndex = isWhite ? 7 : 63;
                rookToIndex = rookFromIndex - 2;
            } else {
                rookFromIndex = isWhite ? 0 : 56;
                rookToIndex = rookFromIndex + 3;
            }
            long rookBitboard = intToPiecesBitboard(4, isWhite);
            rookBitboard = moveBit(rookBitboard, rookFromIndex, rookToIndex);
            setBitboardForPiece(4, isWhite, rookBitboard);

            pieceBoard[rookFromIndex] = null;
            pieceBoard[rookToIndex] = PieceType.ROOK;

            // Mark the rook as moved
            markRookAsMoved(rookFromIndex);
        }

        // Move the piece
        pieceBitboard = moveBit(pieceBitboard, fromIndex, toIndex);
        setBitboardForPiece(pieceTypeBits, isWhite, pieceBitboard);

        pieceBoard[fromIndex] = null;

        if (promotionPieceTypeBits != 0) {
            // Clear the pawn from the promotion square
            clearSquare(toIndex, isWhite);

            // Set the bitboard for the promotion piece
            long promotionPieceBitboard = intToPiecesBitboard(promotionPieceTypeBits, isWhite);
            promotionPieceBitboard |= (1L << toIndex); // Place the promotion piece on the promotion square
            setBitboardForPiece(promotionPieceTypeBits, isWhite, promotionPieceBitboard);
            pieceBoard[toIndex] = pieceTypeFromBits(promotionPieceTypeBits);
        } else {
            pieceBoard[toIndex] = movingPiece;
        }

        // Mark the king as moved if it was a king move
        if (pieceTypeBits == 6) {
            markKingAsMoved(isWhite);
        }

        //Mark rook as moved
        if (pieceTypeBits == 4) {
            markRookAsMoved(fromIndex);
        }

        if (pieceTypeBits == 1 && Math.abs(fromIndex / 8 - toIndex / 8) == 2) {
            // A pawn moved two squares forward. Only expose the en passant
            // target square if an opposing pawn could actually capture it.
            long enemyPawns = isWhite ? blackPawns : whitePawns;
            int file = toIndex % 8;
            boolean leftEnemy = file > 0 && ((enemyPawns & (1L << (toIndex - 1))) != 0);
            boolean rightEnemy = file < 7 && ((enemyPawns & (1L << (toIndex + 1))) != 0);
            lastMoveDoubleStepPawnIndex = (leftEnemy || rightEnemy) ? toIndex : 0;
        } else {
            lastMoveDoubleStepPawnIndex = 0;
        }

        // Handle en passant capture
        if (isEnPassantMove) {
            // Clear the captured pawn for en passant
            int capturedPawnIndex = isWhite ? toIndex - 8 : toIndex + 8;
            clearSquare(capturedPawnIndex, !isWhite);
        }
        updateAggregatedBitboards();
        whitesTurn = !whitesTurn;
    }

    public void clearSquare(int index, boolean isWhite) {
        long mask = ~(1L << index);
        if (isWhite) {
            if ((whitePawns & (1L << index)) != 0L) whitePawns &= mask;
            if ((whiteKnights & (1L << index)) != 0L) whiteKnights &= mask;
            if ((whiteBishops & (1L << index)) != 0L) whiteBishops &= mask;
            if ((whiteRooks & (1L << index)) != 0L) whiteRooks &= mask;
            if ((whiteQueens & (1L << index)) != 0L) whiteQueens &= mask;  // Corrected line for queen
            whiteKing &= mask; // Only clear if the king is actually on the square
        } else {
            if ((blackPawns & (1L << index)) != 0L) blackPawns &= mask;
            if ((blackKnights & (1L << index)) != 0L) blackKnights &= mask;
            if ((blackBishops & (1L << index)) != 0L) blackBishops &= mask;
            if ((blackRooks & (1L << index)) != 0L) blackRooks &= mask;
            if ((blackQueens & (1L << index)) != 0L) blackQueens &= mask;  // Corrected line for queen
            blackKing &= mask; // Only clear if the king is actually on the square
        }
        pieceBoard[index] = null;
        updateAggregatedBitboards();
    }


    public PieceType getPieceTypeAtIndex(int index) {
        return pieceBoard[index];
    }

    public Color getPieceColorAtIndex(int index) {
        long positionMask = 1L << index;

        // Check if the position is occupied by a white piece
        if ((whitePieces & positionMask) != 0) {
            return Color.WHITE;
        }
        // Check if the position is occupied by a black piece
        else if ((blackPieces & positionMask) != 0) {
            return Color.BLACK;
        }

        // No piece found at this position
        return null;
    }

    public boolean isOccupied(int index) {
        long positionMask = 1L << index;
        return (allPieces & positionMask) != 0;
    }

    public boolean isOccupiedByOpponent(int index, boolean colorWhite) {
        long positionMask = 1L << index;

        if (colorWhite) {
            // Check if the position is occupied by any of the black pieces
            return (blackPieces & positionMask) != 0;
        } else {
            // Check if the position is occupied by any of the white pieces
            return (whitePieces & positionMask) != 0;
        }
    }

    public boolean hasKingNotMoved(boolean whitesTurn) {
        return whitesTurn ? !whiteKingMoved : !blackKingMoved;
    }

    private void markRookAsMoved(int rookIndex) {
        if (rookIndex == 0) {
            whiteRookA1Moved = true;
        } else if (rookIndex == 7) {
            whiteRookH1Moved = true;
        } else if (rookIndex == 56) {
            blackRookA8Moved = true;
        } else if (rookIndex == 63) {
            blackRookH8Moved = true;
        }
    }

    public boolean hasRookMoved(int rookIndex) {
        return switch (rookIndex) {
            case 0 ->  // 'a1'
                    whiteRookA1Moved;
            case 7 ->  // 'h1'
                    whiteRookH1Moved;
            case 56 -> // 'a8'
                    blackRookA8Moved;
            case 63 -> // 'h8'
                    blackRookH8Moved;
            default -> true; // Assume the rook has moved if it's not in one of the starting positions
        };
    }

    public boolean isOccupiedByPawn(int index, boolean whiteColor) {
        // Create a bitmask for the position
        long positionMask = 1L << index;

        // Check if the position is occupied by a pawn of the given color
        if (whiteColor) {
            return (whitePawns & positionMask) != 0;
        } else { // Color.BLACK
            return (blackPawns & positionMask) != 0;
        }
    }

    public boolean isOccupiedByColor(int index, boolean colorWhite) {
        // Convert the position to a bit index
        long positionMask = 1L << index;

        // Check if the position is occupied by a piece of the given color
        if (colorWhite) {
            return (whitePieces & positionMask) != 0;
        } else { // Color.BLACK
            return (blackPieces & positionMask) != 0;
        }
    }

    public boolean isInCheck(boolean whitesTurn) {
        int kingPosition = findKingIndex(whitesTurn);
        long opponentAttacks = generateAttackBitboard(!whitesTurn);
        return (opponentAttacks & (1L << kingPosition)) != 0;
    }


    public long generatePinMask(boolean whitesTurn) {
        long kingPosition = whitesTurn ? whiteKing : blackKing;
        long slidingPieces = whitesTurn ? (blackBishops | blackRooks | blackQueens) : (whiteBishops | whiteRooks | whiteQueens);

        long pinMasks = 0L;

        while (slidingPieces != 0) {
            long slidingPiecePosition = Long.lowestOneBit(slidingPieces);
            slidingPieces ^= slidingPiecePosition; // Remove the current sliding piece

            long lineOfAttack = calculateLineOfAttack(slidingPiecePosition, kingPosition);
            long piecesInBetween = lineOfAttack & (whitesTurn ? whitePieces : blackPieces);

            if (Long.bitCount(piecesInBetween) == 1) {
                long pinnedPiece = piecesInBetween & lineOfAttack;
                long squaresBetweenPinnedAndKing = lineBetweenIndices(Long.numberOfTrailingZeros(pinnedPiece), Long.numberOfTrailingZeros(kingPosition));
                long obstructingPieces = squaresBetweenPinnedAndKing & allPieces;

                if (obstructingPieces == 0) {
                    // There are no obstructing pieces (piece is pinned)
                    pinMasks |= lineOfAttack;
                    pinMasks &= whitesTurn ? whitePieces : blackPieces;
                }
            }
        }

        return pinMasks;
    }


    private long calculateLineOfAttack(long slidingPiecePosition, long kingPosition) {
        long lineOfAttack = 0L;
        int slidingPieceIndex = Long.numberOfTrailingZeros(slidingPiecePosition);
        int kingIndex = Long.numberOfTrailingZeros(kingPosition);

        // Determine the type of the sliding piece
        if ((slidingPiecePosition & (whiteBishops | blackBishops)) != 0) {
            // Bishop's line of attack
            long occupancy = allPieces & bishopHelper.bishopMasks[slidingPieceIndex];
            long attack = bishopHelper.calculateMovesUsingBishopMagic(slidingPieceIndex, occupancy);
            lineOfAttack = attack & lineBetweenIndices(slidingPieceIndex, kingIndex);
        } else if ((slidingPiecePosition & (whiteRooks | blackRooks)) != 0) {
            // Rook's line of attack
            long occupancy = allPieces & rookHelper.rookMasks[slidingPieceIndex];
            long attack = rookHelper.calculateMovesUsingRookMagic(slidingPieceIndex, occupancy);
            lineOfAttack = attack & lineBetweenIndices(slidingPieceIndex, kingIndex);
        } else if ((slidingPiecePosition & (whiteQueens | blackQueens)) != 0) {
            // Queen's line of attack (combination of rook and bishop)
            long occupancyBishop = allPieces & bishopHelper.bishopMasks[slidingPieceIndex];
            long occupancyRook = allPieces & rookHelper.rookMasks[slidingPieceIndex];
            long attackDiagonal = bishopHelper.calculateMovesUsingBishopMagic(slidingPieceIndex, occupancyBishop);
            long attackStraight = rookHelper.calculateMovesUsingRookMagic(slidingPieceIndex, occupancyRook);
            lineOfAttack = (attackDiagonal | attackStraight) & lineBetweenIndices(slidingPieceIndex, kingIndex);
        }

        return lineOfAttack;
    }

    private int findKingIndex(boolean whitesTurn) {
        // Use bit operations to find the king's position on the board
        // Assuming there's only one king per color on the board.
        long kingBitboard = whitesTurn ? whiteKing : blackKing;
        return Long.numberOfTrailingZeros(kingBitboard);
    }

    private long knightAttackBitmask(int positionIndex) {
        return KnightHelper.knightMoveTable[positionIndex];
    }

    private long bishopAttackBitmask(int positionIndex) {
        long mask = bishopHelper.bishopMasks[positionIndex];
        long magic = bishopHelper.bishopMagics[positionIndex];

        // Calculate the index for the current occupancy
        long index = ((allPieces & mask) * magic) >>> (64 - bishopHelper.bishopBits[positionIndex]);

        // Retrieve the attacks from the precomputed table
        return bishopHelper.bishopAttacks[positionIndex][(int) index];
    }


    private long rookAttackBitmask(int positionIndex) {
        long mask = rookHelper.rookMasks[positionIndex];
        long magic = rookHelper.rookMagics[positionIndex];

        // Calculate the index for the current occupancy
        long index = ((allPieces & mask) * magic) >>> (64 - rookHelper.rookBits[positionIndex]);

        // Retrieve the attacks from the precomputed table
        return rookHelper.rookAttacks[positionIndex][(int) index];
    }


    private long queenAttackBitmask(int positionIndex) {
        // The queen's attack bitmask is a combination of the rook's and bishop's attack bitmasks
        long rookAttacks = rookAttackBitmask(positionIndex);
        long bishopAttacks = bishopAttackBitmask(positionIndex);

        // Combine both attack patterns
        return rookAttacks | bishopAttacks;
    }


    public void logBoard() {
        StringBuilder logBoard = new StringBuilder();
        logBoard.append('\n');
        for (int rank = 8; rank >= 1; rank--) {
            for (char file = 'a'; file <= 'h'; file++) {
                int index = bitIndex(file, rank);
                long positionMask = 1L << index;

                // Determine the piece at the current position
                char pieceChar = getPieceChar(positionMask);

                // Add the piece character to the log board
                logBoard.append(pieceChar).append(' ');
            }
            logBoard.append("  ").append(rank).append('\n'); // Append the rank number at the end of each line
        }
        logBoard.append("a b c d e f g h"); // Append file letters at the bottom
        log.info(logBoard.toString()); // Log the current board state
    }

    private char getPieceChar(long positionMask) {
        char pieceChar = '.';
        if ((whitePawns & positionMask) != 0) pieceChar = 'P';
        else if ((blackPawns & positionMask) != 0) pieceChar = 'p';
        else if ((whiteKnights & positionMask) != 0) pieceChar = 'N';
        else if ((blackKnights & positionMask) != 0) pieceChar = 'n';
        else if ((whiteBishops & positionMask) != 0) pieceChar = 'B';
        else if ((blackBishops & positionMask) != 0) pieceChar = 'b';
        else if ((whiteRooks & positionMask) != 0) pieceChar = 'R';
        else if ((blackRooks & positionMask) != 0) pieceChar = 'r';
        else if ((whiteQueens & positionMask) != 0) pieceChar = 'Q';
        else if ((blackQueens & positionMask) != 0) pieceChar = 'q';
        else if ((whiteKing & positionMask) != 0) pieceChar = 'K';
        else if ((blackKing & positionMask) != 0) pieceChar = 'k';
        return pieceChar;
    }

    // Helper method to move a piece on a bitboard
    private long moveBit(long pieceBitboard, int fromIndex, int toIndex) {
        // Clear the bit at the fromIndex
        pieceBitboard &= ~(1L << fromIndex);
        // Set the bit at the toIndex
        pieceBitboard |= 1L << toIndex;
        return pieceBitboard;
    }

    private boolean doesMoveWrapAround(int fromIndex, int toIndex) {
        int fromFile = fromIndex % 8;
        int toRank = toIndex / 8;
        int toFile = toIndex % 8;
        // Check if the move wraps around the board horizontally or is outside the board vertically
        return Math.abs(fromFile - toFile) > 1 || toRank < 0 || toRank > 7;
    }

    public void undoMove(int move) {
        int fromIndex = MoveHelper.deriveFromIndex(move); // Extract the first 6 bits
        int toIndex = MoveHelper.deriveToIndex(move); // Extract the next 6 bits
        int pieceTypeBits = MoveHelper.derivePieceTypeBits(move); // Extract the next 3 bits
        boolean isWhite = MoveHelper.isWhitesMove(move); // Extract the color bit
        boolean isCapture = MoveHelper.isCapture(move);
        boolean isEnPassantMove = MoveHelper.isEnPassantMove(move);
        boolean isCastlingMove = MoveHelper.isCastlingMove(move);
        int promotionPieceTypeBits = MoveHelper.derivePromotionPieceTypeBits(move); // Extract the next 3 bits
        int capturedPieceTypeBits = MoveHelper.deriveCapturedPieceTypeBits(move); // Extract the next 3 bits
        boolean isKingFirstMove = MoveHelper.isKingFirstMove(move); // Extract the king's first move bit
        boolean isRookFirstMove = MoveHelper.isRookFirstMove(move); // Extract the rook's first move bit
        int doubleStepPawnIndex = MoveHelper.deriveLastMoveDoubleStepPawnIndex(move);

        // 1. Handle Captured Piece Restoration
        undoCapture(toIndex, capturedPieceTypeBits, isCapture, isWhite, isEnPassantMove);

        // 2. Handle Pawn Promotion
        undoPromotion(promotionPieceTypeBits, fromIndex, toIndex, isWhite);

        // Moving the piece back...
        // Ensure that if the piece is a king, it's handled correctly
        undoPieceMove(pieceTypeBits, fromIndex, toIndex, isWhite);

        // If the move was a castling move, move the rook back
        undoCastling(fromIndex, toIndex, isCastlingMove, isWhite);

        // If the move was a double pawn push, remove the last move double step pawn position
        undoGameState(fromIndex, toIndex, pieceTypeBits, isKingFirstMove, isRookFirstMove, isWhite, doubleStepPawnIndex);

        // Update the aggregated bitboards and piece board
        updateAggregatedBitboards();
        initPieceBoardFromBitboards();
        whitesTurn = !whitesTurn;
    }

    private void undoGameState(int fromIndex, int toIndex, int pieceTypeBits, boolean isKingFirstMove, boolean isRookFirstMove, boolean isWhite, int doubleStepPawnIndex) {

        lastMoveDoubleStepPawnIndex = doubleStepPawnIndex;

        if (pieceTypeBits == 6 && isKingFirstMove) {
            if (isWhite) {
                whiteKingMoved = false;
                if (isRookFirstMove) {
                    if (toIndex == 6) { // 'g1'
                        whiteRookH1Moved = false;
                    } else if (toIndex == 2) { // 'c1'
                        whiteRookA1Moved = false;
                    }
                }
            } else {
                blackKingMoved = false;
                if (isRookFirstMove) {
                    if (toIndex == 62) { // 'g8'
                        blackRookH8Moved = false;
                    } else if (toIndex == 58) { // 'c8'
                        blackRookA8Moved = false;
                    }
                }
            }
        }

        if (pieceTypeBits == 4 && isRookFirstMove) {
            if (fromIndex == 0) { // 'a1'
                whiteRookA1Moved = false;
            } else if (fromIndex == 7) { // 'h1'
                whiteRookH1Moved = false;
            } else if (fromIndex == 56) { // 'a8'
                blackRookA8Moved = false;
            } else if (fromIndex == 63) { // 'h8'
                blackRookH8Moved = false;
            }
        }
    }

    private void undoCastling(int fromIndex, int toIndex, boolean isCastling, boolean isWhite) {
        if (isCastling) {
            // Determine if this is kingside or queenside castling
            boolean kingside = toIndex > fromIndex;
            int rookFromIndex, rookToIndex;
            if (isWhite) {
                whiteKingHasCastled = false;
            } else {
                blackKingHasCastled = false;
            }
            if (kingside) {
                rookToIndex = isWhite ? 7 : 63;
                rookFromIndex = rookToIndex - 2;
            } else {
                rookToIndex = isWhite ? 0 : 56;
                rookFromIndex = rookToIndex + 3;
            }
            // Move the rook back
            long rookBitboard = intToPiecesBitboard(4, isWhite);
            rookBitboard = moveBit(rookBitboard, rookFromIndex, rookToIndex);
            setBitboardForPiece(4, isWhite, rookBitboard);
        }
    }

    private void undoPieceMove(int pieceTypeBits, int fromIndex, int toIndex, boolean isWhite) {
        long pieceBitboard = intToPiecesBitboard(pieceTypeBits, isWhite);
        pieceBitboard = moveBit(pieceBitboard, toIndex, fromIndex);
        setBitboardForPiece(pieceTypeBits, isWhite, pieceBitboard);
    }

    private void undoPromotion(int promotionPieceTypeBits, int fromIndex, int toIndex, boolean isWhite) {
        if (promotionPieceTypeBits != 0) {
            long promotedPieceBitboard = intToPiecesBitboard(promotionPieceTypeBits, isWhite);
            // Remove promoted piece
            promotedPieceBitboard &= ~(1L << toIndex);
            setBitboardForPiece(promotionPieceTypeBits, isWhite, promotedPieceBitboard);

            // Re-add the pawn
            long pawnBitboard = intToPiecesBitboard(1, isWhite);
            pawnBitboard |= 1L << fromIndex;
            setBitboardForPiece(1, isWhite, pawnBitboard);
        }
    }

    private void undoCapture(int toIndex, int capturedPieceTypeBits, boolean isCapture, boolean isWhite, boolean isEnPassant) {
        if (isCapture) {
            int enPassantModifier = 0;
            if (isEnPassant) {
                // En passant capture has occurred
                enPassantModifier = isWhite ? -8 : 8;  // Modifier based on pawn color
                lastMoveDoubleStepPawnIndex = toIndex + enPassantModifier;
            }

            // Directly update the bitboard of the captured piece
            setBitboardForPiece(capturedPieceTypeBits, !isWhite, intToPiecesBitboard(capturedPieceTypeBits, !isWhite) | (1L << (toIndex + enPassantModifier)));
        }
    }


    public boolean isEndgame() {
        // Check if both queens are off the board
        if (blackQueens == 0 && whiteQueens == 0 && blackRooks == 0 && whiteRooks == 0) {
            return true;
        }

        // Count the total number of major and minor pieces on the board using bit counts
        int totalPieces = Long.bitCount(whiteKnights) + Long.bitCount(blackKnights) +
                Long.bitCount(whiteBishops) + Long.bitCount(blackBishops) +
                Long.bitCount(whiteRooks) + Long.bitCount(blackRooks) +
                Long.bitCount(whiteQueens) + Long.bitCount(blackQueens);

        // Consider it endgame if there are fewer than a certain number of pieces
        final int ENDGAME_PIECE_THRESHOLD = 6;
        return totalPieces <= ENDGAME_PIECE_THRESHOLD;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BitBoard bitBoard = (BitBoard) o;
        return whitePawns == bitBoard.whitePawns &&
                blackPawns == bitBoard.blackPawns &&
                whiteKnights == bitBoard.whiteKnights &&
                blackKnights == bitBoard.blackKnights &&
                whiteBishops == bitBoard.whiteBishops &&
                blackBishops == bitBoard.blackBishops &&
                whiteRooks == bitBoard.whiteRooks &&
                blackRooks == bitBoard.blackRooks &&
                whiteQueens == bitBoard.whiteQueens &&
                blackQueens == bitBoard.blackQueens &&
                whiteKing == bitBoard.whiteKing &&
                blackKing == bitBoard.blackKing &&
                whitePieces == bitBoard.whitePieces &&
                blackPieces == bitBoard.blackPieces &&
                allPieces == bitBoard.allPieces &&
                whiteKingMoved == bitBoard.whiteKingMoved &&
                blackKingMoved == bitBoard.blackKingMoved &&
                whiteRookA1Moved == bitBoard.whiteRookA1Moved &&
                whiteRookH1Moved == bitBoard.whiteRookH1Moved &&
                blackRookA8Moved == bitBoard.blackRookA8Moved &&
                blackRookH8Moved == bitBoard.blackRookH8Moved;
    }

    @Override
    public int hashCode() {
        return Objects.hash(whitePawns, blackPawns, whiteKnights, blackKnights, whiteBishops, blackBishops,
                whiteRooks, blackRooks, whiteQueens, blackQueens, whiteKing, blackKing,
                whitePieces, blackPieces, allPieces, whiteKingMoved, blackKingMoved,
                whiteRookA1Moved, whiteRookH1Moved, blackRookA8Moved, blackRookH8Moved,
                lastMoveDoubleStepPawnIndex);
    }

    private PieceType getPieceTypeAtSquare(int square) {

        return getPieceTypeAtIndex(square);
    }

    private Color getPieceColorAtSquare(int square) {
        return getPieceColorAtIndex(square);
    }

    private int getPieceIndex(PieceType pieceType, Color pieceColor) {
        int index = pieceType.ordinal() * 2; // There are two colors for each piece type
        if (pieceColor == Color.BLACK) {
            index++; // Add 1 for black pieces
        }
        return index;
    }

    public long getBoardStateHash() {
        long hash = 0;

        // Iterate over all squares and XOR the hash with the piece hash values
        for (int square = 0; square < 64; square++) {
            PieceType pieceType = getPieceTypeAtSquare(square);
            Color pieceColor = getPieceColorAtSquare(square);
            if (pieceType != null && pieceColor != null) {
                int pieceIndex = getPieceIndex(pieceType, pieceColor); // You need to implement this method
                hash ^= ZobristTable.getPieceSquareHash(pieceIndex, square);
            }
        }

        // Include castling rights in the hash
        if (!whiteKingMoved) {
            if (!whiteRookH1Moved) {
                hash ^= ZobristTable.getCastlingRightsHash(0); // White Kingside
            }
            if (!whiteRookA1Moved) {
                hash ^= ZobristTable.getCastlingRightsHash(1); // White Queenside
            }
        }
        if (!blackKingMoved) {
            if (!blackRookH8Moved) {
                hash ^= ZobristTable.getCastlingRightsHash(2); // Black Kingside
            }
            if (!blackRookA8Moved) {
                hash ^= ZobristTable.getCastlingRightsHash(3); // Black Queenside
            }
        }

        if (lastMoveDoubleStepPawnIndex != 0) {
            hash ^= ZobristTable.getEnPassantSquareHash(lastMoveDoubleStepPawnIndex);
        }

        // Include the player's turn in the hash
        if (whitesTurn) {
            hash ^= ZobristTable.getBlackTurnHash(); // XOR with blackTurnHash means it's white's turn
        }

        return hash;
    }

}

