package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.KingHelper;
import julius.game.chessengine.helper.KnightHelper;
import julius.game.chessengine.helper.RookHelper;

import java.util.Arrays;

/**
 * Tracks piece activity (mobility plus center control) with separate midgame/endgame
 * components.  The module keeps per-piece caches and incrementally updates only the
 * pieces affected by a move so the evaluation pipeline can reuse the tapered score.
 */
public final class ActivityModule implements EvaluationModule {

    private static final int WHITE = 0;
    private static final int BLACK = 1;

    private static final int PAWN = MoveHelper.pieceTypeToInt(PieceType.PAWN);
    private static final int KNIGHT = MoveHelper.pieceTypeToInt(PieceType.KNIGHT);
    private static final int BISHOP = MoveHelper.pieceTypeToInt(PieceType.BISHOP);
    private static final int ROOK = MoveHelper.pieceTypeToInt(PieceType.ROOK);
    private static final int QUEEN = MoveHelper.pieceTypeToInt(PieceType.QUEEN);
    private static final int KING = MoveHelper.pieceTypeToInt(PieceType.KING);

    private static final int[] MIDGAME_MOBILITY_WEIGHTS = new int[7];
    private static final int[] ENDGAME_MOBILITY_WEIGHTS = new int[7];
    private static final int[] MIDGAME_CENTER_WEIGHTS = new int[7];
    private static final int[] ENDGAME_CENTER_WEIGHTS = new int[7];

    private static final long CENTRAL_SQUARES;

    private static final int NORTH = 0;
    private static final int SOUTH = 1;
    private static final int EAST = 2;
    private static final int WEST = 3;
    private static final int NORTH_EAST = 4;
    private static final int NORTH_WEST = 5;
    private static final int SOUTH_EAST = 6;
    private static final int SOUTH_WEST = 7;

    private static final long[][] DIRECTION_RAYS = new long[8][64];

    private static final BishopHelper BISHOP_HELPER = BishopHelper.getInstance();
    private static final RookHelper ROOK_HELPER = RookHelper.getInstance();

    static {
        MIDGAME_MOBILITY_WEIGHTS[KNIGHT] = 3;
        MIDGAME_MOBILITY_WEIGHTS[BISHOP] = 6;
        MIDGAME_MOBILITY_WEIGHTS[ROOK] = 3;
        MIDGAME_MOBILITY_WEIGHTS[QUEEN] = 2;
        MIDGAME_MOBILITY_WEIGHTS[KING] = 0;

        ENDGAME_MOBILITY_WEIGHTS[KNIGHT] = 3;
        ENDGAME_MOBILITY_WEIGHTS[BISHOP] = 6;
        ENDGAME_MOBILITY_WEIGHTS[ROOK] = 3;
        ENDGAME_MOBILITY_WEIGHTS[QUEEN] = 2;
        ENDGAME_MOBILITY_WEIGHTS[KING] = 3;

        MIDGAME_CENTER_WEIGHTS[KNIGHT] = 4;
        MIDGAME_CENTER_WEIGHTS[BISHOP] = 4;
        MIDGAME_CENTER_WEIGHTS[ROOK] = 4;
        MIDGAME_CENTER_WEIGHTS[QUEEN] = 4;
        MIDGAME_CENTER_WEIGHTS[KING] = 0;

        ENDGAME_CENTER_WEIGHTS[KNIGHT] = 4;
        ENDGAME_CENTER_WEIGHTS[BISHOP] = 4;
        ENDGAME_CENTER_WEIGHTS[ROOK] = 4;
        ENDGAME_CENTER_WEIGHTS[QUEEN] = 4;
        ENDGAME_CENTER_WEIGHTS[KING] = 3;

        CENTRAL_SQUARES = (1L << squareIndex('d', 4))
                | (1L << squareIndex('e', 4))
                | (1L << squareIndex('d', 5))
                | (1L << squareIndex('e', 5));

        initializeDirectionRays();
    }

    private static void initializeDirectionRays() {
        for (int square = 0; square < 64; square++) {
            DIRECTION_RAYS[NORTH][square] = buildRay(square, 1, 0);
            DIRECTION_RAYS[SOUTH][square] = buildRay(square, -1, 0);
            DIRECTION_RAYS[EAST][square] = buildRay(square, 0, 1);
            DIRECTION_RAYS[WEST][square] = buildRay(square, 0, -1);
            DIRECTION_RAYS[NORTH_EAST][square] = buildRay(square, 1, 1);
            DIRECTION_RAYS[NORTH_WEST][square] = buildRay(square, 1, -1);
            DIRECTION_RAYS[SOUTH_EAST][square] = buildRay(square, -1, 1);
            DIRECTION_RAYS[SOUTH_WEST][square] = buildRay(square, -1, -1);
        }
    }

    private static long buildRay(int square, int rankDelta, int fileDelta) {
        long mask = 0L;
        int rank = square / 8;
        int file = square % 8;
        int r = rank + rankDelta;
        int f = file + fileDelta;
        while (r >= 0 && r < 8 && f >= 0 && f < 8) {
            mask |= 1L << (r * 8 + f);
            r += rankDelta;
            f += fileDelta;
        }
        return mask;
    }

    private static int squareIndex(char file, int rank) {
        return (rank - 1) * 8 + (file - 'a');
    }

    private final PieceActivity[] activities = new PieceActivity[64];
    private final int[] midgameTotals = new int[2];
    private final int[] endgameTotals = new int[2];

    private long whitePieces;
    private long blackPieces;
    private long allPieces;
    private long sliderSquares;

    private int midgameScoreCache;
    private int endgameScoreCache;
    private boolean dirty = true;

    public ActivityModule() {
        for (int i = 0; i < activities.length; i++) {
            activities[i] = new PieceActivity();
        }
    }

    @Override
    public void initialize(EvaluationContext context) {
        rebuildFromBoard(context.getBoard());
    }

    @Override
    public void evaluate(EvaluationContext context) {
        if (!dirty) {
            return;
        }
        rebuildFromBoard(context.getBoard());
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        boolean forward = isForwardMove(moveContext);
        if (!updateForMove(moveContext.getMove(), forward)) {
            rebuildFromBoard(moveContext.getCurrentContext().getBoard());
        }
    }

    @Override
    public void undoMove(MoveContext moveContext) {
        applyMove(moveContext);
    }

    @Override
    public int getMidgameScore() {
        return midgameScoreCache;
    }

    @Override
    public int getEndgameScore() {
        return endgameScoreCache;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void markDirty() {
        dirty = true;
    }

    private boolean updateForMove(int move, boolean forward) {
        if (forward) {
            return applyForwardMove(move);
        }
        return revertMove(move);
    }

    private boolean applyForwardMove(int move) {
        int from = MoveHelper.deriveFromIndex(move);
        int to = MoveHelper.deriveToIndex(move);
        int moverColor = MoveHelper.isWhitesMove(move) ? WHITE : BLACK;
        int piece = MoveHelper.derivePieceTypeBits(move);
        int promotion = MoveHelper.derivePromotionPieceTypeBits(move);
        int capturedPiece = MoveHelper.deriveCapturedPieceTypeBits(move);
        boolean enPassant = MoveHelper.isEnPassantMove(move);
        boolean castling = MoveHelper.isCastlingMove(move);

        long affectedMask = (1L << from) | (1L << to);
        long excludeMask = 0L;

        if (pieceNotRemoved(from)) {
            return false;
        }

        if (capturedPiece != 0) {
            int captureSquare = enPassant ? enPassantCaptureSquare(to, moverColor) : to;
            affectedMask |= 1L << captureSquare;
            if (pieceNotRemoved(captureSquare)) {
                return false;
            }
        }

        int resultingPiece = promotion != 0 ? promotion : piece;
        if (pieceNotPlaced(to, moverColor, resultingPiece)) {
            return false;
        }
        excludeMask |= 1L << to;

        CastlingUpdate castlingUpdate = null;
        if (castling) {
            castlingUpdate = handleCastlingRook(moverColor, to, false);
            if (castlingUpdate == null) {
                return false;
            }
            affectedMask |= castlingUpdate.affectedMask();
            if (castlingUpdate.rookDestination() >= 0) {
                excludeMask |= 1L << castlingUpdate.rookDestination();
            }
        }

        recalculatePiece(to);
        if (castlingUpdate != null && castlingUpdate.rookDestination() >= 0) {
            recalculatePiece(castlingUpdate.rookDestination());
        }

        recalculateAffectedSliders(affectedMask, excludeMask);
        updateScoreCache();
        dirty = false;
        return true;
    }

    private boolean revertMove(int move) {
        int from = MoveHelper.deriveFromIndex(move);
        int to = MoveHelper.deriveToIndex(move);
        int moverColor = MoveHelper.isWhitesMove(move) ? WHITE : BLACK;
        int opponentColor = moverColor ^ 1;
        int piece = MoveHelper.derivePieceTypeBits(move);
        int capturedPiece = MoveHelper.deriveCapturedPieceTypeBits(move);
        boolean enPassant = MoveHelper.isEnPassantMove(move);
        boolean castling = MoveHelper.isCastlingMove(move);

        long affectedMask = (1L << from) | (1L << to);
        long excludeMask = 0L;

        if (pieceNotRemoved(to)) {
            return false;
        }

        CastlingUpdate castlingUpdate = null;
        if (castling) {
            castlingUpdate = handleCastlingRook(moverColor, to, true);
            if (castlingUpdate == null) {
                return false;
            }
            affectedMask |= castlingUpdate.affectedMask();
            if (castlingUpdate.rookDestination() >= 0) {
                excludeMask |= 1L << castlingUpdate.rookDestination();
            }
        }

        if (capturedPiece != 0) {
            int captureSquare = enPassant ? enPassantCaptureSquare(to, moverColor) : to;
            affectedMask |= 1L << captureSquare;
            if (pieceNotPlaced(captureSquare, opponentColor, capturedPiece)) {
                return false;
            }
            if (isSlider(capturedPiece)) {
                excludeMask |= 1L << captureSquare;
            }
            recalculatePiece(captureSquare);
        }

        if (pieceNotPlaced(from, moverColor, piece)) {
            return false;
        }
        excludeMask |= 1L << from;
        recalculatePiece(from);

        if (castlingUpdate != null && castlingUpdate.rookDestination() >= 0) {
            recalculatePiece(castlingUpdate.rookDestination());
        }

        recalculateAffectedSliders(affectedMask, excludeMask);
        updateScoreCache();
        dirty = false;
        return true;
    }

    private int enPassantCaptureSquare(int to, int moverColor) {
        return moverColor == WHITE ? to - 8 : to + 8;
    }

    private CastlingUpdate handleCastlingRook(int color, int kingTo, boolean undo) {
        int rookFrom;
        int rookTo;
        if (color == WHITE) {
            if (kingTo == 6) { // White short castle
                rookFrom = undo ? 5 : 7;
                rookTo = undo ? 7 : 5;
            } else { // White long castle
                rookFrom = undo ? 3 : 0;
                rookTo = undo ? 0 : 3;
            }
        } else {
            if (kingTo == 62) { // Black short castle
                rookFrom = undo ? 61 : 63;
                rookTo = undo ? 63 : 61;
            } else { // Black long castle
                rookFrom = undo ? 59 : 56;
                rookTo = undo ? 56 : 59;
            }
        }

        if (pieceNotRemoved(rookFrom)) {
            return null;
        }
        if (pieceNotPlaced(rookTo, color, ROOK)) {
            return null;
        }
        return new CastlingUpdate((1L << rookFrom) | (1L << rookTo), rookTo);
    }

    private void recalculateAffectedSliders(long affectedMask, long excludeMask) {
        long sliders = sliderSquares;
        while (sliders != 0) {
            int square = Long.numberOfTrailingZeros(sliders);
            sliders &= sliders - 1;

            if (((excludeMask >>> square) & 1L) != 0) {
                continue;
            }

            PieceActivity activity = activities[square];
            if (activity.color < 0) {
                continue;
            }

            long rayMask = sliderRayMask(activity.pieceType, square);
            if ((rayMask & affectedMask) != 0) {
                recalculatePiece(square);
            }
        }
    }

    private long sliderRayMask(int pieceType, int square) {
        if (pieceType <= 0) {
            return 0L;
        }
        PieceType type = MoveHelper.intToPieceType(pieceType);
        return switch (type) {
            case BISHOP -> DIRECTION_RAYS[NORTH_EAST][square]
                    | DIRECTION_RAYS[NORTH_WEST][square]
                    | DIRECTION_RAYS[SOUTH_EAST][square]
                    | DIRECTION_RAYS[SOUTH_WEST][square];
            case ROOK -> DIRECTION_RAYS[NORTH][square]
                    | DIRECTION_RAYS[SOUTH][square]
                    | DIRECTION_RAYS[EAST][square]
                    | DIRECTION_RAYS[WEST][square];
            case QUEEN -> DIRECTION_RAYS[NORTH][square]
                    | DIRECTION_RAYS[SOUTH][square]
                    | DIRECTION_RAYS[EAST][square]
                    | DIRECTION_RAYS[WEST][square]
                    | DIRECTION_RAYS[NORTH_EAST][square]
                    | DIRECTION_RAYS[NORTH_WEST][square]
                    | DIRECTION_RAYS[SOUTH_EAST][square]
                    | DIRECTION_RAYS[SOUTH_WEST][square];
            default -> 0L;
        };
    }

    private boolean isSlider(int pieceType) {
        return pieceType == BISHOP || pieceType == ROOK || pieceType == QUEEN;
    }

    private void rebuildFromBoard(EvaluationContext.BoardView board) {
        Arrays.fill(midgameTotals, 0);
        Arrays.fill(endgameTotals, 0);
        for (PieceActivity activity : activities) {
            activity.reset();
        }

        whitePieces = 0L;
        blackPieces = 0L;
        sliderSquares = 0L;

        if (board == null) {
            updateScoreCache();
            dirty = false;
            return;
        }

        registerPieces(board.getWhitePawns(), WHITE, PAWN);
        registerPieces(board.getWhiteKnights(), WHITE, KNIGHT);
        registerPieces(board.getWhiteBishops(), WHITE, BISHOP);
        registerPieces(board.getWhiteRooks(), WHITE, ROOK);
        registerPieces(board.getWhiteQueens(), WHITE, QUEEN);
        registerPieces(board.getWhiteKing(), WHITE, KING);

        registerPieces(board.getBlackPawns(), BLACK, PAWN);
        registerPieces(board.getBlackKnights(), BLACK, KNIGHT);
        registerPieces(board.getBlackBishops(), BLACK, BISHOP);
        registerPieces(board.getBlackRooks(), BLACK, ROOK);
        registerPieces(board.getBlackQueens(), BLACK, QUEEN);
        registerPieces(board.getBlackKing(), BLACK, KING);

        allPieces = whitePieces | blackPieces;

        for (int square = 0; square < activities.length; square++) {
            if (activities[square].color >= 0) {
                recalculatePiece(square);
            }
        }

        updateScoreCache();
        dirty = false;
    }

    private void registerPieces(long bitboard, int color, int pieceType) {
        long remaining = bitboard;
        while (remaining != 0) {
            long sq = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(sq);
            PieceActivity activity = activities[index];
            activity.color = color;
            activity.pieceType = pieceType;
            long mask = 1L << index;
            if (color == WHITE) {
                whitePieces |= mask;
            } else {
                blackPieces |= mask;
            }
            if (isSlider(pieceType)) {
                sliderSquares |= mask;
            }
            remaining ^= sq;
        }
    }

    private boolean pieceNotPlaced(int square, int color, int pieceType) {
        PieceActivity activity = activities[square];
        if (activity.color != -1) {
            return true;
        }
        activity.color = color;
        activity.pieceType = pieceType;
        activity.midgameScore = 0;
        activity.endgameScore = 0;

        long mask = 1L << square;
        if (color == WHITE) {
            whitePieces |= mask;
        } else {
            blackPieces |= mask;
        }
        if (isSlider(pieceType)) {
            sliderSquares |= mask;
        }
        allPieces = whitePieces | blackPieces;
        return false;
    }

    private boolean pieceNotRemoved(int square) {
        PieceActivity activity = activities[square];
        if (activity.color == -1) {
            return true;
        }
        int color = activity.color;
        midgameTotals[color] -= activity.midgameScore;
        endgameTotals[color] -= activity.endgameScore;

        long mask = 1L << square;
        if (color == WHITE) {
            whitePieces &= ~mask;
        } else {
            blackPieces &= ~mask;
        }
        if (isSlider(activity.pieceType)) {
            sliderSquares &= ~mask;
        }

        activity.reset();
        allPieces = whitePieces | blackPieces;
        return false;
    }

    private void recalculatePiece(int square) {
        PieceActivity activity = activities[square];
        if (activity.color < 0) {
            return;
        }

        int pieceType = activity.pieceType;
        if (pieceType == PAWN) {
            midgameTotals[activity.color] -= activity.midgameScore;
            endgameTotals[activity.color] -= activity.endgameScore;
            activity.midgameScore = 0;
            activity.endgameScore = 0;
            return;
        }

        long attacks;
        if (pieceType <= 0) {
            activity.midgameScore = 0;
            activity.endgameScore = 0;
            return;
        }

        PieceType type = MoveHelper.intToPieceType(pieceType);
        switch (type) {
            case KNIGHT -> attacks = KnightHelper.knightMoveTable[square];
            case BISHOP -> attacks = BISHOP_HELPER.calculateBishopMoves(square, allPieces);
            case ROOK -> attacks = ROOK_HELPER.calculateRookMoves(square, allPieces);
            case QUEEN -> attacks = BISHOP_HELPER.calculateBishopMoves(square, allPieces)
                    | ROOK_HELPER.calculateRookMoves(square, allPieces);
            case KING -> attacks = KingHelper.KING_ATTACKS[square];
            default -> {
                activity.midgameScore = 0;
                activity.endgameScore = 0;
                return;
            }
        }

        long friendlyPieces = activity.color == WHITE ? whitePieces : blackPieces;
        long legalTargets = attacks & ~friendlyPieces;

        int mobility = Long.bitCount(legalTargets);
        int center = Long.bitCount(legalTargets & CENTRAL_SQUARES);

        int midgameContribution = mobility * MIDGAME_MOBILITY_WEIGHTS[pieceType]
                + center * MIDGAME_CENTER_WEIGHTS[pieceType];
        int endgameContribution = mobility * ENDGAME_MOBILITY_WEIGHTS[pieceType]
                + center * ENDGAME_CENTER_WEIGHTS[pieceType];

        midgameTotals[activity.color] += midgameContribution - activity.midgameScore;
        endgameTotals[activity.color] += endgameContribution - activity.endgameScore;

        activity.midgameScore = midgameContribution;
        activity.endgameScore = endgameContribution;
    }

    private void updateScoreCache() {
        allPieces = whitePieces | blackPieces;
        midgameScoreCache = midgameTotals[WHITE] - midgameTotals[BLACK];
        endgameScoreCache = endgameTotals[WHITE] - endgameTotals[BLACK];
    }

    private boolean isForwardMove(MoveContext moveContext) {
        EvaluationContext previous = moveContext.getPreviousContext();
        if (previous == null) {
            return true;
        }
        boolean moveIsWhite = MoveHelper.isWhitesMove(moveContext.getMove());
        return previous.isWhiteToMove() == moveIsWhite;
    }

    private static final class PieceActivity {
        private int color = -1;
        private int pieceType;
        private int midgameScore;
        private int endgameScore;

        private void reset() {
            color = -1;
            pieceType = 0;
            midgameScore = 0;
            endgameScore = 0;
        }

    }

    private record CastlingUpdate(long affectedMask, int rookDestination) {
    }
}

