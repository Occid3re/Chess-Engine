package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.KingHelper;
import julius.game.chessengine.helper.KnightHelper;
import julius.game.chessengine.helper.RookHelper;

import java.util.Arrays;

/**
 * Tracks piece activity (mobility plus multi-ring center control) with separate midgame/endgame
 * components. The module keeps per-square caches and incrementally updates only the pieces
 * affected by a move so the evaluation pipeline can reuse the tapered score.
 * <p>
 * Notes:
 * - Pawns are intentionally ignored here (PST/structure module should handle them).
 * - Kings receive activity only in endgame.
 * - Center is multi-ring: core(4), inner(16), extended(36), each with piece/phase weights.
 */
public final class ActivityModule implements EvaluationModule {

    private static final int WHITE = 0;
    private static final int BLACK = 1;

    private static final int PAWN   = MoveHelper.pieceTypeToInt(PieceType.PAWN);
    private static final int KNIGHT = MoveHelper.pieceTypeToInt(PieceType.KNIGHT);
    private static final int BISHOP = MoveHelper.pieceTypeToInt(PieceType.BISHOP);
    private static final int ROOK   = MoveHelper.pieceTypeToInt(PieceType.ROOK);
    private static final int QUEEN  = MoveHelper.pieceTypeToInt(PieceType.QUEEN);
    private static final int KING   = MoveHelper.pieceTypeToInt(PieceType.KING);

    // =======================
    // Tuned mobility weights
    // =======================
    // Indexed by piece-type int (0..6). Only entries we use are set.
    // These are conservative to prevent mobility from overpowering material/PST.
    private static final int[] MIDGAME_MOBILITY_WEIGHTS = new int[7];
    private static final int[] ENDGAME_MOBILITY_WEIGHTS = new int[7];

    // Per-piece soft caps for linear mobility to avoid overcounting on empty boards.
    private static final int[] MOBILITY_CAP = new int[7];

    // ==========================================
    // Multi-ring center weights (core/inner/ext)
    // ==========================================
    // We weight "legal targets" intersecting the rings, per piece & phase.
    private static final int[] MIDGAME_CENTER_CORE  = new int[7];
    private static final int[] MIDGAME_CENTER_INNER = new int[7];
    private static final int[] MIDGAME_CENTER_EXT   = new int[7];

    private static final int[] ENDGAME_CENTER_CORE  = new int[7];
    private static final int[] ENDGAME_CENTER_INNER = new int[7];
    private static final int[] ENDGAME_CENTER_EXT   = new int[7];

    // ======================
    // Center ring bitboards
    // ======================
    // Core = {d4,e4,d5,e5}
    private static final long CENTER_CORE;
    // Inner 16 = {c3..f6}
    private static final long CENTER_INNER_16;
    // Extended 36 ≈ files c..f & ranks 3..6 plus a ring around it (b..g & 2..7 minus corners)
    private static final long CENTER_EXT_36;

    // Directions for first-blocker scans
    private static final int NORTH = 0;
    private static final int SOUTH = 1;
    private static final int EAST = 2;
    private static final int WEST = 3;
    private static final int NORTH_EAST = 4;
    private static final int NORTH_WEST = 5;
    private static final int SOUTH_EAST = 6;
    private static final int SOUTH_WEST = 7;

    private static final int[] RANK_DELTAS = { 1, -1,  0,  0,  1,  1, -1, -1};
    private static final int[] FILE_DELTAS = { 0,  0,  1, -1,  1, -1,  1, -1};

    private static final BishopHelper BISHOP_HELPER = BishopHelper.getInstance();
    private static final RookHelper   ROOK_HELPER   = RookHelper.getInstance();

    static {
        // ===== Mobility weights (centipawns per reachable square) =====
        // Knights like mobility equally in MG/EG; bishops slightly higher; rooks/queens moderate; king only in EG.
        MIDGAME_MOBILITY_WEIGHTS[KNIGHT] = 3;
        MIDGAME_MOBILITY_WEIGHTS[BISHOP] = 4;
        MIDGAME_MOBILITY_WEIGHTS[ROOK]   = 2;
        MIDGAME_MOBILITY_WEIGHTS[QUEEN]  = 1;
        MIDGAME_MOBILITY_WEIGHTS[KING]   = 0;

        ENDGAME_MOBILITY_WEIGHTS[KNIGHT] = 3;
        ENDGAME_MOBILITY_WEIGHTS[BISHOP] = 5;
        ENDGAME_MOBILITY_WEIGHTS[ROOK]   = 3;
        ENDGAME_MOBILITY_WEIGHTS[QUEEN]  = 2;
        ENDGAME_MOBILITY_WEIGHTS[KING]   = 3;

        // ===== Mobility soft caps =====
        MOBILITY_CAP[KNIGHT] = 8;   // max knight moves on empty board
        MOBILITY_CAP[BISHOP] = 13;  // typical bishop mobility
        MOBILITY_CAP[ROOK]   = 14;  // typical rook mobility
        MOBILITY_CAP[QUEEN]  = 27;  // queen can explode; cap it
        MOBILITY_CAP[KING]   = 8;

        // ===== Center weights (core / inner16 / extended36) =====
        // MG: Knights heavily core-focused; bishops/rooks care inner+ext; queen mild; king 0.
        MIDGAME_CENTER_CORE[KNIGHT]  = 6; MIDGAME_CENTER_INNER[KNIGHT]  = 3; MIDGAME_CENTER_EXT[KNIGHT]  = 1;
        MIDGAME_CENTER_CORE[BISHOP]  = 2; MIDGAME_CENTER_INNER[BISHOP]  = 3; MIDGAME_CENTER_EXT[BISHOP]  = 2;
        MIDGAME_CENTER_CORE[ROOK]    = 1; MIDGAME_CENTER_INNER[ROOK]    = 2; MIDGAME_CENTER_EXT[ROOK]    = 2;
        MIDGAME_CENTER_CORE[QUEEN]   = 1; MIDGAME_CENTER_INNER[QUEEN]   = 2; MIDGAME_CENTER_EXT[QUEEN]   = 1;
        MIDGAME_CENTER_CORE[KING]    = 0; MIDGAME_CENTER_INNER[KING]    = 0; MIDGAME_CENTER_EXT[KING]    = 0;

        // EG: King likes center; minor pieces still like it but slightly less tapered.
        ENDGAME_CENTER_CORE[KNIGHT]  = 5; ENDGAME_CENTER_INNER[KNIGHT]  = 3; ENDGAME_CENTER_EXT[KNIGHT]  = 1;
        ENDGAME_CENTER_CORE[BISHOP]  = 2; ENDGAME_CENTER_INNER[BISHOP]  = 3; ENDGAME_CENTER_EXT[BISHOP]  = 2;
        ENDGAME_CENTER_CORE[ROOK]    = 1; ENDGAME_CENTER_INNER[ROOK]    = 2; ENDGAME_CENTER_EXT[ROOK]    = 2;
        ENDGAME_CENTER_CORE[QUEEN]   = 1; ENDGAME_CENTER_INNER[QUEEN]   = 2; ENDGAME_CENTER_EXT[QUEEN]   = 1;
        ENDGAME_CENTER_CORE[KING]    = 4; ENDGAME_CENTER_INNER[KING]    = 3; ENDGAME_CENTER_EXT[KING]    = 2;

        // ===== Center rings =====
        CENTER_CORE = bit(squareIndex('d', 4)) | bit(squareIndex('e', 4))
                | bit(squareIndex('d', 5)) | bit(squareIndex('e', 5));

        // Inner 16: files c..f, ranks 3..6
        long inner = 0L;
        for (char f = 'c'; f <= 'f'; f++) {
            for (int r = 3; r <= 6; r++) {
                inner |= bit(squareIndex(f, r));
            }
        }
        CENTER_INNER_16 = inner;

        // Extended 36: files b..g, ranks 2..7 minus the outer corner squares (b2,b7,g2,g7)
        long ext = 0L;
        for (char f = 'b'; f <= 'g'; f++) {
            for (int r = 2; r <= 7; r++) {
                int idx = squareIndex(f, r);
                if ((f == 'b' || f == 'g') && (r == 2 || r == 7)) continue; // trim corners
                ext |= bit(idx);
            }
        }
        CENTER_EXT_36 = ext;
    }

    private static long bit(int sq) { return 1L << sq; }

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
        rebuildFromBoard(context.board());
    }

    @Override
    public void evaluate(EvaluationContext context) {
        if (!dirty) return;
        rebuildFromBoard(context.board());
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        boolean forward = isForwardMove(moveContext);
        if (!updateForMove(moveContext.move(), forward)) {
            rebuildFromBoard(moveContext.currentContext().board());
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
        return forward ? applyForwardMove(move) : revertMove(move);
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

        long affectedMask = bit(from) | bit(to);
        long excludeMask = 0L;

        if (pieceNotRemoved(from)) return false;

        if (capturedPiece != 0) {
            int captureSquare = enPassant ? enPassantCaptureSquare(to, moverColor) : to;
            affectedMask |= bit(captureSquare);
            if (pieceNotRemoved(captureSquare)) return false;
        }

        int resultingPiece = (promotion != 0) ? promotion : piece;
        if (pieceNotPlaced(to, moverColor, resultingPiece)) return false;
        excludeMask |= bit(to);

        CastlingUpdate castlingUpdate = null;
        if (castling) {
            castlingUpdate = handleCastlingRook(moverColor, to, false);
            if (castlingUpdate == null) return false;
            affectedMask |= castlingUpdate.affectedMask();
            if (castlingUpdate.rookDestination() >= 0) {
                excludeMask |= bit(castlingUpdate.rookDestination());
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

        long affectedMask = bit(from) | bit(to);
        long excludeMask = 0L;

        if (pieceNotRemoved(to)) return false;

        CastlingUpdate castlingUpdate = null;
        if (castling) {
            castlingUpdate = handleCastlingRook(moverColor, to, true);
            if (castlingUpdate == null) return false;
            affectedMask |= castlingUpdate.affectedMask();
            if (castlingUpdate.rookDestination() >= 0) {
                excludeMask |= bit(castlingUpdate.rookDestination());
            }
        }

        if (capturedPiece != 0) {
            int captureSquare = enPassant ? enPassantCaptureSquare(to, moverColor) : to;
            affectedMask |= bit(captureSquare);
            if (pieceNotPlaced(captureSquare, opponentColor, capturedPiece)) return false;
            if (isSlider(capturedPiece)) {
                excludeMask |= bit(captureSquare);
            }
            recalculatePiece(captureSquare);
        }

        if (pieceNotPlaced(from, moverColor, piece)) return false;
        excludeMask |= bit(from);
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
        int rookFrom, rookTo;
        if (color == WHITE) {
            if (kingTo == 6) { // g1
                rookFrom = undo ? 5 : 7;
                rookTo   = undo ? 7 : 5;
            } else {           // c1
                rookFrom = undo ? 3 : 0;
                rookTo   = undo ? 0 : 3;
            }
        } else {
            if (kingTo == 62) { // g8
                rookFrom = undo ? 61 : 63;
                rookTo   = undo ? 63 : 61;
            } else {            // c8
                rookFrom = undo ? 59 : 56;
                rookTo   = undo ? 56 : 59;
            }
        }
        if (pieceNotRemoved(rookFrom)) return null;
        if (pieceNotPlaced(rookTo, color, ROOK)) return null;
        return new CastlingUpdate(bit(rookFrom) | bit(rookTo), rookTo);
    }

    private void recalculateAffectedSliders(long affectedMask, long excludeMask) {
        long sliders = sliderSquares;
        while (sliders != 0) {
            int square = Long.numberOfTrailingZeros(sliders);
            sliders &= sliders - 1;

            if (((excludeMask >>> square) & 1L) != 0) continue;

            PieceActivity activity = activities[square];
            if (activity.color < 0) continue;

            long relevantMask = activity.attackMask | activity.blockerMask;
            if ((relevantMask & affectedMask) != 0) {
                recalculatePiece(square);
            }
        }
    }

    private boolean isSlider(int pieceType) {
        return pieceType == BISHOP || pieceType == ROOK || pieceType == QUEEN;
    }

    private void rebuildFromBoard(EvaluationContext.BoardView board) {
        Arrays.fill(midgameTotals, 0);
        Arrays.fill(endgameTotals, 0);
        for (PieceActivity activity : activities) activity.reset();

        whitePieces = 0L;
        blackPieces = 0L;
        sliderSquares = 0L;

        if (board == null) {
            updateScoreCache();
            dirty = false;
            return;
        }

        registerPieces(board.whitePawns(),   WHITE, PAWN);
        registerPieces(board.whiteKnights(), WHITE, KNIGHT);
        registerPieces(board.whiteBishops(), WHITE, BISHOP);
        registerPieces(board.whiteRooks(),   WHITE, ROOK);
        registerPieces(board.whiteQueens(),  WHITE, QUEEN);
        registerPieces(board.whiteKing(),    WHITE, KING);

        registerPieces(board.blackPawns(),   BLACK, PAWN);
        registerPieces(board.blackKnights(), BLACK, KNIGHT);
        registerPieces(board.blackBishops(), BLACK, BISHOP);
        registerPieces(board.blackRooks(),   BLACK, ROOK);
        registerPieces(board.blackQueens(),  BLACK, QUEEN);
        registerPieces(board.blackKing(),    BLACK, KING);

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
            long mask = bit(index);
            if (color == WHITE) whitePieces |= mask;
            else                blackPieces |= mask;
            if (isSlider(pieceType)) sliderSquares |= mask;
            remaining ^= sq;
        }
    }

    private boolean pieceNotPlaced(int square, int color, int pieceType) {
        PieceActivity activity = activities[square];
        if (activity.color != -1) return true;
        activity.color = color;
        activity.pieceType = pieceType;
        activity.midgameScore = 0;
        activity.endgameScore = 0;
        activity.attackMask = 0L;
        activity.blockerMask = 0L;

        long mask = bit(square);
        if (color == WHITE) whitePieces |= mask;
        else                blackPieces |= mask;
        if (isSlider(pieceType)) sliderSquares |= mask;
        allPieces = whitePieces | blackPieces;
        return false;
    }

    private boolean pieceNotRemoved(int square) {
        PieceActivity activity = activities[square];
        if (activity.color == -1) return true;

        int color = activity.color;
        midgameTotals[color] -= activity.midgameScore;
        endgameTotals[color] -= activity.endgameScore;

        long mask = bit(square);
        if (color == WHITE) whitePieces &= ~mask;
        else                blackPieces &= ~mask;
        if (isSlider(activity.pieceType)) sliderSquares &= ~mask;

        activity.reset();
        allPieces = whitePieces | blackPieces;
        return false;
    }

    private void recalculatePiece(int square) {
        PieceActivity activity = activities[square];
        if (activity.color < 0) return;

        int pieceType = activity.pieceType;

        // Pawns are ignored in this module; zero out and return.
        if (pieceType == PAWN) {
            if (activity.midgameScore != 0 || activity.endgameScore != 0) {
                int c = activity.color;
                midgameTotals[c] -= activity.midgameScore;
                endgameTotals[c] -= activity.endgameScore;
                activity.midgameScore = 0;
                activity.endgameScore = 0;
            }
            activity.attackMask = 0L;
            activity.blockerMask = 0L;
            return;
        }

        PieceType type = MoveHelper.intToPieceType(pieceType);

        long attacks;
        switch (type) {
            case KNIGHT -> attacks = KnightHelper.knightMoveTable[square];
            case BISHOP -> attacks = BISHOP_HELPER.calculateBishopMoves(square, allPieces);
            case ROOK   -> attacks = ROOK_HELPER.calculateRookMoves(square, allPieces);
            case QUEEN  -> attacks = BISHOP_HELPER.calculateBishopMoves(square, allPieces)
                    | ROOK_HELPER.calculateRookMoves(square, allPieces);
            case KING   -> attacks = KingHelper.KING_ATTACKS[square];
            default -> {
                zeroOut(activity);
                return;
            }
        }

        activity.attackMask = attacks;
        activity.blockerMask = computeBlockerMask(square, type, allPieces);

        long friendly = (activity.color == WHITE) ? whitePieces : blackPieces;
        long legalTargets = attacks & ~friendly;

        // -------- mobility (with soft clamp) --------
        int mobilityCount = Long.bitCount(legalTargets);
        int cap = MOBILITY_CAP[pieceType];
        if (cap > 0 && mobilityCount > cap) mobilityCount = cap;

        int mgMob = mobilityCount * MIDGAME_MOBILITY_WEIGHTS[pieceType];
        int egMob = mobilityCount * ENDGAME_MOBILITY_WEIGHTS[pieceType];

        // -------- multi-ring center control --------
        int mgCenter =
                Long.bitCount(legalTargets & CENTER_CORE)  * MIDGAME_CENTER_CORE[pieceType] +
                        Long.bitCount(legalTargets & CENTER_INNER_16) * MIDGAME_CENTER_INNER[pieceType] +
                        Long.bitCount(legalTargets & CENTER_EXT_36)   * MIDGAME_CENTER_EXT[pieceType];

        int egCenter =
                Long.bitCount(legalTargets & CENTER_CORE)  * ENDGAME_CENTER_CORE[pieceType] +
                        Long.bitCount(legalTargets & CENTER_INNER_16) * ENDGAME_CENTER_INNER[pieceType] +
                        Long.bitCount(legalTargets & CENTER_EXT_36)   * ENDGAME_CENTER_EXT[pieceType];

        int newMG = mgMob + mgCenter;
        int newEG = egMob + egCenter;

        // Update color totals by diff (micro-avoid churn when unchanged)
        int color = activity.color;
        if (newMG != activity.midgameScore) {
            midgameTotals[color] += newMG - activity.midgameScore;
            activity.midgameScore = newMG;
        }
        if (newEG != activity.endgameScore) {
            endgameTotals[color] += newEG - activity.endgameScore;
            activity.endgameScore = newEG;
        }
    }

    private void zeroOut(PieceActivity a) {
        if (a.color >= 0) {
            int c = a.color;
            midgameTotals[c] -= a.midgameScore;
            endgameTotals[c] -= a.endgameScore;
        }
        a.midgameScore = 0;
        a.endgameScore = 0;
        a.attackMask = 0L;
        a.blockerMask = 0L;
    }

    private long computeBlockerMask(int square, PieceType type, long occupancy) {
        if (type != PieceType.BISHOP && type != PieceType.ROOK && type != PieceType.QUEEN) {
            return 0L;
        }
        long blockers = 0L;
        if (type == PieceType.BISHOP || type == PieceType.QUEEN) {
            blockers |= firstBlocker(square, NORTH_EAST, occupancy);
            blockers |= firstBlocker(square, NORTH_WEST, occupancy);
            blockers |= firstBlocker(square, SOUTH_EAST, occupancy);
            blockers |= firstBlocker(square, SOUTH_WEST, occupancy);
        }
        if (type == PieceType.ROOK || type == PieceType.QUEEN) {
            blockers |= firstBlocker(square, NORTH, occupancy);
            blockers |= firstBlocker(square, SOUTH, occupancy);
            blockers |= firstBlocker(square, EAST, occupancy);
            blockers |= firstBlocker(square, WEST, occupancy);
        }
        return blockers;
    }

    private long firstBlocker(int square, int dir, long occ) {
        int rd = RANK_DELTAS[dir], fd = FILE_DELTAS[dir];
        int r = square / 8 + rd, f = square % 8 + fd;
        while (r >= 0 && r < 8 && f >= 0 && f < 8) {
            int target = r * 8 + f;
            long bit = 1L << target;
            if ((occ & bit) != 0) return bit;
            r += rd; f += fd;
        }
        return 0L;
    }

    private void updateScoreCache() {
        // allPieces is already kept up to date in setters; keep this for safety when called from rebuild/apply.
        allPieces = whitePieces | blackPieces;
        midgameScoreCache = midgameTotals[WHITE] - midgameTotals[BLACK];
        endgameScoreCache = endgameTotals[WHITE] - endgameTotals[BLACK];
    }

    private boolean isForwardMove(MoveContext moveContext) {
        EvaluationContext previous = moveContext.previousContext();
        if (previous == null) return true;
        boolean moveIsWhite = MoveHelper.isWhitesMove(moveContext.move());
        return previous.whiteToMove() == moveIsWhite;
    }

    private static final class PieceActivity {
        private int color = -1;
        private int pieceType;
        private int midgameScore;
        private int endgameScore;
        private long attackMask;
        private long blockerMask;

        private void reset() {
            color = -1;
            pieceType = 0;
            midgameScore = 0;
            endgameScore = 0;
            attackMask = 0L;
            blockerMask = 0L;
        }
    }

    private record CastlingUpdate(long affectedMask, int rookDestination) { }
}
