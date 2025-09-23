package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.figures.PieceType;

import java.util.Objects;

/**
 * Immutable snapshot of the current evaluation inputs.  Modules receive a context instance during
 * initialization and whenever the board state changes so they can derive any expensive features
 * lazily.
 */
public record EvaluationContext(BoardView board, GameStateEnum gameState, int phase, long whiteAttackMap,
                                long blackAttackMap, boolean whiteToMove) {

    /**
         * Lightweight immutable view of the board tailored for evaluation purposes.  Only the data
         * consumed by the evaluation modules is copied so we avoid instantiating a heavy {@link
         * BitBoard} clone for every refresh.
         */
        public record BoardView(boolean whitesTurn, long whitePawns, long blackPawns, long whiteKnights, long blackKnights,
                                long whiteBishops, long blackBishops, long whiteRooks, long blackRooks, long whiteQueens,
                                long blackQueens, long whiteKing, long blackKing, long whitePieces, long blackPieces,
                                long allPieces, int lastMoveDoubleStepPawnIndex, boolean whiteKingMoved,
                                boolean blackKingMoved, boolean whiteRookA1Moved, boolean whiteRookH1Moved,
                                boolean blackRookA8Moved, boolean blackRookH8Moved, boolean whiteKingHasCastled,
                                boolean blackKingHasCastled, PieceType[] pieceTypes) {

            public BoardView(boolean whitesTurn,
                             long whitePawns,
                             long blackPawns,
                             long whiteKnights,
                             long blackKnights,
                             long whiteBishops,
                             long blackBishops,
                             long whiteRooks,
                             long blackRooks,
                             long whiteQueens,
                             long blackQueens,
                             long whiteKing,
                             long blackKing,
                             long whitePieces,
                             long blackPieces,
                             long allPieces,
                             int lastMoveDoubleStepPawnIndex,
                             boolean whiteKingMoved,
                             boolean blackKingMoved,
                             boolean whiteRookA1Moved,
                             boolean whiteRookH1Moved,
                             boolean blackRookA8Moved,
                             boolean blackRookH8Moved,
                             boolean whiteKingHasCastled,
                             boolean blackKingHasCastled,
                             PieceType[] pieceTypes) {
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
                this.pieceTypes = Objects.requireNonNull(pieceTypes, "pieceTypes");
            }

            public static BoardView from(BitBoard bitBoard) {
                PieceType[] pieces = new PieceType[64];
                for (int i = 0; i < pieces.length; i++) {
                    pieces[i] = bitBoard.getPieceTypeAtIndex(i);
                }
                return new BoardView(
                        bitBoard.isWhitesTurn(),
                        bitBoard.getWhitePawns(),
                        bitBoard.getBlackPawns(),
                        bitBoard.getWhiteKnights(),
                        bitBoard.getBlackKnights(),
                        bitBoard.getWhiteBishops(),
                        bitBoard.getBlackBishops(),
                        bitBoard.getWhiteRooks(),
                        bitBoard.getBlackRooks(),
                        bitBoard.getWhiteQueens(),
                        bitBoard.getBlackQueens(),
                        bitBoard.getWhiteKing(),
                        bitBoard.getBlackKing(),
                        bitBoard.getWhitePieces(),
                        bitBoard.getBlackPieces(),
                        bitBoard.getAllPieces(),
                        bitBoard.getLastMoveDoubleStepPawnIndex(),
                        bitBoard.isWhiteKingMoved(),
                        bitBoard.isBlackKingMoved(),
                        bitBoard.isWhiteRookA1Moved(),
                        bitBoard.isWhiteRookH1Moved(),
                        bitBoard.isBlackRookA8Moved(),
                        bitBoard.isBlackRookH8Moved(),
                        bitBoard.isWhiteKingHasCastled(),
                        bitBoard.isBlackKingHasCastled(),
                        pieces
                );
            }

            public PieceType getPieceTypeAtIndex(int index) {
                return pieceTypes[index];
            }

        }

    public EvaluationContext(BoardView board, GameStateEnum gameState, int phase,
                             long whiteAttackMap, long blackAttackMap, boolean whiteToMove) {
        this.board = Objects.requireNonNull(board, "board");
        this.gameState = gameState;
        this.phase = phase;
        this.whiteAttackMap = whiteAttackMap;
        this.blackAttackMap = blackAttackMap;
        this.whiteToMove = whiteToMove;
    }

    public static EvaluationContext from(BitBoard bitBoard, GameStateEnum gameState) {
        Objects.requireNonNull(bitBoard, "bitBoard");
        BoardView snapshot = BoardView.from(bitBoard);
        long whiteAttacks = bitBoard.getAttackBitboard(true);
        long blackAttacks = bitBoard.getAttackBitboard(false);
        return new EvaluationContext(snapshot, gameState, bitBoard.getPhase(), whiteAttacks, blackAttacks,
                bitBoard.isWhitesTurn());
    }

    public EvaluationContext copy() {
        return new EvaluationContext(board, gameState, phase, whiteAttackMap, blackAttackMap, whiteToMove);
    }
}
