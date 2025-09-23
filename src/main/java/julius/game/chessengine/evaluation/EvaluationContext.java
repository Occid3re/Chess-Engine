package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.figures.PieceType;
import lombok.Setter;

import java.util.Objects;

/**
 * Mutable snapshot of the current evaluation inputs.  The pipeline keeps a single
 * instance around and mutates it incrementally as moves are played so evaluation
 * modules can avoid rebuilding their internal caches from scratch.
 */
public final class EvaluationContext {

    private final BoardView board;
    private GameStateEnum gameState;
    private int phase;
    private long whiteAttackMap;
    private long blackAttackMap;
    private boolean whiteToMove;

    private EvaluationContext(BoardView board,
                              GameStateEnum gameState,
                              int phase,
                              long whiteAttackMap,
                              long blackAttackMap,
                              boolean whiteToMove) {
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
        return new EvaluationContext(
                snapshot,
                gameState,
                snapshot.computePhase(),
                bitBoard.getAttackBitboard(true),
                bitBoard.getAttackBitboard(false),
                bitBoard.isWhitesTurn()
        );
    }

    public BoardView board() {
        return board;
    }

    public GameStateEnum gameState() {
        return gameState;
    }

    public int phase() {
        return phase;
    }

    public long whiteAttackMap() {
        return whiteAttackMap;
    }

    public long blackAttackMap() {
        return blackAttackMap;
    }

    public boolean whiteToMove() {
        return whiteToMove;
    }

    public void refresh(BitBoard bitBoard, GameStateEnum state) {
        Objects.requireNonNull(bitBoard, "bitBoard");
        board.reset(bitBoard);
        synchronize(bitBoard, state);
    }

    public void applyMove(BitBoard bitBoard, int move, GameStateEnum state) {
        Objects.requireNonNull(bitBoard, "bitBoard");
        board.applyMove(move);
        synchronize(bitBoard, state);
    }

    public void undoMove(BitBoard bitBoard, int move, GameStateEnum state) {
        Objects.requireNonNull(bitBoard, "bitBoard");
        board.undoMove(move);
        synchronize(bitBoard, state);
    }

    private void synchronize(BitBoard bitBoard, GameStateEnum state) {
        this.gameState = state;
        this.phase = board.computePhase();
        this.whiteAttackMap = bitBoard.getAttackBitboard(true);
        this.blackAttackMap = bitBoard.getAttackBitboard(false);
        boolean whiteTurn = bitBoard.isWhitesTurn();
        this.whiteToMove = whiteTurn;
        board.setWhitesTurn(whiteTurn);
        board.setLastMoveDoubleStepPawnIndex(bitBoard.getLastMoveDoubleStepPawnIndex());
        board.setWhiteKingMoved(bitBoard.isWhiteKingMoved());
        board.setBlackKingMoved(bitBoard.isBlackKingMoved());
        board.setWhiteRookA1Moved(bitBoard.isWhiteRookA1Moved());
        board.setWhiteRookH1Moved(bitBoard.isWhiteRookH1Moved());
        board.setBlackRookA8Moved(bitBoard.isBlackRookA8Moved());
        board.setBlackRookH8Moved(bitBoard.isBlackRookH8Moved());
        board.setWhiteKingHasCastled(bitBoard.isWhiteKingHasCastled());
        board.setBlackKingHasCastled(bitBoard.isBlackKingHasCastled());
    }

    public EvaluationContext copy() {
        return new EvaluationContext(
                board.copy(),
                gameState,
                phase,
                whiteAttackMap,
                blackAttackMap,
                whiteToMove
        );
    }

    /**
     * Lightweight mutable view of the board tailored for evaluation purposes.
     */
    public static final class BoardView {

        @Setter
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
        @Setter
        private int lastMoveDoubleStepPawnIndex;
        @Setter
        private boolean whiteKingMoved;
        @Setter
        private boolean blackKingMoved;
        @Setter
        private boolean whiteRookA1Moved;
        @Setter
        private boolean whiteRookH1Moved;
        @Setter
        private boolean blackRookA8Moved;
        @Setter
        private boolean blackRookH8Moved;
        @Setter
        private boolean whiteKingHasCastled;
        @Setter
        private boolean blackKingHasCastled;
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

        public BoardView copy() {
            return new BoardView(
                    whitesTurn,
                    whitePawns,
                    blackPawns,
                    whiteKnights,
                    blackKnights,
                    whiteBishops,
                    blackBishops,
                    whiteRooks,
                    blackRooks,
                    whiteQueens,
                    blackQueens,
                    whiteKing,
                    blackKing,
                    whitePieces,
                    blackPieces,
                    allPieces,
                    lastMoveDoubleStepPawnIndex,
                    whiteKingMoved,
                    blackKingMoved,
                    whiteRookA1Moved,
                    whiteRookH1Moved,
                    blackRookA8Moved,
                    blackRookH8Moved,
                    whiteKingHasCastled,
                    blackKingHasCastled,
                    pieceTypes.clone()
            );
        }

        public void reset(BitBoard bitBoard) {
            whitesTurn = bitBoard.isWhitesTurn();
            whitePawns = bitBoard.getWhitePawns();
            blackPawns = bitBoard.getBlackPawns();
            whiteKnights = bitBoard.getWhiteKnights();
            blackKnights = bitBoard.getBlackKnights();
            whiteBishops = bitBoard.getWhiteBishops();
            blackBishops = bitBoard.getBlackBishops();
            whiteRooks = bitBoard.getWhiteRooks();
            blackRooks = bitBoard.getBlackRooks();
            whiteQueens = bitBoard.getWhiteQueens();
            blackQueens = bitBoard.getBlackQueens();
            whiteKing = bitBoard.getWhiteKing();
            blackKing = bitBoard.getBlackKing();
            whitePieces = bitBoard.getWhitePieces();
            blackPieces = bitBoard.getBlackPieces();
            allPieces = bitBoard.getAllPieces();
            lastMoveDoubleStepPawnIndex = bitBoard.getLastMoveDoubleStepPawnIndex();
            whiteKingMoved = bitBoard.isWhiteKingMoved();
            blackKingMoved = bitBoard.isBlackKingMoved();
            whiteRookA1Moved = bitBoard.isWhiteRookA1Moved();
            whiteRookH1Moved = bitBoard.isWhiteRookH1Moved();
            blackRookA8Moved = bitBoard.isBlackRookA8Moved();
            blackRookH8Moved = bitBoard.isBlackRookH8Moved();
            whiteKingHasCastled = bitBoard.isWhiteKingHasCastled();
            blackKingHasCastled = bitBoard.isBlackKingHasCastled();
            for (int i = 0; i < pieceTypes.length; i++) {
                pieceTypes[i] = bitBoard.getPieceTypeAtIndex(i);
            }
        }

        public PieceType getPieceTypeAtIndex(int index) {
            return pieceTypes[index];
        }

        public long whitePawns() {
            return whitePawns;
        }

        public long blackPawns() {
            return blackPawns;
        }

        public long whiteKnights() {
            return whiteKnights;
        }

        public long blackKnights() {
            return blackKnights;
        }

        public long whiteBishops() {
            return whiteBishops;
        }

        public long blackBishops() {
            return blackBishops;
        }

        public long whiteRooks() {
            return whiteRooks;
        }

        public long blackRooks() {
            return blackRooks;
        }

        public long whiteQueens() {
            return whiteQueens;
        }

        public long blackQueens() {
            return blackQueens;
        }

        public long whiteKing() {
            return whiteKing;
        }

        public long blackKing() {
            return blackKing;
        }

        public long whitePieces() {
            return whitePieces;
        }

        public long blackPieces() {
            return blackPieces;
        }

        public long allPieces() {
            return allPieces;
        }

        public boolean whiteKingMoved() {
            return whiteKingMoved;
        }

        public boolean blackKingMoved() {
            return blackKingMoved;
        }

        public boolean whiteRookA1Moved() {
            return whiteRookA1Moved;
        }

        public boolean whiteRookH1Moved() {
            return whiteRookH1Moved;
        }

        public boolean blackRookA8Moved() {
            return blackRookA8Moved;
        }

        public boolean blackRookH8Moved() {
            return blackRookH8Moved;
        }

        public boolean whiteKingHasCastled() {
            return whiteKingHasCastled;
        }

        public boolean blackKingHasCastled() {
            return blackKingHasCastled;
        }

        public void applyMove(int move) {
            updateForMove(move, true);
        }

        public void undoMove(int move) {
            updateForMove(move, false);
        }

        public int computePhase() {
            int phaseValue = 0;
            phaseValue += Long.bitCount(whiteQueens | blackQueens) * 4;
            phaseValue += Long.bitCount(whiteRooks | blackRooks) * 2;
            phaseValue += Long.bitCount(whiteBishops | blackBishops);
            phaseValue += Long.bitCount(whiteKnights | blackKnights);
            int maxPhase = 24;
            return 256 - (phaseValue * 256 / maxPhase);
        }

        private void updateForMove(int move, boolean forward) {
            if (forward) {
                applyForward(move);
            } else {
                applyBackward(move);
            }
            recomputeAggregates();
        }

        private void applyForward(int move) {
            int from = MoveHelper.deriveFromIndex(move);
            int to = MoveHelper.deriveToIndex(move);
            boolean whiteMove = MoveHelper.isWhitesMove(move);
            int promotionBits = MoveHelper.derivePromotionPieceTypeBits(move);
            boolean capture = MoveHelper.isCapture(move);
            boolean enPassant = MoveHelper.isEnPassantMove(move);
            boolean castling = MoveHelper.isCastlingMove(move);
            PieceType movingPiece = pieceTypes[from];
            if (movingPiece == null) {
                movingPiece = pieceTypeFromBits(MoveHelper.derivePieceTypeBits(move));
            }
            if (movingPiece == null) {
                return;
            }

            removePiece(whiteMove, movingPiece, from);

            if (capture) {
                int captureSquare = enPassant ? (whiteMove ? to - 8 : to + 8) : to;
                PieceType captured = pieceTypes[captureSquare];
                if (captured == null) {
                    captured = pieceTypeFromBits(MoveHelper.deriveCapturedPieceTypeBits(move));
                }
                if (captured != null) {
                    removePiece(!whiteMove, captured, captureSquare);
                }
            }

            if (castling) {
                boolean kingside = to > from;
                int rookFrom = whiteMove ? (kingside ? 7 : 0) : (kingside ? 63 : 56);
                int rookTo = kingside ? (rookFrom - 2) : (rookFrom + 3);
                removePiece(whiteMove, PieceType.ROOK, rookFrom);
                addPiece(whiteMove, PieceType.ROOK, rookTo);
                markRookMoved(rookFrom, true);
                if (whiteMove) {
                    whiteKingHasCastled = true;
                } else {
                    blackKingHasCastled = true;
                }
            }

            PieceType placedPiece = promotionBits == 0
                    ? movingPiece
                    : pieceTypeFromBits(promotionBits);
            addPiece(whiteMove, placedPiece, to);
            markPostMoveFlags(move, whiteMove, movingPiece);
            whitesTurn = !whiteMove;
        }

        private void applyBackward(int move) {
            int from = MoveHelper.deriveFromIndex(move);
            int to = MoveHelper.deriveToIndex(move);
            boolean whiteMove = MoveHelper.isWhitesMove(move);
            boolean capture = MoveHelper.isCapture(move);
            boolean enPassant = MoveHelper.isEnPassantMove(move);
            boolean castling = MoveHelper.isCastlingMove(move);
            int promotionBits = MoveHelper.derivePromotionPieceTypeBits(move);
            int capturedBits = MoveHelper.deriveCapturedPieceTypeBits(move);

            PieceType placedPiece = pieceTypes[to];
            if (placedPiece == null) {
                placedPiece = pieceTypeFromBits(MoveHelper.derivePieceTypeBits(move));
            }

            if (castling) {
                boolean kingside = to > from;
                int rookFrom = whiteMove ? (kingside ? 7 : 0) : (kingside ? 63 : 56);
                int rookTo = kingside ? (rookFrom - 2) : (rookFrom + 3);
                removePiece(whiteMove, PieceType.ROOK, rookTo);
                addPiece(whiteMove, PieceType.ROOK, rookFrom);
                markRookMoved(rookFrom, false);
                if (whiteMove) {
                    whiteKingHasCastled = false;
                } else {
                    blackKingHasCastled = false;
                }
            }

            removePiece(whiteMove, placedPiece, to);
            PieceType restored = promotionBits == 0
                    ? pieceTypeFromBits(MoveHelper.derivePieceTypeBits(move))
                    : PieceType.PAWN;
            if (restored != null) {
                addPiece(whiteMove, restored, from);
            }

            if (capture) {
                int captureSquare = enPassant ? (whiteMove ? to - 8 : to + 8) : to;
                PieceType captured = pieceTypeFromBits(capturedBits);
                if (captured != null) {
                    addPiece(!whiteMove, captured, captureSquare);
                }
            }

            restorePostMoveFlags(move, whiteMove);
            whitesTurn = whiteMove;
        }

        private void markPostMoveFlags(int move, boolean whiteMove, PieceType movingPiece) {
            if (movingPiece == PieceType.KING) {
                if (whiteMove) {
                    whiteKingMoved = true;
                } else {
                    blackKingMoved = true;
                }
            }
            if (movingPiece == PieceType.ROOK) {
                markRookMoved(MoveHelper.deriveFromIndex(move), true);
            }
            if (MoveHelper.isCastlingMove(move)) {
                // The rook flags are already updated when the rook is moved during castling.
                if (whiteMove) {
                    whiteKingMoved = true;
                } else {
                    blackKingMoved = true;
                }
            }
        }

        private void restorePostMoveFlags(int move, boolean whiteMove) {
            int pieceBits = MoveHelper.derivePieceTypeBits(move);
            boolean kingFirstMove = MoveHelper.isKingFirstMove(move);
            boolean rookFirstMove = MoveHelper.isRookFirstMove(move);
            if (pieceBits == MoveHelper.pieceTypeToInt(PieceType.KING) && kingFirstMove) {
                if (whiteMove) {
                    whiteKingMoved = false;
                } else {
                    blackKingMoved = false;
                }
                if (MoveHelper.isCastlingMove(move) && rookFirstMove) {
                    if (whiteMove) {
                        if (MoveHelper.deriveToIndex(move) == 6) {
                            whiteRookH1Moved = false;
                        } else if (MoveHelper.deriveToIndex(move) == 2) {
                            whiteRookA1Moved = false;
                        }
                    } else {
                        if (MoveHelper.deriveToIndex(move) == 62) {
                            blackRookH8Moved = false;
                        } else if (MoveHelper.deriveToIndex(move) == 58) {
                            blackRookA8Moved = false;
                        }
                    }
                }
            }
            if (pieceBits == MoveHelper.pieceTypeToInt(PieceType.ROOK) && rookFirstMove) {
                markRookMoved(MoveHelper.deriveFromIndex(move), false);
            }
        }

        private void markRookMoved(int rookIndex, boolean moved) {
            if (rookIndex == 0) {
                whiteRookA1Moved = moved;
            } else if (rookIndex == 7) {
                whiteRookH1Moved = moved;
            } else if (rookIndex == 56) {
                blackRookA8Moved = moved;
            } else if (rookIndex == 63) {
                blackRookH8Moved = moved;
            }
        }

        private void removePiece(boolean whitePiece, PieceType piece, int index) {
            if (piece == null) {
                return;
            }
            long mask = 1L << index;
            switch (piece) {
                case PAWN -> {
                    if (whitePiece) {
                        whitePawns &= ~mask;
                    } else {
                        blackPawns &= ~mask;
                    }
                }
                case KNIGHT -> {
                    if (whitePiece) {
                        whiteKnights &= ~mask;
                    } else {
                        blackKnights &= ~mask;
                    }
                }
                case BISHOP -> {
                    if (whitePiece) {
                        whiteBishops &= ~mask;
                    } else {
                        blackBishops &= ~mask;
                    }
                }
                case ROOK -> {
                    if (whitePiece) {
                        whiteRooks &= ~mask;
                    } else {
                        blackRooks &= ~mask;
                    }
                }
                case QUEEN -> {
                    if (whitePiece) {
                        whiteQueens &= ~mask;
                    } else {
                        blackQueens &= ~mask;
                    }
                }
                case KING -> {
                    if (whitePiece) {
                        whiteKing &= ~mask;
                    } else {
                        blackKing &= ~mask;
                    }
                }
            }
            pieceTypes[index] = null;
        }

        private void addPiece(boolean whitePiece, PieceType piece, int index) {
            if (piece == null) {
                return;
            }
            long mask = 1L << index;
            switch (piece) {
                case PAWN -> {
                    if (whitePiece) {
                        whitePawns |= mask;
                    } else {
                        blackPawns |= mask;
                    }
                }
                case KNIGHT -> {
                    if (whitePiece) {
                        whiteKnights |= mask;
                    } else {
                        blackKnights |= mask;
                    }
                }
                case BISHOP -> {
                    if (whitePiece) {
                        whiteBishops |= mask;
                    } else {
                        blackBishops |= mask;
                    }
                }
                case ROOK -> {
                    if (whitePiece) {
                        whiteRooks |= mask;
                    } else {
                        blackRooks |= mask;
                    }
                }
                case QUEEN -> {
                    if (whitePiece) {
                        whiteQueens |= mask;
                    } else {
                        blackQueens |= mask;
                    }
                }
                case KING -> {
                    if (whitePiece) {
                        whiteKing |= mask;
                    } else {
                        blackKing |= mask;
                    }
                }
            }
            pieceTypes[index] = piece;
        }

        private void recomputeAggregates() {
            whitePieces = whitePawns | whiteKnights | whiteBishops | whiteRooks | whiteQueens | whiteKing;
            blackPieces = blackPawns | blackKnights | blackBishops | blackRooks | blackQueens | blackKing;
            allPieces = whitePieces | blackPieces;
        }

        private static PieceType pieceTypeFromBits(int bits) {
            if (bits == 0) {
                return null;
            }
            return MoveHelper.intToPieceType(bits);
        }
    }
}
