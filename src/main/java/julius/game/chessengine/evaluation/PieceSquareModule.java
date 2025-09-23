package julius.game.chessengine.evaluation;

import java.util.Arrays;

import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.figures.PieceType;
import lombok.Getter;

import static julius.game.chessengine.evaluation.MaterialModule.BISHOP_VALUE;
import static julius.game.chessengine.evaluation.MaterialModule.KNIGHT_VALUE;
import static julius.game.chessengine.evaluation.MaterialModule.PAWN_VALUE;
import static julius.game.chessengine.evaluation.MaterialModule.QUEEN_VALUE;
import static julius.game.chessengine.evaluation.MaterialModule.ROOK_VALUE;
import static julius.game.chessengine.helper.BishopHelper.BISHOP_ENDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.BishopHelper.BISHOP_MIDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.BitHelper.FileMasks;
import static julius.game.chessengine.helper.KingHelper.BLACK_KING_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.KingHelper.KING_ENDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.KingHelper.WHITE_KING_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.KnightHelper.KNIGHT_ENDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.KnightHelper.KNIGHT_MIDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.PawnHelper.BLACK_PAWN_ENDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.PawnHelper.BLACK_PAWN_MIDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.PawnHelper.WHITE_PAWN_ENDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.PawnHelper.WHITE_PAWN_MIDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.QueenHelper.QUEEN_ENDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.QueenHelper.QUEEN_MIDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.RookHelper.BLACK_ROOK_ENDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.RookHelper.BLACK_ROOK_MIDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.RookHelper.WHITE_ROOK_ENDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.RookHelper.WHITE_ROOK_MIDGAME_POSITIONAL_VALUES;

/**
 * Tracks tapered piece-square contributions and development penalties incrementally so the
 * evaluation pipeline can update the score of a move by touching only the affected squares.
 */
public final class PieceSquareModule implements EvaluationModule {

    private static final int WHITE = 0;
    private static final int BLACK = 1;

    private static final int PAWN = MoveHelper.pieceTypeToInt(PieceType.PAWN);
    private static final int KNIGHT = MoveHelper.pieceTypeToInt(PieceType.KNIGHT);
    private static final int BISHOP = MoveHelper.pieceTypeToInt(PieceType.BISHOP);
    private static final int ROOK = MoveHelper.pieceTypeToInt(PieceType.ROOK);
    private static final int QUEEN = MoveHelper.pieceTypeToInt(PieceType.QUEEN);
    private static final int KING = MoveHelper.pieceTypeToInt(PieceType.KING);

    private static final int DEVELOPMENT_PHASE_THRESHOLD = 64;
    private static final int QUEEN_DEVELOPMENT_PHASE_THRESHOLD = 80;
    private static final int UNDEVELOPED_MINOR_PENALTY = -30;
    private static final int EARLY_QUEEN_DEVELOPMENT_PENALTY_PER_MINOR = -20;
    private static final int MIN_UNDEVELOPED_MINORS_FOR_QUEEN_PENALTY = 2;
    private static final int START_POSITION_PENALTY = -60;
    private static final int BLEND_SCALE = 256;
    private static final int CASTLING_BONUS = 35;
    private static final int NOT_CASTLED_AND_ROOK_MOVE_PENALTY = -20;

    private static final int PIECE_SQUARE_WEIGHT_NUMERATOR = 3;
    private static final int PIECE_SQUARE_WEIGHT_DENOMINATOR = 4;

    private static final long NOT_A_FILE = ~FileMasks[0];
    private static final long NOT_H_FILE = ~FileMasks[7];

    private static final int[][][] MIDGAME_TABLES = new int[2][7][];
    private static final int[][][] ENDGAME_TABLES = new int[2][7][];

    private static final boolean[][] KNIGHT_HOME_SQUARE = new boolean[2][64];
    private static final boolean[][] BISHOP_HOME_SQUARE = new boolean[2][64];
    private static final boolean[][] ROOK_HOME_SQUARE = new boolean[2][64];

    private static final int[] QUEEN_START_SQUARE = new int[2];

    static {
        MIDGAME_TABLES[WHITE][PAWN] = WHITE_PAWN_MIDGAME_POSITIONAL_VALUES;
        ENDGAME_TABLES[WHITE][PAWN] = WHITE_PAWN_ENDGAME_POSITIONAL_VALUES;
        MIDGAME_TABLES[BLACK][PAWN] = BLACK_PAWN_MIDGAME_POSITIONAL_VALUES;
        ENDGAME_TABLES[BLACK][PAWN] = BLACK_PAWN_ENDGAME_POSITIONAL_VALUES;

        MIDGAME_TABLES[WHITE][KNIGHT] = KNIGHT_MIDGAME_POSITIONAL_VALUES;
        MIDGAME_TABLES[BLACK][KNIGHT] = KNIGHT_MIDGAME_POSITIONAL_VALUES;
        ENDGAME_TABLES[WHITE][KNIGHT] = KNIGHT_ENDGAME_POSITIONAL_VALUES;
        ENDGAME_TABLES[BLACK][KNIGHT] = KNIGHT_ENDGAME_POSITIONAL_VALUES;

        MIDGAME_TABLES[WHITE][BISHOP] = BISHOP_MIDGAME_POSITIONAL_VALUES;
        MIDGAME_TABLES[BLACK][BISHOP] = BISHOP_MIDGAME_POSITIONAL_VALUES;
        ENDGAME_TABLES[WHITE][BISHOP] = BISHOP_ENDGAME_POSITIONAL_VALUES;
        ENDGAME_TABLES[BLACK][BISHOP] = BISHOP_ENDGAME_POSITIONAL_VALUES;

        MIDGAME_TABLES[WHITE][ROOK] = WHITE_ROOK_MIDGAME_POSITIONAL_VALUES;
        MIDGAME_TABLES[BLACK][ROOK] = BLACK_ROOK_MIDGAME_POSITIONAL_VALUES;
        ENDGAME_TABLES[WHITE][ROOK] = WHITE_ROOK_ENDGAME_POSITIONAL_VALUES;
        ENDGAME_TABLES[BLACK][ROOK] = BLACK_ROOK_ENDGAME_POSITIONAL_VALUES;

        MIDGAME_TABLES[WHITE][QUEEN] = QUEEN_MIDGAME_POSITIONAL_VALUES;
        MIDGAME_TABLES[BLACK][QUEEN] = QUEEN_MIDGAME_POSITIONAL_VALUES;
        ENDGAME_TABLES[WHITE][QUEEN] = QUEEN_ENDGAME_POSITIONAL_VALUES;
        ENDGAME_TABLES[BLACK][QUEEN] = QUEEN_ENDGAME_POSITIONAL_VALUES;

        MIDGAME_TABLES[WHITE][KING] = WHITE_KING_POSITIONAL_VALUES;
        MIDGAME_TABLES[BLACK][KING] = BLACK_KING_POSITIONAL_VALUES;
        ENDGAME_TABLES[WHITE][KING] = KING_ENDGAME_POSITIONAL_VALUES;
        ENDGAME_TABLES[BLACK][KING] = KING_ENDGAME_POSITIONAL_VALUES;

        markSquares(KNIGHT_HOME_SQUARE[WHITE], 0x0000000000000042L);
        markSquares(KNIGHT_HOME_SQUARE[BLACK], 0x4200000000000000L);
        markSquares(BISHOP_HOME_SQUARE[WHITE], 0x0000000000000024L);
        markSquares(BISHOP_HOME_SQUARE[BLACK], 0x2400000000000000L);
        markSquares(ROOK_HOME_SQUARE[WHITE], 0x0000000000000081L);
        markSquares(ROOK_HOME_SQUARE[BLACK], 0x8100000000000000L);

        QUEEN_START_SQUARE[WHITE] = Long.numberOfTrailingZeros(1L << 3);
        QUEEN_START_SQUARE[BLACK] = Long.numberOfTrailingZeros(1L << 59);
    }

    private static void markSquares(boolean[] table, long bitboard) {
        long remaining = bitboard;
        while (remaining != 0) {
            long sq = remaining & -remaining;
            table[Long.numberOfTrailingZeros(sq)] = true;
            remaining ^= sq;
        }
    }

    private final int[] occupantColor = new int[64];
    private final int[] occupantPiece = new int[64];
    private final int[] midgameContributionBySquare = new int[64];
    private final int[] endgameContributionBySquare = new int[64];

    private final int[] minorPiecesAtHome = new int[2];
    private final int[] rooksAtHome = new int[2];
    private final int[] queenCount = new int[2];
    private final boolean[] queenOnStart = new boolean[2];

    private int midgameTotal;
    private int endgameTotal;
    @Getter
    private int developmentContribution;
    private int castlingContribution;
    private int currentPhase;
    private boolean initialized;
    private boolean dirty = true;
    private boolean developmentDirty;
    private boolean castlingDirty = true;

    public PieceSquareModule() {
        Arrays.fill(occupantColor, -1);
    }

    @Override
    public void initialize(EvaluationContext context) {
        rebuildFromBoard(context.board());
        currentPhase = clampPhase(context.phase());
        recalculateDevelopmentContribution();
        recalculateCastlingContribution(context.board(), currentPhase);
        dirty = false;
        initialized = true;
    }

    @Override
    public void evaluate(EvaluationContext context) {
        if (!dirty) {
            return;
        }
        rebuildFromBoard(context.board());
        currentPhase = clampPhase(context.phase());
        recalculateDevelopmentContribution();
        recalculateCastlingContribution(context.board(), currentPhase);
        dirty = false;
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        if (!ensureInitialized(moveContext)) {
            return;
        }
        boolean forward = isForwardMove(moveContext);
        castlingDirty = true;
        if (!updateForMove(moveContext.move(), forward)) {
            rebuildFromBoard(moveContext.currentContext().board());
        }
        int phase = clampPhase(moveContext.currentContext().phase());
        if (phase != currentPhase) {
            currentPhase = phase;
            developmentDirty = true;
        }
        if (developmentDirty) {
            recalculateDevelopmentContribution();
        }
        if (castlingDirty) {
            recalculateCastlingContribution(moveContext.currentContext().board(), currentPhase);
        }
        dirty = false;
    }

    @Override
    public void undoMove(MoveContext moveContext) {
        applyMove(moveContext);
    }

    @Override
    public int getMidgameScore() {
        return midgameTotal;
    }

    @Override
    public int getEndgameScore() {
        return endgameTotal;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void markDirty() {
        dirty = true;
        castlingDirty = true;
    }

    private boolean ensureInitialized(MoveContext moveContext) {
        if (initialized) {
            return true;
        }
        EvaluationContext context = moveContext.currentContext();
        if (context == null) {
            return false;
        }
        initialize(context);
        return true;
    }

    private boolean updateForMove(int move, boolean forward) {
        if (!forward) {
            return revertMove(move);
        }
        return applyForwardMove(move);
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

        if (notRemovePiece(from)) {
            return false;
        }

        if (capturedPiece != 0) {
            int captureSquare = enPassant ? enPassantCaptureSquare(to, moverColor) : to;
            if (notRemovePiece(captureSquare)) {
                return false;
            }
        }

        int resultingPiece = promotion != 0 ? promotion : piece;
        if (!placePiece(to, moverColor, resultingPiece)) {
            return false;
        }

        if (castling) {
            if (doNotHandleCastlingRook(moverColor, to, false)) {
                return false;
            }
        }

        developmentDirty = true;
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

        if (notRemovePiece(to)) {
            return false;
        }

        if (castling) {
            if (doNotHandleCastlingRook(moverColor, to, true)) {
                return false;
            }
        }

        if (!placePiece(from, moverColor, piece)) {
            return false;
        }

        if (capturedPiece != 0) {
            int captureSquare = enPassant ? enPassantCaptureSquare(to, moverColor) : to;
            if (!placePiece(captureSquare, opponentColor, capturedPiece)) {
                return false;
            }
        }

        // We placed a pawn back on the from-square already; nothing extra required.

        developmentDirty = true;
        return true;
    }

    private int enPassantCaptureSquare(int to, int moverColor) {
        return moverColor == WHITE ? to - 8 : to + 8;
    }

    private boolean doNotHandleCastlingRook(int color, int kingTo, boolean undo) {
        int rookFrom;
        int rookTo;
        if (color == WHITE) {
            if (kingTo == 6) {
                rookFrom = undo ? 5 : 7;
                rookTo = undo ? 7 : 5;
            } else {
                rookFrom = undo ? 3 : 0;
                rookTo = undo ? 0 : 3;
            }
        } else {
            if (kingTo == 62) {
                rookFrom = undo ? 61 : 63;
                rookTo = undo ? 63 : 61;
            } else {
                rookFrom = undo ? 59 : 56;
                rookTo = undo ? 56 : 59;
            }
        }

        return notRemovePiece(rookFrom) || !placePiece(rookTo, color, ROOK);
    }

    private boolean notRemovePiece(int square) {
        int color = occupantColor[square];
        if (color == -1) {
            return true;
        }
        int pieceType = occupantPiece[square];
        midgameTotal -= midgameContributionBySquare[square];
        endgameTotal -= endgameContributionBySquare[square];
        midgameContributionBySquare[square] = 0;
        endgameContributionBySquare[square] = 0;
        occupantColor[square] = -1;
        occupantPiece[square] = 0;

        adjustDevelopmentOnLeave(square, color, pieceType);
        return false;
    }

    private boolean placePiece(int square, int color, int pieceType) {
        if (occupantColor[square] != -1) {
            return false;
        }
        int[] mgTable = MIDGAME_TABLES[color][pieceType];
        int[] egTable = ENDGAME_TABLES[color][pieceType];
        int mg = mgTable != null ? mgTable[square] : 0;
        int eg = egTable != null ? egTable[square] : 0;
        if (color == BLACK) {
            mg = -mg;
            eg = -eg;
        }
        mg = scalePieceSquareContribution(mg);
        eg = scalePieceSquareContribution(eg);
        midgameContributionBySquare[square] = mg;
        endgameContributionBySquare[square] = eg;
        midgameTotal += mg;
        endgameTotal += eg;
        occupantColor[square] = color;
        occupantPiece[square] = pieceType;

        adjustDevelopmentOnEnter(square, color, pieceType);
        return true;
    }

    private void adjustDevelopmentOnEnter(int square, int color, int pieceType) {
        if (pieceType <= 0) {
            return;
        }
        PieceType type = MoveHelper.intToPieceType(pieceType);
        switch (type) {
            case KNIGHT -> {
                if (KNIGHT_HOME_SQUARE[color][square]) {
                    minorPiecesAtHome[color]++;
                    developmentDirty = true;
                }
            }
            case BISHOP -> {
                if (BISHOP_HOME_SQUARE[color][square]) {
                    minorPiecesAtHome[color]++;
                    developmentDirty = true;
                }
            }
            case ROOK -> {
                if (ROOK_HOME_SQUARE[color][square]) {
                    rooksAtHome[color]++;
                    developmentDirty = true;
                }
            }
            case QUEEN -> {
                queenCount[color]++;
                if (isQueenStartSquare(color, square)) {
                    queenOnStart[color] = true;
                }
                developmentDirty = true;
            }
            default -> {
            }
        }
    }

    private void adjustDevelopmentOnLeave(int square, int color, int pieceType) {
        if (pieceType <= 0) {
            return;
        }
        PieceType type = MoveHelper.intToPieceType(pieceType);
        switch (type) {
            case KNIGHT -> {
                if (KNIGHT_HOME_SQUARE[color][square]) {
                    minorPiecesAtHome[color]--;
                    developmentDirty = true;
                }
            }
            case BISHOP -> {
                if (BISHOP_HOME_SQUARE[color][square]) {
                    minorPiecesAtHome[color]--;
                    developmentDirty = true;
                }
            }
            case ROOK -> {
                if (ROOK_HOME_SQUARE[color][square]) {
                    rooksAtHome[color]--;
                    developmentDirty = true;
                }
            }
            case QUEEN -> {
                queenCount[color]--;
                if (isQueenStartSquare(color, square)) {
                    queenOnStart[color] = false;
                }
                developmentDirty = true;
            }
            default -> {
            }
        }
    }

    private boolean isQueenStartSquare(int color, int square) {
        return QUEEN_START_SQUARE[color] == square;
    }

    private static int scalePieceSquareContribution(int value) {
        int scaled = value * PIECE_SQUARE_WEIGHT_NUMERATOR;
        if (scaled >= 0) {
            return (scaled + PIECE_SQUARE_WEIGHT_DENOMINATOR / 2)
                    / PIECE_SQUARE_WEIGHT_DENOMINATOR;
        }
        return -((-scaled + PIECE_SQUARE_WEIGHT_DENOMINATOR / 2)
                / PIECE_SQUARE_WEIGHT_DENOMINATOR);
    }

    private void recalculateDevelopmentContribution() {
        int whiteStart = (minorPiecesAtHome[WHITE] + rooksAtHome[WHITE] == 6) ? START_POSITION_PENALTY : 0;
        int blackStart = (minorPiecesAtHome[BLACK] + rooksAtHome[BLACK] == 6) ? START_POSITION_PENALTY : 0;

        int whiteMinor = currentPhase >= DEVELOPMENT_PHASE_THRESHOLD
                ? minorPiecesAtHome[WHITE] * UNDEVELOPED_MINOR_PENALTY : 0;
        int blackMinor = currentPhase >= DEVELOPMENT_PHASE_THRESHOLD
                ? minorPiecesAtHome[BLACK] * UNDEVELOPED_MINOR_PENALTY : 0;

        int whiteQueen = 0;
        if (currentPhase <= QUEEN_DEVELOPMENT_PHASE_THRESHOLD && queenCount[WHITE] > 0 && !queenOnStart[WHITE]) {
            if (minorPiecesAtHome[WHITE] >= MIN_UNDEVELOPED_MINORS_FOR_QUEEN_PENALTY) {
                whiteQueen = minorPiecesAtHome[WHITE] * EARLY_QUEEN_DEVELOPMENT_PENALTY_PER_MINOR;
            }
        }

        int blackQueen = 0;
        if (currentPhase <= QUEEN_DEVELOPMENT_PHASE_THRESHOLD && queenCount[BLACK] > 0 && !queenOnStart[BLACK]) {
            if (minorPiecesAtHome[BLACK] >= MIN_UNDEVELOPED_MINORS_FOR_QUEEN_PENALTY) {
                blackQueen = minorPiecesAtHome[BLACK] * EARLY_QUEEN_DEVELOPMENT_PENALTY_PER_MINOR;
            }
        }

        int contribution = (whiteStart + whiteMinor + whiteQueen) - (blackStart + blackMinor + blackQueen);
        int delta = contribution - developmentContribution;
        if (delta != 0) {
            midgameTotal += delta;
            endgameTotal += delta;
            developmentContribution = contribution;
        }
        developmentDirty = false;
    }

    private void recalculateCastlingContribution(EvaluationContext.BoardView board, int phase) {
        if (!castlingDirty) {
            return;
        }
        if (board == null) {
            castlingContribution = 0;
            castlingDirty = false;
            return;
        }
        int contribution = computeCastlingContribution(board, phase);
        int delta = contribution - castlingContribution;
        if (delta != 0) {
            midgameTotal += delta;
            endgameTotal += delta;
            castlingContribution = contribution;
        }
        castlingDirty = false;
    }

    private int computeCastlingContribution(EvaluationContext.BoardView board, int phase) {
        int materialBalance = computeMaterialBalance(board);
        int whiteAdjustment = computeCastlingAdjustment(
                board.whiteKing(),
                board.whitePawns(),
                board.whiteKingHasCastled(),
                board.whiteKingMoved(),
                board.whiteRookA1Moved(),
                board.whiteRookH1Moved(),
                true,
                phase,
                materialBalance
        );
        int blackAdjustment = computeCastlingAdjustment(
                board.blackKing(),
                board.blackPawns(),
                board.blackKingHasCastled(),
                board.blackKingMoved(),
                board.blackRookA8Moved(),
                board.blackRookH8Moved(),
                false,
                phase,
                materialBalance
        );
        return whiteAdjustment - blackAdjustment;
    }

    private int computeCastlingAdjustment(long king,
                                          long pawns,
                                          boolean hasCastled,
                                          boolean kingMoved,
                                          boolean rookAFileMoved,
                                          boolean rookHFileMoved,
                                          boolean white,
                                          int phase,
                                          int materialBalance) {
        if (king == 0L) {
            return 0;
        }
        int castlingBonus = CASTLING_BONUS * (BLEND_SCALE - phase) / BLEND_SCALE;
        int rookMovePenalty = NOT_CASTLED_AND_ROOK_MOVE_PENALTY * (BLEND_SCALE - phase) / BLEND_SCALE;
        if (white) {
            if (materialBalance < 0) {
                rookMovePenalty /= 2;
            }
        } else {
            if (materialBalance > 0) {
                rookMovePenalty /= 2;
            }
        }

        boolean applyPenalty = false;
        if (!hasCastled) {
            int kingIndex = Long.numberOfTrailingZeros(king);
            long forwardMask = getForwardMask(king, white);
            boolean missingShield = Long.bitCount(pawns & forwardMask) < 3;
            int fileIndex = kingIndex & 7;
            long fileMask = FileMasks[fileIndex];
            boolean openOrHalfOpen = (pawns & fileMask) == 0;
            applyPenalty = missingShield || openOrHalfOpen;
        }

        int adjustment = 0;
        if (hasCastled) {
            adjustment += castlingBonus;
        } else if (applyPenalty) {
            if (rookAFileMoved) {
                adjustment += rookMovePenalty;
            }
            if (rookHFileMoved) {
                adjustment += rookMovePenalty;
            }
            if (kingMoved) {
                adjustment += rookMovePenalty * 2;
            }
        }
        return adjustment;
    }

    private static long getForwardMask(long king, boolean white) {
        long forwardMask;
        if (white) {
            forwardMask = king << 8;
            if ((king & NOT_A_FILE) != 0) {
                forwardMask |= (king << 7);
            }
            if ((king & NOT_H_FILE) != 0) {
                forwardMask |= (king << 9);
            }
        } else {
            forwardMask = king >>> 8;
            if ((king & NOT_A_FILE) != 0) {
                forwardMask |= (king >>> 9);
            }
            if ((king & NOT_H_FILE) != 0) {
                forwardMask |= (king >>> 7);
            }
        }
        return forwardMask;
    }

    private static int computeMaterialBalance(EvaluationContext.BoardView board) {
        int whiteMaterial = Long.bitCount(board.whitePawns()) * PAWN_VALUE
                + Long.bitCount(board.whiteKnights()) * KNIGHT_VALUE
                + Long.bitCount(board.whiteBishops()) * BISHOP_VALUE
                + Long.bitCount(board.whiteRooks()) * ROOK_VALUE
                + Long.bitCount(board.whiteQueens()) * QUEEN_VALUE;
        int blackMaterial = Long.bitCount(board.blackPawns()) * PAWN_VALUE
                + Long.bitCount(board.blackKnights()) * KNIGHT_VALUE
                + Long.bitCount(board.blackBishops()) * BISHOP_VALUE
                + Long.bitCount(board.blackRooks()) * ROOK_VALUE
                + Long.bitCount(board.blackQueens()) * QUEEN_VALUE;
        return whiteMaterial - blackMaterial;
    }

    private void rebuildFromBoard(EvaluationContext.BoardView board) {
        Arrays.fill(occupantColor, -1);
        Arrays.fill(occupantPiece, 0);
        Arrays.fill(midgameContributionBySquare, 0);
        Arrays.fill(endgameContributionBySquare, 0);
        Arrays.fill(minorPiecesAtHome, 0);
        Arrays.fill(rooksAtHome, 0);
        Arrays.fill(queenCount, 0);
        Arrays.fill(queenOnStart, false);
        midgameTotal = 0;
        endgameTotal = 0;
        developmentContribution = 0;
        castlingContribution = 0;

        if (board == null) {
            developmentDirty = true;
            castlingDirty = true;
            initialized = true;
            return;
        }

        fillPieces(board.whitePawns(), WHITE, PAWN);
        fillPieces(board.whiteKnights(), WHITE, KNIGHT);
        fillPieces(board.whiteBishops(), WHITE, BISHOP);
        fillPieces(board.whiteRooks(), WHITE, ROOK);
        fillPieces(board.whiteQueens(), WHITE, QUEEN);
        fillPieces(board.whiteKing(), WHITE, KING);

        fillPieces(board.blackPawns(), BLACK, PAWN);
        fillPieces(board.blackKnights(), BLACK, KNIGHT);
        fillPieces(board.blackBishops(), BLACK, BISHOP);
        fillPieces(board.blackRooks(), BLACK, ROOK);
        fillPieces(board.blackQueens(), BLACK, QUEEN);
        fillPieces(board.blackKing(), BLACK, KING);

        developmentDirty = true;
        castlingDirty = true;
        initialized = true;
    }

    private void fillPieces(long bitboard, int color, int pieceType) {
        long remaining = bitboard;
        while (remaining != 0) {
            long sq = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(sq);
            placePiece(index, color, pieceType);
            remaining ^= sq;
        }
    }

    private boolean isForwardMove(MoveContext moveContext) {
        EvaluationContext previous = moveContext.previousContext();
        if (previous == null) {
            return true;
        }
        boolean moverIsWhite = MoveHelper.isWhitesMove(moveContext.move());
        return previous.whiteToMove() == moverIsWhite;
    }

    private static int clampPhase(int phase) {
        if (phase < 0) {
            return 0;
        }
        return Math.min(phase, 256);
    }
}

