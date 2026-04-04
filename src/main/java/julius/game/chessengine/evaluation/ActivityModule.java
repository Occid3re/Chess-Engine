package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.ImmutableBoardView;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.KingHelper;
import julius.game.chessengine.helper.KnightHelper;
import julius.game.chessengine.helper.RookHelper;
import julius.game.chessengine.tuning.Tuning;

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
    private static final long[] BISHOP_WATCHERS = new long[64];
    private static final long[] ROOK_WATCHERS = new long[64];
    private static final long[] QUEEN_WATCHERS = new long[64];

    private static final boolean USE_WATCHER_TABLES;

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
        boolean watchersReady = initializeWatcherTables();
        boolean forceFallback = Boolean.getBoolean("chessengine.activity.linearScanFallback");
        USE_WATCHER_TABLES = watchersReady && !forceFallback;
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

    private static boolean initializeWatcherTables() {
        Arrays.fill(BISHOP_WATCHERS, 0L);
        Arrays.fill(ROOK_WATCHERS, 0L);
        Arrays.fill(QUEEN_WATCHERS, 0L);

        try {
            for (int origin = 0; origin < 64; origin++) {
                long bishopTargets = bishopLikeRays(origin);
                while (bishopTargets != 0) {
                    int target = Long.numberOfTrailingZeros(bishopTargets);
                    bishopTargets &= bishopTargets - 1;
                    BISHOP_WATCHERS[target] |= 1L << origin;
                }

                long rookTargets = rookLikeRays(origin);
                while (rookTargets != 0) {
                    int target = Long.numberOfTrailingZeros(rookTargets);
                    rookTargets &= rookTargets - 1;
                    ROOK_WATCHERS[target] |= 1L << origin;
                }
            }

            for (int square = 0; square < 64; square++) {
                QUEEN_WATCHERS[square] = BISHOP_WATCHERS[square] | ROOK_WATCHERS[square];
            }
            return true;
        } catch (RuntimeException ex) {
            Arrays.fill(BISHOP_WATCHERS, 0L);
            Arrays.fill(ROOK_WATCHERS, 0L);
            Arrays.fill(QUEEN_WATCHERS, 0L);
            return false;
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

    private static long bishopLikeRays(int square) {
        return DIRECTION_RAYS[NORTH_EAST][square]
                | DIRECTION_RAYS[NORTH_WEST][square]
                | DIRECTION_RAYS[SOUTH_EAST][square]
                | DIRECTION_RAYS[SOUTH_WEST][square];
    }

    private static long rookLikeRays(int square) {
        return DIRECTION_RAYS[NORTH][square]
                | DIRECTION_RAYS[SOUTH][square]
                | DIRECTION_RAYS[EAST][square]
                | DIRECTION_RAYS[WEST][square];
    }

    private final PieceActivity[] activities = new PieceActivity[64];
    private final int[] midgameTotals = new int[2];
    private final int[] endgameTotals = new int[2];

    private long whitePieces;
    private long blackPieces;
    private long allPieces;
    private long sliderSquares;
    private long whitePawns;
    private long blackPawns;

    // Bonus constants for positional features
    private static final int KNIGHT_OUTPOST_MIDGAME = 15;
    private static final int KNIGHT_OUTPOST_ENDGAME = 10;
    private static final int ROOK_SEVENTH_RANK_MIDGAME = 20;
    private static final int ROOK_SEVENTH_RANK_ENDGAME = 30;
    private static final long RANK_7 = 0x00FF000000000000L;
    private static final long RANK_2 = 0x000000000000FF00L;

    private int midgameScoreCache;
    private int endgameScoreCache;
    private boolean dirty = true;
    private boolean initialized;

    public ActivityModule() {
        for (int i = 0; i < activities.length; i++) {
            activities[i] = new PieceActivity();
        }
        midgameMobilityWeights[KNIGHT] = Tuning.activityMidgameKnightMobility();
        midgameMobilityWeights[BISHOP] = Tuning.activityMidgameBishopMobility();
        midgameMobilityWeights[ROOK] = Tuning.activityMidgameRookMobility();
        midgameMobilityWeights[QUEEN] = Tuning.activityMidgameQueenMobility();
        midgameMobilityWeights[KING] = Tuning.activityMidgameKingMobility();

        endgameMobilityWeights[KNIGHT] = Tuning.activityEndgameKnightMobility();
        endgameMobilityWeights[BISHOP] = Tuning.activityEndgameBishopMobility();
        endgameMobilityWeights[ROOK] = Tuning.activityEndgameRookMobility();
        endgameMobilityWeights[QUEEN] = Tuning.activityEndgameQueenMobility();
        endgameMobilityWeights[KING] = Tuning.activityEndgameKingMobility();

        midgameCenterWeights[KNIGHT] = Tuning.activityMidgameKnightCenter();
        midgameCenterWeights[BISHOP] = Tuning.activityMidgameBishopCenter();
        midgameCenterWeights[ROOK] = Tuning.activityMidgameRookCenter();
        midgameCenterWeights[QUEEN] = Tuning.activityMidgameQueenCenter();
        midgameCenterWeights[KING] = Tuning.activityMidgameKingCenter();

        endgameCenterWeights[KNIGHT] = Tuning.activityEndgameKnightCenter();
        endgameCenterWeights[BISHOP] = Tuning.activityEndgameBishopCenter();
        endgameCenterWeights[ROOK] = Tuning.activityEndgameRookCenter();
        endgameCenterWeights[QUEEN] = Tuning.activityEndgameQueenCenter();
        endgameCenterWeights[KING] = Tuning.activityEndgameKingCenter();
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
        if (!USE_WATCHER_TABLES || !initialized) {
            recalculateAffectedSlidersLinear(affectedMask, excludeMask);
            return;
        }

        long candidateSliders = 0L;
        long targets = affectedMask;
        while (targets != 0) {
            int target = Long.numberOfTrailingZeros(targets);
            targets &= targets - 1;
            candidateSliders |= QUEEN_WATCHERS[target];
        }

        candidateSliders &= sliderSquares;
        candidateSliders &= ~excludeMask;

        while (candidateSliders != 0) {
            int square = Long.numberOfTrailingZeros(candidateSliders);
            candidateSliders &= candidateSliders - 1;
            recalculatePiece(square);
        }
    }

    private void recalculateAffectedSlidersLinear(long affectedMask, long excludeMask) {
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

    private static long sliderRayMask(int pieceType, int square) {
        if (pieceType <= 0) {
            return 0L;
        }
        PieceType type = MoveHelper.intToPieceType(pieceType);
        return switch (type) {
            case BISHOP -> bishopLikeRays(square);
            case ROOK -> rookLikeRays(square);
            case QUEEN -> bishopLikeRays(square) | rookLikeRays(square);
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
        whitePawns = board.getWhitePawns();
        blackPawns = board.getBlackPawns();

        registerPieces(whitePawns, WHITE, PAWN);
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

        addPositionalBonuses(board);
        updateScoreCache();
        dirty = false;
    }

    /**
     * Adds positional bonuses that go beyond simple mobility:
     * - Knight outpost bonus (supported by own pawn, can't be attacked by enemy pawns)
     * - Rook on 7th rank bonus (strong attacking position)
     */
    private void addPositionalBonuses(ImmutableBoardView board) {
        // Knight outposts: knights on rank 4-6 supported by own pawn and not attackable by enemy pawns
        addKnightOutpostBonus(board.getWhiteKnights(), whitePawns, blackPawns, true);
        addKnightOutpostBonus(board.getBlackKnights(), blackPawns, whitePawns, false);

        // Rook on 7th rank bonus
        addRookSeventhRankBonus(board.getWhiteRooks(), RANK_7, board.getBlackKing(), true);
        addRookSeventhRankBonus(board.getBlackRooks(), RANK_2, board.getWhiteKing(), false);

        // Connected rooks bonus (two rooks defending each other)
        addConnectedRooksBonus(board.getWhiteRooks(), true);
        addConnectedRooksBonus(board.getBlackRooks(), false);
    }

    private static final int CONNECTED_ROOKS_MIDGAME = 10;
    private static final int CONNECTED_ROOKS_ENDGAME = 15;

    private void addConnectedRooksBonus(long rooks, boolean isWhite) {
        if (Long.bitCount(rooks) < 2) return;
        int colorIdx = isWhite ? WHITE : BLACK;
        // Check if any rook attacks another rook (they see each other on rank or file)
        long remaining = rooks;
        while (remaining != 0) {
            int sq = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            long rookAttacks = ROOK_HELPER.calculateRookMoves(sq, allPieces);
            if ((rookAttacks & rooks) != 0) {
                // At least one pair of connected rooks
                midgameTotals[colorIdx] += CONNECTED_ROOKS_MIDGAME;
                endgameTotals[colorIdx] += CONNECTED_ROOKS_ENDGAME;
                return; // count once
            }
        }
    }

    private void addKnightOutpostBonus(long knights, long friendlyPawns, long enemyPawns, boolean isWhite) {
        int friendlyPawnColor = isWhite ? WHITE : BLACK;
        int enemyPawnColor = isWhite ? BLACK : WHITE;
        int colorIdx = isWhite ? WHITE : BLACK;
        long remaining = knights;
        while (remaining != 0) {
            int sq = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            int rank = sq >> 3;
            // Only consider outpost squares on ranks 4-6 for white, 3-5 for black
            boolean onOutpostRank = isWhite ? (rank >= 3 && rank <= 5) : (rank >= 2 && rank <= 4);
            if (!onOutpostRank) continue;

            // Check if supported by own pawn
            long knightMask = 1L << sq;
            long friendlyPawnAttacks = computePawnAttackMaskForSquares(friendlyPawns, friendlyPawnColor);
            if ((friendlyPawnAttacks & knightMask) == 0) continue;

            // Check if enemy pawns can attack this square (no enemy pawns on adjacent files ahead)
            int file = sq & 7;
            long adjacentFiles = 0L;
            if (file > 0) adjacentFiles |= julius.game.chessengine.helper.BitHelper.FileMasks[file - 1];
            if (file < 7) adjacentFiles |= julius.game.chessengine.helper.BitHelper.FileMasks[file + 1];
            long forwardRanks = isWhite
                    ? julius.game.chessengine.helper.PawnMoveTables.FORWARD_RANK_MASKS_WHITE[sq]
                    : julius.game.chessengine.helper.PawnMoveTables.FORWARD_RANK_MASKS_BLACK[sq];
            boolean canBeAttackedByPawn = (enemyPawns & adjacentFiles & forwardRanks) != 0;
            if (canBeAttackedByPawn) continue;

            midgameTotals[colorIdx] += KNIGHT_OUTPOST_MIDGAME;
            endgameTotals[colorIdx] += KNIGHT_OUTPOST_ENDGAME;
        }
    }

    private void addRookSeventhRankBonus(long rooks, long seventhRank, long enemyKing, boolean isWhite) {
        int colorIdx = isWhite ? WHITE : BLACK;
        long rooksOnSeventh = rooks & seventhRank;
        if (rooksOnSeventh == 0) return;
        // Only give bonus if enemy king is on 8th (or 1st for black) rank
        int enemyKingRank = enemyKing != 0 ? (Long.numberOfTrailingZeros(enemyKing) >> 3) : -1;
        boolean enemyKingOnBackRank = isWhite ? (enemyKingRank == 7) : (enemyKingRank == 0);
        if (!enemyKingOnBackRank) return;
        int rookCount = Long.bitCount(rooksOnSeventh);
        midgameTotals[colorIdx] += rookCount * ROOK_SEVENTH_RANK_MIDGAME;
        endgameTotals[colorIdx] += rookCount * ROOK_SEVENTH_RANK_ENDGAME;
    }

    private static long computePawnAttackMaskForSquares(long pawns, int pawnColor) {
        long mask = 0L;
        long remaining = pawns;
        while (remaining != 0) {
            int index = Long.numberOfTrailingZeros(remaining);
            mask |= julius.game.chessengine.helper.PawnMoveTables.PAWN_ATTACKS[pawnColor][index];
            remaining &= remaining - 1;
        }
        return mask;
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

