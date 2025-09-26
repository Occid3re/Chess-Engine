package julius.game.chessengine.board;

import julius.game.chessengine.figures.PieceType;

import java.util.Arrays;
import java.util.Objects;

/**
 * Lightweight, read-only projection of a {@link BitBoard}'s tactical state. The
 * evaluation pipeline can reuse instances of this class to avoid repeatedly
 * cloning {@link BitBoard} objects while still exposing all data required by the
 * evaluation modules.
 */
public final class ImmutableBoardView {

    private boolean whitesTurn;
    private long whitePawns;
    private long blackPawns;
    private long whiteKnights;
    private long blackKnights;
    private long whiteBishops;
    private long blackBishops;
    private long whiteRooks;
    private long blackRooks;
    private long whiteQueens;
    private long blackQueens;
    private long whiteKing;
    private long blackKing;
    private long whitePieces;
    private long blackPieces;
    private long allPieces;
    private long whiteAttackMap;
    private long blackAttackMap;
    private int lastMoveDoubleStepPawnIndex;
    private boolean whiteKingMoved;
    private boolean blackKingMoved;
    private boolean whiteRookA1Moved;
    private boolean whiteRookH1Moved;
    private boolean blackRookA8Moved;
    private boolean blackRookH8Moved;
    private boolean whiteKingHasCastled;
    private boolean blackKingHasCastled;
    private int halfmoveClock;
    private int fullmoveNumber;
    private final PieceType[] pieceBoard = new PieceType[64];

    private ImmutableBoardView() {
    }

    private ImmutableBoardView(ImmutableBoardView other) {
        copyFrom(other);
    }

    public static ImmutableBoardView from(BitBoard source) {
        Objects.requireNonNull(source, "source");
        ImmutableBoardView view = new ImmutableBoardView();
        view.copyFrom(source);
        return view;
    }

    public ImmutableBoardView copy() {
        return new ImmutableBoardView(this);
    }

    public void copyFrom(BitBoard source) {
        Objects.requireNonNull(source, "source");
        this.whitesTurn = source.isWhitesTurn();
        this.whitePawns = source.getWhitePawns();
        this.blackPawns = source.getBlackPawns();
        this.whiteKnights = source.getWhiteKnights();
        this.blackKnights = source.getBlackKnights();
        this.whiteBishops = source.getWhiteBishops();
        this.blackBishops = source.getBlackBishops();
        this.whiteRooks = source.getWhiteRooks();
        this.blackRooks = source.getBlackRooks();
        this.whiteQueens = source.getWhiteQueens();
        this.blackQueens = source.getBlackQueens();
        this.whiteKing = source.getWhiteKing();
        this.blackKing = source.getBlackKing();
        this.whitePieces = source.getWhitePieces();
        this.blackPieces = source.getBlackPieces();
        this.allPieces = source.getAllPieces();
        this.lastMoveDoubleStepPawnIndex = source.getLastMoveDoubleStepPawnIndex();
        this.whiteKingMoved = source.isWhiteKingMoved();
        this.blackKingMoved = source.isBlackKingMoved();
        this.whiteRookA1Moved = source.isWhiteRookA1Moved();
        this.whiteRookH1Moved = source.isWhiteRookH1Moved();
        this.blackRookA8Moved = source.isBlackRookA8Moved();
        this.blackRookH8Moved = source.isBlackRookH8Moved();
        this.whiteKingHasCastled = source.isWhiteKingHasCastled();
        this.blackKingHasCastled = source.isBlackKingHasCastled();
        this.halfmoveClock = source.getHalfmoveClock();
        this.fullmoveNumber = source.getFullmoveNumber();
        PieceType[] sourcePieces = source.getPieceBoard();
        System.arraycopy(sourcePieces, 0, this.pieceBoard, 0, this.pieceBoard.length);
        this.whiteAttackMap = source.getAttackBitboard(true);
        this.blackAttackMap = source.getAttackBitboard(false);
    }

    public void copyFrom(ImmutableBoardView other) {
        Objects.requireNonNull(other, "other");
        this.whitesTurn = other.whitesTurn;
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
        this.whitePieces = other.whitePieces;
        this.blackPieces = other.blackPieces;
        this.allPieces = other.allPieces;
        this.whiteAttackMap = other.whiteAttackMap;
        this.blackAttackMap = other.blackAttackMap;
        this.lastMoveDoubleStepPawnIndex = other.lastMoveDoubleStepPawnIndex;
        this.whiteKingMoved = other.whiteKingMoved;
        this.blackKingMoved = other.blackKingMoved;
        this.whiteRookA1Moved = other.whiteRookA1Moved;
        this.whiteRookH1Moved = other.whiteRookH1Moved;
        this.blackRookA8Moved = other.blackRookA8Moved;
        this.blackRookH8Moved = other.blackRookH8Moved;
        this.whiteKingHasCastled = other.whiteKingHasCastled;
        this.blackKingHasCastled = other.blackKingHasCastled;
        this.halfmoveClock = other.halfmoveClock;
        this.fullmoveNumber = other.fullmoveNumber;
        System.arraycopy(other.pieceBoard, 0, this.pieceBoard, 0, this.pieceBoard.length);
    }

    public boolean isWhitesTurn() {
        return whitesTurn;
    }

    public long getWhitePawns() {
        return whitePawns;
    }

    public long getBlackPawns() {
        return blackPawns;
    }

    public long getWhiteKnights() {
        return whiteKnights;
    }

    public long getBlackKnights() {
        return blackKnights;
    }

    public long getWhiteBishops() {
        return whiteBishops;
    }

    public long getBlackBishops() {
        return blackBishops;
    }

    public long getWhiteRooks() {
        return whiteRooks;
    }

    public long getBlackRooks() {
        return blackRooks;
    }

    public long getWhiteQueens() {
        return whiteQueens;
    }

    public long getBlackQueens() {
        return blackQueens;
    }

    public long getWhiteKing() {
        return whiteKing;
    }

    public long getBlackKing() {
        return blackKing;
    }

    public long getWhitePieces() {
        return whitePieces;
    }

    public long getBlackPieces() {
        return blackPieces;
    }

    public long getAllPieces() {
        return allPieces;
    }

    public long getWhiteAttackMap() {
        return whiteAttackMap;
    }

    public long getBlackAttackMap() {
        return blackAttackMap;
    }

    public int getLastMoveDoubleStepPawnIndex() {
        return lastMoveDoubleStepPawnIndex;
    }

    public boolean isWhiteKingMoved() {
        return whiteKingMoved;
    }

    public boolean isBlackKingMoved() {
        return blackKingMoved;
    }

    public boolean isWhiteRookA1Moved() {
        return whiteRookA1Moved;
    }

    public boolean isWhiteRookH1Moved() {
        return whiteRookH1Moved;
    }

    public boolean isBlackRookA8Moved() {
        return blackRookA8Moved;
    }

    public boolean isBlackRookH8Moved() {
        return blackRookH8Moved;
    }

    public boolean isWhiteKingHasCastled() {
        return whiteKingHasCastled;
    }

    public boolean isBlackKingHasCastled() {
        return blackKingHasCastled;
    }

    public int getHalfmoveClock() {
        return halfmoveClock;
    }

    public int getFullmoveNumber() {
        return fullmoveNumber;
    }

    public PieceType getPieceTypeAtIndex(int index) {
        if (index < 0 || index >= pieceBoard.length) {
            throw new IndexOutOfBoundsException("Square index out of range: " + index);
        }
        return pieceBoard[index];
    }

    public PieceType[] getPieceBoard() {
        return Arrays.copyOf(pieceBoard, pieceBoard.length);
    }
}
