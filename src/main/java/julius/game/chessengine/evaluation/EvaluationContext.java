package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.figures.PieceType;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable snapshot of the current evaluation inputs.  Modules receive a context instance during
 * initialization and whenever the board state changes so they can derive any expensive features
 * lazily.
 */
public final class EvaluationContext {

    /**
     * Lightweight immutable view of the board tailored for evaluation purposes.  Only the data
     * consumed by the evaluation modules is copied so we avoid instantiating a heavy {@link
     * BitBoard} clone for every refresh.
     */
    public static final class BoardView {

        private final boolean whitesTurn;
        private final long whitePawns;
        private final long blackPawns;
        private final long whiteKnights;
        private final long blackKnights;
        private final long whiteBishops;
        private final long blackBishops;
        private final long whiteRooks;
        private final long blackRooks;
        private final long whiteQueens;
        private final long blackQueens;
        private final long whiteKing;
        private final long blackKing;
        private final long whitePieces;
        private final long blackPieces;
        private final long allPieces;
        private final int lastMoveDoubleStepPawnIndex;
        private final boolean whiteKingMoved;
        private final boolean blackKingMoved;
        private final boolean whiteRookA1Moved;
        private final boolean whiteRookH1Moved;
        private final boolean blackRookA8Moved;
        private final boolean blackRookH8Moved;
        private final boolean whiteKingHasCastled;
        private final boolean blackKingHasCastled;
        private final PieceType[] pieceTypes;

        private BoardView(boolean whitesTurn,
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

        public PieceType getPieceTypeAtIndex(int index) {
            return pieceTypes[index];
        }

        public PieceType[] getPieceTypes() {
            return Arrays.copyOf(pieceTypes, pieceTypes.length);
        }
    }

    private final BoardView board;
    private final GameStateEnum gameState;
    private final int phase;
    private final long whiteAttackMap;
    private final long blackAttackMap;
    private final boolean whiteToMove;

    private EvaluationContext(BoardView board, GameStateEnum gameState, int phase,
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

    public BoardView getBoard() {
        return board;
    }

    public GameStateEnum getGameState() {
        return gameState;
    }

    public int getPhase() {
        return phase;
    }

    public long getWhiteAttackMap() {
        return whiteAttackMap;
    }

    public long getBlackAttackMap() {
        return blackAttackMap;
    }

    public boolean isWhiteToMove() {
        return whiteToMove;
    }

    public EvaluationContext copy() {
        return new EvaluationContext(board, gameState, phase, whiteAttackMap, blackAttackMap, whiteToMove);
    }
}
