package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.ImmutableBoardView;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.KingHelper;
import julius.game.chessengine.helper.KnightHelper;
import julius.game.chessengine.helper.RookHelper;
import julius.game.chessengine.tuning.TunableParameter;

import java.util.Arrays;
import java.util.Objects;

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

    private static final TunableParameter MIDGAME_KNIGHT_MOBILITY = TunableParameter.of("activity.midgameMobilityKnight", 2);
    private static final TunableParameter MIDGAME_BISHOP_MOBILITY = TunableParameter.of("activity.midgameMobilityBishop", 4);
    private static final TunableParameter MIDGAME_ROOK_MOBILITY = TunableParameter.of("activity.midgameMobilityRook", 2);
    private static final TunableParameter MIDGAME_QUEEN_MOBILITY = TunableParameter.of("activity.midgameMobilityQueen", 1);
    private static final TunableParameter MIDGAME_KING_MOBILITY = TunableParameter.of("activity.midgameMobilityKing", 0);

    private static final TunableParameter ENDGAME_KNIGHT_MOBILITY = TunableParameter.of("activity.endgameMobilityKnight", 2);
    private static final TunableParameter ENDGAME_BISHOP_MOBILITY = TunableParameter.of("activity.endgameMobilityBishop", 4);
    private static final TunableParameter ENDGAME_ROOK_MOBILITY = TunableParameter.of("activity.endgameMobilityRook", 2);
    private static final TunableParameter ENDGAME_QUEEN_MOBILITY = TunableParameter.of("activity.endgameMobilityQueen", 1);
    private static final TunableParameter ENDGAME_KING_MOBILITY = TunableParameter.of("activity.endgameMobilityKing", 2);

    private static final TunableParameter MIDGAME_KNIGHT_CENTER = TunableParameter.of("activity.midgameCenterKnight", 3);
    private static final TunableParameter MIDGAME_BISHOP_CENTER = TunableParameter.of("activity.midgameCenterBishop", 3);
    private static final TunableParameter MIDGAME_ROOK_CENTER = TunableParameter.of("activity.midgameCenterRook", 3);
    private static final TunableParameter MIDGAME_QUEEN_CENTER = TunableParameter.of("activity.midgameCenterQueen", 3);
    private static final TunableParameter MIDGAME_KING_CENTER = TunableParameter.of("activity.midgameCenterKing", 0);

    private static final TunableParameter ENDGAME_KNIGHT_CENTER = TunableParameter.of("activity.endgameCenterKnight", 3);
    private static final TunableParameter ENDGAME_BISHOP_CENTER = TunableParameter.of("activity.endgameCenterBishop", 3);
    private static final TunableParameter ENDGAME_ROOK_CENTER = TunableParameter.of("activity.endgameCenterRook", 3);
    private static final TunableParameter ENDGAME_QUEEN_CENTER = TunableParameter.of("activity.endgameCenterQueen", 3);
    private static final TunableParameter ENDGAME_KING_CENTER = TunableParameter.of("activity.endgameCenterKing", 2);

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

    private final int[] midgameMobilityWeights = new int[7];
    private final int[] endgameMobilityWeights = new int[7];
    private final int[] midgameCenterWeights = new int[7];
    private final int[] endgameCenterWeights = new int[7];

    static {
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
    private boolean initialized;

    public ActivityModule() {
        for (int i = 0; i < activities.length; i++) {
            activities[i] = new PieceActivity();
        }
        midgameMobilityWeights[KNIGHT] = MIDGAME_KNIGHT_MOBILITY.getInt();
        midgameMobilityWeights[BISHOP] = MIDGAME_BISHOP_MOBILITY.getInt();
        midgameMobilityWeights[ROOK] = MIDGAME_ROOK_MOBILITY.getInt();
        midgameMobilityWeights[QUEEN] = MIDGAME_QUEEN_MOBILITY.getInt();
        midgameMobilityWeights[KING] = MIDGAME_KING_MOBILITY.getInt();

        endgameMobilityWeights[KNIGHT] = ENDGAME_KNIGHT_MOBILITY.getInt();
        endgameMobilityWeights[BISHOP] = ENDGAME_BISHOP_MOBILITY.getInt();
        endgameMobilityWeights[ROOK] = ENDGAME_ROOK_MOBILITY.getInt();
        endgameMobilityWeights[QUEEN] = ENDGAME_QUEEN_MOBILITY.getInt();
        endgameMobilityWeights[KING] = ENDGAME_KING_MOBILITY.getInt();

        midgameCenterWeights[KNIGHT] = MIDGAME_KNIGHT_CENTER.getInt();
        midgameCenterWeights[BISHOP] = MIDGAME_BISHOP_CENTER.getInt();
        midgameCenterWeights[ROOK] = MIDGAME_ROOK_CENTER.getInt();
        midgameCenterWeights[QUEEN] = MIDGAME_QUEEN_CENTER.getInt();
        midgameCenterWeights[KING] = MIDGAME_KING_CENTER.getInt();

        endgameCenterWeights[KNIGHT] = ENDGAME_KNIGHT_CENTER.getInt();
        endgameCenterWeights[BISHOP] = ENDGAME_BISHOP_CENTER.getInt();
        endgameCenterWeights[ROOK] = ENDGAME_ROOK_CENTER.getInt();
        endgameCenterWeights[QUEEN] = ENDGAME_QUEEN_CENTER.getInt();
        endgameCenterWeights[KING] = ENDGAME_KING_CENTER.getInt();
    }

    @Override
    public void initialize(EvaluationContext context) {
        rebuildFromBoard(Objects.requireNonNull(context, "context").getBoardView());
        initialized = true;
    }

    @Override
    public void evaluate(EvaluationContext context) {
        if (!dirty) {
            return;
        }
        rebuildFromBoard(Objects.requireNonNull(context, "context").getBoardView());
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        if (!ensureInitialized(moveContext)) {
            return;
        }
        boolean forward = isForwardMove(moveContext);
        if (!updateForMove(moveContext.getMove(), forward)) {
            rebuildFromBoard(moveContext.getCurrentContext().getBoardView());
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

    private boolean ensureInitialized(MoveContext moveContext) {
        if (initialized) {
            return true;
        }
        EvaluationContext context = moveContext.getCurrentContext();
        if (context == null) {
            return false;
        }
        initialize(context);
        return true;
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

        if (!removePiece(from)) {
            return false;
        }

        if (capturedPiece != 0) {
            int captureSquare = enPassant ? enPassantCaptureSquare(to, moverColor) : to;
            affectedMask |= 1L << captureSquare;
            if (!removePiece(captureSquare)) {
                return false;
            }
        }

        int resultingPiece = promotion != 0 ? promotion : piece;
        if (!placePiece(to, moverColor, resultingPiece)) {
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

        if (!removePiece(to)) {
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
            if (!placePiece(captureSquare, opponentColor, capturedPiece)) {
                return false;
            }
            if (isSlider(capturedPiece)) {
                excludeMask |= 1L << captureSquare;
            }
            recalculatePiece(captureSquare);
        }

        if (!placePiece(from, moverColor, piece)) {
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

        if (!removePiece(rookFrom)) {
            return null;
        }
        if (!placePiece(rookTo, color, ROOK)) {
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

    private void rebuildFromBoard(ImmutableBoardView board) {
        Arrays.fill(midgameTotals, 0);
        Arrays.fill(endgameTotals, 0);
        for (PieceActivity activity : activities) {
            activity.reset();
        }

        whitePieces = 0L;
        blackPieces = 0L;
        sliderSquares = 0L;

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

    private boolean placePiece(int square, int color, int pieceType) {
        PieceActivity activity = activities[square];
        if (activity.color != -1) {
            return false;
        }
        activity.color = color;
        activity.pieceType = pieceType;
        activity.mobilityCount = 0;
        activity.centerCount = 0;
        activity.midgameScore = 0;
        activity.endgameScore = 0;
        activity.attackMask = 0L;

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
        return true;
    }

    private boolean removePiece(int square) {
        PieceActivity activity = activities[square];
        if (activity.color == -1) {
            return false;
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
        return true;
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
            activity.mobilityCount = 0;
            activity.centerCount = 0;
            activity.midgameScore = 0;
            activity.endgameScore = 0;
            activity.attackMask = 0L;
            return;
        }

        long attacks = 0L;
        if (pieceType <= 0) {
            activity.mobilityCount = 0;
            activity.centerCount = 0;
            activity.midgameScore = 0;
            activity.endgameScore = 0;
            activity.attackMask = 0L;
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
                activity.mobilityCount = 0;
                activity.centerCount = 0;
                activity.midgameScore = 0;
                activity.endgameScore = 0;
                activity.attackMask = 0L;
                return;
            }
        }

        long friendlyPieces = activity.color == WHITE ? whitePieces : blackPieces;
        long legalTargets = attacks & ~friendlyPieces;

        int mobility = Long.bitCount(legalTargets);
        int center = Long.bitCount(legalTargets & CENTRAL_SQUARES);

        int midgameContribution = mobility * midgameMobilityWeights[pieceType]
                + center * midgameCenterWeights[pieceType];
        int endgameContribution = mobility * endgameMobilityWeights[pieceType]
                + center * endgameCenterWeights[pieceType];

        midgameTotals[activity.color] += midgameContribution - activity.midgameScore;
        endgameTotals[activity.color] += endgameContribution - activity.endgameScore;

        activity.mobilityCount = mobility;
        activity.centerCount = center;
        activity.midgameScore = midgameContribution;
        activity.endgameScore = endgameContribution;
        activity.attackMask = legalTargets;
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
        private int mobilityCount;
        private int centerCount;
        private int midgameScore;
        private int endgameScore;
        private long attackMask;

        private void reset() {
            color = -1;
            pieceType = 0;
            mobilityCount = 0;
            centerCount = 0;
            midgameScore = 0;
            endgameScore = 0;
            attackMask = 0L;
        }
    }

    private record CastlingUpdate(long affectedMask, int rookDestination) {
    }
}

