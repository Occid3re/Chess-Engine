package julius.game.chessengine.board;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.*;
import julius.game.chessengine.utils.Color;
import julius.game.chessengine.utils.Score;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import java.util.Arrays;

import static julius.game.chessengine.board.MoveHelper.createMoveInt;
import static julius.game.chessengine.helper.BitHelper.*;
import static julius.game.chessengine.helper.KingHelper.KING_ATTACKS;

@Log4j2
@Getter
public class BitBoard {

    private static final PieceType[] PROMOTION_PIECES = {PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT};

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
    private long zKey = 0L;
    @Setter
    private int halfmoveClock = 0;
    @Setter
    private int fullmoveNumber = 1;
    /**
     * Bitboards caching the squares attacked by each side.
     */
    private long whiteAttackMap = 0L;
    private long blackAttackMap = 0L;
    private boolean whiteAttackDirty = true;
    private boolean blackAttackDirty = true;
    private PieceType[] pieceBoard = new PieceType[64];

    private final MoveSnapshot moveSnapshot = new MoveSnapshot();
    private final PieceBitboards attackScratchWhite = new PieceBitboards();
    private final PieceBitboards attackScratchBlack = new PieceBitboards();
    private final PieceBitboards seeWhiteScratch = new PieceBitboards();
    private final PieceBitboards seeBlackScratch = new PieceBitboards();

    // Reuse gain stack in SEE (allocation-free)
    private final int[] seeGain = new int[32];

    private static final int MAX_PSEUDO_LEGAL_MOVES = 218;

    public record PinState(boolean whiteSide, int kingSquare, long diagonalPinned, long straightPinned) {

        public long getAllPinned() {
            return diagonalPinned | straightPinned;
        }
    }

    private record PinRayInfo(long rayMask, int pinnerSquare) {
    }

    public record MoveGenResult(IntArrayList moves, PinState pinState) {

    }

    private static final class PieceBitboards {
        long pawns;
        long knights;
        long bishops;
        long rooks;
        long queens;
        long king;

        PieceBitboards() {
        }

        void load(long pawns, long knights, long bishops, long rooks, long queens, long king) {
            this.pawns = pawns;
            this.knights = knights;
            this.bishops = bishops;
            this.rooks = rooks;
            this.queens = queens;
            this.king = king;
        }

        long get(int pieceBits) {
            return switch (pieceBits) {
                case 1 -> pawns;
                case 2 -> knights;
                case 3 -> bishops;
                case 4 -> rooks;
                case 5 -> queens;
                case 6 -> king;
                default -> 0L;
            };
        }

        void set(int pieceBits, long value) {
            switch (pieceBits) {
                case 1 -> pawns = value;
                case 2 -> knights = value;
                case 3 -> bishops = value;
                case 4 -> rooks = value;
                case 5 -> queens = value;
                case 6 -> king = value;
                default -> {
                }
            }
        }

        void clearBit(int pieceBits, long mask) {
            if (pieceBits >= 1 && pieceBits <= 6) {
                set(pieceBits, get(pieceBits) & ~mask);
            }
        }

        void addBit(int pieceBits, long mask) {
            if (pieceBits >= 1 && pieceBits <= 6) {
                set(pieceBits, get(pieceBits) | mask);
            }
        }
    }

    private static final class MoveSnapshot {
        final PieceBitboards white = new PieceBitboards();
        final PieceBitboards black = new PieceBitboards();
        long occ;

        void load(BitBoard board) {
            white.load(board.whitePawns, board.whiteKnights, board.whiteBishops, board.whiteRooks, board.whiteQueens, board.whiteKing);
            black.load(board.blackPawns, board.blackKnights, board.blackBishops, board.blackRooks, board.blackQueens, board.blackKing);
            occ = board.allPieces;
        }
    }

    @Getter(AccessLevel.NONE)
    private final IntArrayList halfmoveHistory = new IntArrayList();
    @Getter(AccessLevel.NONE)
    private final IntArrayList fullmoveHistory = new IntArrayList();
    @Getter(AccessLevel.NONE)
    private final IntArrayList doubleStepHistory = new IntArrayList();

    // This variable needs to be set whenever a move is made
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

    private void xorPiece(Color color, PieceType type, int square) {
        if (type == null || color == null) {
            log.warn("Attempted to xor Zobrist key with null piece information at square {}", square);
            return;
        }
        zKey ^= ZobristTable.getPieceSquareHash(getPieceIndex(type, color), square);
    }

    private void xorCastlingRight(int index) {
        zKey ^= ZobristTable.getCastlingRightsHash(index);
    }

    private void xorEp(int squareIndex) {
        zKey ^= ZobristTable.getEnPassantSquareHash(squareIndex);
    }

    private void xorSide() {
        zKey ^= ZobristTable.getBlackTurnHash();
    }

    private void recomputeZobristKey() {
        zKey = 0;
        // The Zobrist side-to-move bit is set only when it is Black's turn.
        if (!whitesTurn) {
            xorSide();
        }
        for (int sq = 0; sq < 64; sq++) {
            PieceType pt = pieceBoard[sq];
            if (pt != null) {
                Color c = getPieceColorAtIndex(sq);
                xorPiece(c, pt, sq);
            }
        }
        if (!whiteKingMoved) {
            if (!whiteRookH1Moved) xorCastlingRight(0);
            if (!whiteRookA1Moved) xorCastlingRight(1);
        }
        if (!blackKingMoved) {
            if (!blackRookH8Moved) xorCastlingRight(2);
            if (!blackRookA8Moved) xorCastlingRight(3);
        }
        int ep = getEnPassantTargetIndex();
        if (ep != -1) xorEp(ep);
    }

    public BitBoard(boolean whitesTurn, long whitePawns, long blackPawns, long whiteKnights, long blackKnights, long whiteBishops, long blackBishops, long whiteRooks, long blackRooks, long whiteQueens, long blackQueens, long whiteKing, long blackKing, long whitePieces, long blackPieces, long allPieces, int lastMoveDoubleStepPawnIndex, boolean whiteKingMoved, boolean blackKingMoved, boolean whiteRookA1Moved, boolean whiteRookH1Moved, boolean blackRookA8Moved, boolean blackRookH8Moved, boolean whiteKingHasCastled, boolean blackKingHasCastled, int halfmoveClock, int fullmoveNumber) {
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
        this.halfmoveClock = halfmoveClock;
        this.fullmoveNumber = fullmoveNumber;
        initPieceBoardFromBitboards();
        recomputeWhiteAttackMap();
        recomputeBlackAttackMap();
        recomputeZobristKey();
    }

    public BitBoard() {
        setInitialPosition();
    }

    public BitBoard(BitBoard other) {
        this(other, true);
    }

    private BitBoard(BitBoard other, boolean includeHistory) {
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
        this.whiteAttackMap = other.whiteAttackMap;
        this.blackAttackMap = other.blackAttackMap;
        this.whiteAttackDirty = other.whiteAttackDirty;
        this.blackAttackDirty = other.blackAttackDirty;
        this.zKey = other.zKey;
        this.pieceBoard = Arrays.copyOf(other.pieceBoard, other.pieceBoard.length);
        this.halfmoveClock = other.halfmoveClock;
        this.fullmoveNumber = other.fullmoveNumber;

        if (includeHistory) {
            this.halfmoveHistory.addAll(other.halfmoveHistory);
            this.fullmoveHistory.addAll(other.fullmoveHistory);
            this.doubleStepHistory.addAll(other.doubleStepHistory);
        }
    }

    public void setLastMoveDoubleStepPawnIndex(int index) {
        int oldEp = getEnPassantTargetIndex();
        this.lastMoveDoubleStepPawnIndex = index;
        int newEp = getEnPassantTargetIndex();
        if (oldEp != -1) xorEp(oldEp);
        if (newEp != -1) xorEp(newEp);
    }

    public void flipSideToMove() {
        whitesTurn = !whitesTurn;
        xorSide();
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

    /**
     * Returns all pseudo-legal moves for the side to move.
     *
     * <p>The returned list is freshly allocated on each invocation and is exclusively owned by the
     * caller. Callers may freely modify the contents but should not expect subsequent invocations to
     * reuse or preserve previously returned instances.</p>
     */
    public IntArrayList getAllCurrentPossibleMoves() {
        return generateAllPossibleMoves(whitesTurn);
    }

    public PinState computePinState(boolean whiteSide) {
        int kingSquare = findKingIndex(whiteSide);
        long occ = allPieces;
        long ownPieces = whiteSide ? whitePieces : blackPieces;
        long enemyBishops = whiteSide ? blackBishops : whiteBishops;
        long enemyRooks = whiteSide ? blackRooks : whiteRooks;
        long enemyQueens = whiteSide ? blackQueens : whiteQueens;

        long diagonalPinned = 0L;
        long straightPinned = 0L;

        long diagonalSliders = enemyBishops | enemyQueens;
        while (diagonalSliders != 0) {
            int sliderSq = Long.numberOfTrailingZeros(diagonalSliders);
            diagonalSliders &= diagonalSliders - 1;
            if (!areAlignedDiagonal(kingSquare, sliderSq)) {
                continue;
            }
            long between = BitboardHelper.lineBetweenIndices(kingSquare, sliderSq);
            long blockers = occ & between;
            if (blockers == 0) {
                continue;
            }
            long ownBlockers = blockers & ownPieces;
            if (ownBlockers != 0 && (ownBlockers & (ownBlockers - 1)) == 0 && blockers == ownBlockers) {
                diagonalPinned |= ownBlockers;
            }
        }

        long straightSliders = enemyRooks | enemyQueens;
        while (straightSliders != 0) {
            int sliderSq = Long.numberOfTrailingZeros(straightSliders);
            straightSliders &= straightSliders - 1;
            if (!areAlignedStraight(kingSquare, sliderSq)) {
                continue;
            }
            long between = BitboardHelper.lineBetweenIndices(kingSquare, sliderSq);
            long blockers = occ & between;
            if (blockers == 0) {
                continue;
            }
            long ownBlockers = blockers & ownPieces;
            if (ownBlockers != 0 && (ownBlockers & (ownBlockers - 1)) == 0 && blockers == ownBlockers) {
                straightPinned |= ownBlockers;
            }
        }

        return new PinState(whiteSide, kingSquare, diagonalPinned, straightPinned);
    }

    private boolean isMoveAllowedByPin(PinState pinState, int from, int to) {
        if (pinState == null) {
            return true;
        }
        long fromMask = 1L << from;
        if ((pinState.getAllPinned() & fromMask) == 0) {
            return true;
        }
        if ((pinState.straightPinned() & fromMask) != 0) {
            return areAlignedStraight(pinState.kingSquare(), to)
                    && isOnSameRay(pinState.kingSquare(), from, to, false);
        }
        if ((pinState.diagonalPinned() & fromMask) != 0) {
            return areAlignedDiagonal(pinState.kingSquare(), to)
                    && isOnSameRay(pinState.kingSquare(), from, to, true);
        }
        return true;
    }

    private PinRayInfo resolvePinRayInfo(PinState pinState, int pinnedSquare) {
        int kingSquare = pinState.kingSquare();
        int kingRank = kingSquare >>> 3;
        int kingFile = kingSquare & 7;
        int pieceRank = pinnedSquare >>> 3;
        int pieceFile = pinnedSquare & 7;

        int rankDir = Integer.compare(pieceRank, kingRank);
        int fileDir = Integer.compare(pieceFile, kingFile);

        int currentRank = pieceRank + rankDir;
        int currentFile = pieceFile + fileDir;
        int pinnerSquare = -1;

        while (currentRank >= 0 && currentRank < 8 && currentFile >= 0 && currentFile < 8) {
            int idx = (currentRank << 3) + currentFile;
            long mask = 1L << idx;
            if ((allPieces & mask) != 0) {
                pinnerSquare = idx;
                break;
            }
            currentRank += rankDir;
            currentFile += fileDir;
        }

        long rayMask = 0L;
        if (pinnerSquare != -1) {
            rayMask = BitboardHelper.lineBetweenIndices(kingSquare, pinnerSquare) | (1L << pinnerSquare);
        }

        return new PinRayInfo(rayMask, pinnerSquare);
    }

    private boolean areAlignedDiagonal(int a, int b) {
        int rankA = a >>> 3;
        int fileA = a & 7;
        int rankB = b >>> 3;
        int fileB = b & 7;
        return Math.abs(rankA - rankB) == Math.abs(fileA - fileB);
    }

    private boolean areAlignedStraight(int a, int b) {
        return (a >>> 3) == (b >>> 3) || (a & 7) == (b & 7);
    }

    private boolean isOnSameRay(int kingSquare, int from, int to, boolean diagonal) {
        if (kingSquare == to) {
            return false;
        }
        int kingRank = kingSquare >>> 3;
        int kingFile = kingSquare & 7;
        int fromRank = from >>> 3;
        int fromFile = from & 7;
        int toRank = to >>> 3;
        int toFile = to & 7;

        boolean compareRank = Integer.compare(fromRank, kingRank) == Integer.compare(toRank, kingRank);
        boolean compareFile = Integer.compare(fromFile, kingFile) == Integer.compare(toFile, kingFile);
        if (diagonal) {
            return compareRank
                    && compareFile;
        }
        if (fromFile == kingFile) {
            return compareRank;
        }
        return compareFile;
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
        doubleStepHistory.clear();
        halfmoveClock = 0;
        fullmoveNumber = 1;
        halfmoveHistory.clear();
        fullmoveHistory.clear();
        initPieceBoardFromBitboards();
        recomputeWhiteAttackMap();
        recomputeBlackAttackMap();
        recomputeZobristKey();
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

        whiteAttackDirty = true;
        blackAttackDirty = true;
    }

    /**
     * Bishop-ray attacks from 'sq' with an explicit occupancy.
     */
    private long bishopAttacksFromWithOcc(int sq, long occ) {
        long mask = bishopHelper.bishopMasks[sq];
        long occMasked = occ & mask;
        return bishopHelper.calculateMovesUsingBishopMagic(sq, occMasked);
    }

    /**
     * Rook-ray attacks from 'sq' with an explicit occupancy.
     */
    private long rookAttacksFromWithOcc(int sq, long occ) {
        long mask = rookHelper.rookMasks[sq];
        long occMasked = occ & mask;
        return rookHelper.calculateMovesUsingRookMagic(sq, occMasked);
    }

    /**
     * Bitboard of pawns (for the given side) that attack 'sq'.
     */
    private long pawnAttackersToSquare(int sq, boolean whiteSide, long whitePawnsBB, long blackPawnsBB) {
        int file = sq & 7;
        long res = 0L;
        if (whiteSide) {
            if (file != 7 && sq >= 7) res |= (1L << (sq - 7));  // from ... -> to = +7
            if (file != 0 && sq >= 9) res |= (1L << (sq - 9));  // from ... -> to = +9
            return res & whitePawnsBB;
        } else {
            if (file != 0 && sq <= 56) res |= (1L << (sq + 7));  // from ... -> to = -7
            if (file != 7 && sq <= 54) res |= (1L << (sq + 9));  // from ... -> to = -9
            return res & blackPawnsBB;
        }
    }

    /**
     * Simple Static Exchange Evaluation (SEE).
     * Returns the minimal guaranteed material gain (centipawns) for the side that plays 'move'.
     * Positive => winning capture; Negative => losing capture.
     */
    public int see(int move) {
        // Only meaningful for captures/promotions (we keep 0 for non-captures).
        boolean isCapture = MoveHelper.isCapture(move);
        int promoBits = MoveHelper.derivePromotionPieceTypeBits(move);
        if (!isCapture && promoBits == 0) return 0;

        int from = MoveHelper.deriveFromIndex(move);
        int to = MoveHelper.deriveToIndex(move);
        boolean whiteMove = MoveHelper.isWhitesMove(move);
        int moverBits = MoveHelper.derivePieceTypeBits(move);
        boolean isEp = MoveHelper.isEnPassantMove(move);
        int capturedBits = MoveHelper.deriveCapturedPieceTypeBits(move);

        long occ = allPieces;
        long toMask = 1L << to;
        long fromMask = 1L << from;

        PieceBitboards whiteState = seeWhiteScratch;
        PieceBitboards blackState = seeBlackScratch;
        whiteState.load(whitePawns, whiteKnights, whiteBishops, whiteRooks, whiteQueens, whiteKing);
        blackState.load(blackPawns, blackKnights, blackBishops, blackRooks, blackQueens, blackKing);

        // Remove captured piece from its set/occ
        int capIndex = isEp ? (whiteMove ? (to - 8) : (to + 8)) : to;
        if (isCapture) {
            long capMask = 1L << capIndex;
            if (whiteMove) {
                blackState.clearBit(capturedBits, capMask);
            } else {
                whiteState.clearBit(capturedBits, capMask);
            }
            occ &= ~capMask;
        }

        // Move the attacking piece onto 'to' (promotion changes its type/value)
        int placedBits = (promoBits != 0 ? promoBits : moverBits);
        if (whiteMove) {
            whiteState.clearBit(moverBits, fromMask);
            whiteState.addBit(placedBits, toMask);
        } else {
            blackState.clearBit(moverBits, fromMask);
            blackState.addBit(placedBits, toMask);
        }
        occ &= ~fromMask;
        occ |= toMask;

        // Gain stack (swap-off)
        int[] gain = seeGain;
        int d = 0;

        int capVal = isCapture ? Score.getPieceValue(isEp ? 1 : capturedBits) : 0;
        if (promoBits != 0) {
            capVal += Score.getPieceValue(promoBits) - Score.getPieceValue(1); // promotion delta (to piece - pawn)
        }
        gain[0] = capVal;

        // Track current occupant of 'to' (side/piece) – initially the mover after first capture
        int victimValue = Score.getPieceValue(placedBits);
        int toPieceBits = placedBits;
        boolean toPieceWhite = whiteMove;

        boolean sideWhite = !whiteMove; // opponent to recapture first

        while (true) {
            // Build attackers for the side to move now
            long knights = sideWhite ? whiteState.knights : blackState.knights;
            long bishops = sideWhite ? whiteState.bishops : blackState.bishops;
            long rooks = sideWhite ? whiteState.rooks : blackState.rooks;
            long queens = sideWhite ? whiteState.queens : blackState.queens;
            long king = sideWhite ? whiteState.king : blackState.king;

            long attPawns = pawnAttackersToSquare(to, sideWhite, whiteState.pawns, blackState.pawns);
            long attKnights = KnightHelper.knightMoveTable[to] & knights;
            long bRay = bishopAttacksFromWithOcc(to, occ);
            long rRay = rookAttacksFromWithOcc(to, occ);
            long attBishops = bRay & bishops;
            long attRooks = rRay & rooks;
            long attQueens = (bRay | rRay) & queens;
            long attKing = KING_ATTACKS[to] & king;

            long fromBB;
            int attBits = 0;
            int attFrom = -1;
            long attMask = 0L;

            while (true) {
                if (attPawns != 0) {
                    int candidate = selectLegalAttacker(attPawns, 1, sideWhite, whiteState, blackState, occ, to, toPieceWhite, toPieceBits);
                    if (candidate != -1) {
                        fromBB = 1L << candidate;
                        attBits = 1;
                        attFrom = candidate;
                        attMask = fromBB;
                        break;
                    }
                    attPawns = 0;
                } else if (attKnights != 0) {
                    int candidate = selectLegalAttacker(attKnights, 2, sideWhite, whiteState, blackState, occ, to, toPieceWhite, toPieceBits);
                    if (candidate != -1) {
                        fromBB = 1L << candidate;
                        attBits = 2;
                        attFrom = candidate;
                        attMask = fromBB;
                        break;
                    }
                    attKnights = 0;
                } else if (attBishops != 0) {
                    int candidate = selectLegalAttacker(attBishops, 3, sideWhite, whiteState, blackState, occ, to, toPieceWhite, toPieceBits);
                    if (candidate != -1) {
                        fromBB = 1L << candidate;
                        attBits = 3;
                        attFrom = candidate;
                        attMask = fromBB;
                        break;
                    }
                    attBishops = 0;
                } else if (attRooks != 0) {
                    int candidate = selectLegalAttacker(attRooks, 4, sideWhite, whiteState, blackState, occ, to, toPieceWhite, toPieceBits);
                    if (candidate != -1) {
                        fromBB = 1L << candidate;
                        attBits = 4;
                        attFrom = candidate;
                        attMask = fromBB;
                        break;
                    }
                    attRooks = 0;
                } else if (attQueens != 0) {
                    int candidate = selectLegalAttacker(attQueens, 5, sideWhite, whiteState, blackState, occ, to, toPieceWhite, toPieceBits);
                    if (candidate != -1) {
                        fromBB = 1L << candidate;
                        attBits = 5;
                        attFrom = candidate;
                        attMask = fromBB;
                        break;
                    }
                    attQueens = 0;
                } else if (attKing != 0) {
                    int kingFrom = Long.numberOfTrailingZeros(attKing);
                    if (!isKingCaptureLegal(sideWhite, kingFrom, to, whiteState, blackState, occ, toPieceWhite, toPieceBits)) {
                        attKing = 0;
                        continue;
                    }
                    fromBB = 1L << kingFrom;
                    attBits = 6;
                    attFrom = kingFrom;
                    attMask = fromBB;
                    break;
                } else {
                    break;
                }
            }

            if (attFrom == -1) {
                break; // no more recaptures
            }

            // Remove previous occupant of 'to' (it is being captured now)
            if (toPieceWhite) {
                whiteState.clearBit(toPieceBits, toMask);
            } else {
                blackState.clearBit(toPieceBits, toMask);
            }

            // Move this attacker onto 'to'
            if (sideWhite) {
                whiteState.clearBit(attBits, attMask);
            } else {
                blackState.clearBit(attBits, attMask);
            }
            occ &= ~attMask; // from-square cleared
            // place on 'to' (keep occupancy accurate for slider lookups)
            if (sideWhite) {
                whiteState.addBit(attBits, toMask);
            } else {
                blackState.addBit(attBits, toMask);
            }
            occ |= toMask; // ensure destination remains occupied for future attack discovery

            // Push gain and toggle
            d++;
            gain[d] = victimValue - gain[d - 1];
            victimValue = Score.getPieceValue(attBits);
            toPieceBits = attBits;
            toPieceWhite = sideWhite;
            sideWhite = !sideWhite;
        }

        // Resolve swaps back (fail-soft)
        for (int i = d - 1; i >= 0; i--) {
            gain[i] = -Math.max(-gain[i], gain[i + 1]);
        }
        return gain[0];
    }

    private boolean isKingCaptureLegal(boolean kingIsWhite, int kingFrom, int target,
                                       PieceBitboards whiteState, PieceBitboards blackState, long occ,
                                       boolean toPieceWhite, int toPieceBits) {
        long toMask = 1L << target;
        long fromMask = 1L << kingFrom;

        long originalWhite = whiteState.king;
        long originalBlack = blackState.king;
        long originalCaptured = 0L;

        if (toPieceBits >= 1 && toPieceBits <= 6) {
            if (toPieceWhite) {
                originalCaptured = whiteState.get(toPieceBits);
                whiteState.set(toPieceBits, originalCaptured & ~toMask);
            } else {
                originalCaptured = blackState.get(toPieceBits);
                blackState.set(toPieceBits, originalCaptured & ~toMask);
            }
        }

        if (kingIsWhite) {
            whiteState.king = (originalWhite & ~fromMask) | toMask;
        } else {
            blackState.king = (originalBlack & ~fromMask) | toMask;
        }

        long occCopy = (occ & ~fromMask) | toMask;

        boolean safe = isSquareAttackedNotInSee(target, !kingIsWhite, whiteState, blackState, occCopy);

        if (kingIsWhite) {
            whiteState.king = originalWhite;
        } else {
            blackState.king = originalBlack;
        }
        if (toPieceBits >= 1 && toPieceBits <= 6) {
            if (toPieceWhite) {
                whiteState.set(toPieceBits, originalCaptured);
            } else {
                blackState.set(toPieceBits, originalCaptured);
            }
        }

        return safe;
    }

    private int selectLegalAttacker(long attackers, int attBits, boolean sideWhite,
                                    PieceBitboards whiteState, PieceBitboards blackState, long occ, int target,
                                    boolean toPieceWhite, int toPieceBits) {
        while (attackers != 0) {
            int from = Long.numberOfTrailingZeros(attackers);
            if (attBits == 6 || isNonKingCaptureLegal(sideWhite, from, target, attBits, whiteState, blackState, occ,
                    toPieceWhite, toPieceBits)) {
                return from;
            }
            attackers &= attackers - 1;
        }
        return -1;
    }

    private boolean isNonKingCaptureLegal(boolean attackerWhite, int attackerFrom, int target, int attackerBits,
                                          PieceBitboards whiteState, PieceBitboards blackState, long occ,
                                          boolean toPieceWhite, int toPieceBits) {
        long toMask = 1L << target;
        long fromMask = 1L << attackerFrom;

        long originalAttacker = attackerWhite ? whiteState.get(attackerBits) : blackState.get(attackerBits);
        long originalCaptured = 0L;
        long occCopy = occ;

        if (toPieceBits >= 1 && toPieceBits <= 6) {
            if (toPieceWhite) {
                originalCaptured = whiteState.get(toPieceBits);
                whiteState.set(toPieceBits, originalCaptured & ~toMask);
            } else {
                originalCaptured = blackState.get(toPieceBits);
                blackState.set(toPieceBits, originalCaptured & ~toMask);
            }
            occCopy &= ~toMask;
        }

        if (attackerWhite) {
            whiteState.set(attackerBits, (originalAttacker & ~fromMask) | toMask);
        } else {
            blackState.set(attackerBits, (originalAttacker & ~fromMask) | toMask);
        }

        occCopy &= ~fromMask;
        occCopy |= toMask;

        long kingBB = attackerWhite ? whiteState.king : blackState.king;
        if (kingBB == 0) {
            if (attackerWhite) {
                whiteState.set(attackerBits, originalAttacker);
            } else {
                blackState.set(attackerBits, originalAttacker);
            }
            if (toPieceBits >= 1 && toPieceBits <= 6) {
                if (toPieceWhite) {
                    whiteState.set(toPieceBits, originalCaptured);
                } else {
                    blackState.set(toPieceBits, originalCaptured);
                }
            }
            return false;
        }
        int kingSquare = Long.numberOfTrailingZeros(kingBB);
        boolean safe = isSquareAttackedNotInSee(kingSquare, !attackerWhite, whiteState, blackState, occCopy);

        if (attackerWhite) {
            whiteState.set(attackerBits, originalAttacker);
        } else {
            blackState.set(attackerBits, originalAttacker);
        }
        if (toPieceBits >= 1 && toPieceBits <= 6) {
            if (toPieceWhite) {
                whiteState.set(toPieceBits, originalCaptured);
            } else {
                blackState.set(toPieceBits, originalCaptured);
            }
        }

        return safe;
    }

    private boolean isSquareAttackedNotInSee(int square, boolean attackerWhite,
                                             PieceBitboards whiteState, PieceBitboards blackState, long occ) {
        return isSquareSafe(square, !attackerWhite, occ, whiteState, blackState);
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

    // Fast: set a piece bitboard without recomputing aggregates/attacks.
    // Caller must call updateAggregatedBitboards() and mark dirty after batches.
    private void setBitboardForPieceFast(int pieceTypeBits, boolean isWhite, long bitboard) {
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
    }

    private void markKingAsMoved(boolean isWhite) {
        if (isWhite) {
            whiteKingMoved = true;
        } else {
            blackKingMoved = true;
        }
    }


    /**
     * Generates all pseudo-legal moves for the specified side.
     *
     * <p>A new {@link IntArrayList} is created for every call to guarantee that the caller receives a
     * private container. This avoids the subtle aliasing bugs that occurred when the move generation
     * buffer was shared across threads. The list is pre-sized to the theoretical maximum number of
     * pseudo-legal moves so the amortized allocation cost is limited to the list instance itself.</p>
     *
     * @param whitesTurn {@code true} if white is to move, otherwise {@code false}
     * @return an {@link IntArrayList} containing pseudo-legal moves encoded with
     * {@link MoveHelper#createMoveInt(int, int, PieceType, boolean, boolean, boolean, boolean, PieceType, PieceType, boolean, boolean, int)}
     */

    // New method (rename of existing generateAllPossibleMoves) that exposes pins:
    public MoveGenResult generateAllPossibleMovesWithPins(boolean whitesTurn) {
        IntArrayList moves = new IntArrayList(MAX_PSEUDO_LEGAL_MOVES);
        PinState pinState = generateAllPossibleMovesInto(whitesTurn, moves);
        return new MoveGenResult(moves, pinState);
    }

    public PinState generateAllPossibleMovesInto(boolean whitesTurn, IntArrayList targetMoves) {
        targetMoves.clear();
        PinState pinState = computePinState(whitesTurn);
        final int castlingState = packCastlingState();

        generatePawnMoves(whitesTurn, targetMoves, pinState, castlingState);
        generateKnightMoves(whitesTurn, targetMoves, pinState, castlingState);
        generateBishopMoves(whitesTurn, targetMoves, pinState, castlingState);
        generateRookMoves(whitesTurn, targetMoves, pinState, castlingState);
        generateQueenMoves(whitesTurn, targetMoves, pinState, castlingState);
        generateKingMoves(whitesTurn, targetMoves, castlingState);

        return pinState;
    }

    public IntArrayList generateAllPossibleMoves(boolean whitesTurn) {
        return generateAllPossibleMovesWithPins(whitesTurn).moves;
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

    /**
     * Returns the cached attack bitboard for the given side, recomputing it only when
     * marked dirty. This avoids rebuilding the entire attack map multiple times for the
     * same position.
     */
    public long getAttackBitboard(boolean colorWhite) {
        if (colorWhite) {
            if (whiteAttackDirty) {
                recomputeWhiteAttackMap();
            }
            return whiteAttackMap;
        } else {
            if (blackAttackDirty) {
                recomputeBlackAttackMap();
            }
            return blackAttackMap;
        }
    }

    // Method to set the bitboard for a specific piece type and color
    void updateAggregatedBitboards() {
        whitePieces = whitePawns | whiteKnights | whiteBishops | whiteRooks | whiteQueens | whiteKing;
        blackPieces = blackPawns | blackKnights | blackBishops | blackRooks | blackQueens | blackKing;
        allPieces = whitePieces | blackPieces;
    }

    private void recomputeWhiteAttackMap() {
        whiteAttackMap = generateAttackBitboard(true);
        whiteAttackDirty = false;
    }

    private void recomputeBlackAttackMap() {
        blackAttackMap = generateAttackBitboard(false);
        blackAttackDirty = false;
    }

    // Returns 0 if empty; otherwise 1..6 (PAWN...KING) for the *given side* at index.
    private int pieceBitsAt(int index, boolean whiteSide) {
        long m = 1L << index;
        if (whiteSide) {
            if ((whitePawns & m) != 0)   return 1;
            if ((whiteKnights & m) != 0) return 2;
            if ((whiteBishops & m) != 0) return 3;
            if ((whiteRooks & m) != 0)   return 4;
            if ((whiteQueens & m) != 0)  return 5;
            if ((whiteKing & m) != 0)    return 6;
        } else {
            if ((blackPawns & m) != 0)   return 1;
            if ((blackKnights & m) != 0) return 2;
            if ((blackBishops & m) != 0) return 3;
            if ((blackRooks & m) != 0)   return 4;
            if ((blackQueens & m) != 0)  return 5;
            if ((blackKing & m) != 0)    return 6;
        }
        return 0;
    }

    private void generatePawnMoves(boolean whitesTurn, IntArrayList moves, PinState pinState, int castlingState) {
        final long pawns = whitesTurn ? whitePawns : blackPawns;
        final long opponentPieces = whitesTurn ? blackPieces : whitePieces;
        final long opponentPiecesNoKing = opponentPieces & ~(whitesTurn ? blackKing : whiteKing);
        final long emptySquares = ~allPieces;
        final long promotionRank = whitesTurn ? RankMasks[7] : RankMasks[0];

        // ------------------ Single Pushes ------------------
        long singlePushes = whitesTurn ? (pawns << 8) & emptySquares
                : (pawns >>> 8) & emptySquares;

        // Non-promotion single pushes
        long tmp = singlePushes & ~promotionRank;
        while (tmp != 0) {
            int to = Long.numberOfTrailingZeros(tmp);
            int from = whitesTurn ? to - 8 : to + 8;
            if (isMoveAllowedByPin(pinState, from, to)) {
                moves.add(createMoveInt(from, to, PieceType.PAWN, whitesTurn,
                        false, false, false, null, null, false, false, castlingState));
            }
            tmp &= tmp - 1;
        }

        // Promotion pushes
        tmp = singlePushes & promotionRank;
        while (tmp != 0) {
            int to = Long.numberOfTrailingZeros(tmp);
            int from = whitesTurn ? to - 8 : to + 8;
            if (isMoveAllowedByPin(pinState, from, to)) {
                addPromotionMoves(moves, from, to, whitesTurn, false, null, castlingState);
            }
            tmp &= tmp - 1;
        }

        // ------------------ Double Pushes ------------------
        long doublePushes;
        if (whitesTurn) {
            long pawnsOnSecond = pawns & RankMasks[1];
            doublePushes = ((pawnsOnSecond << 8) & emptySquares);
            doublePushes = (doublePushes << 8) & emptySquares;
        } else {
            long pawnsOnSeventh = pawns & RankMasks[6];
            doublePushes = ((pawnsOnSeventh >>> 8) & emptySquares);
            doublePushes = (doublePushes >>> 8) & emptySquares;
        }

        tmp = doublePushes;
        while (tmp != 0) {
            int to = Long.numberOfTrailingZeros(tmp);
            int from = whitesTurn ? to - 16 : to + 16;
            if (isMoveAllowedByPin(pinState, from, to)) {
                moves.add(createMoveInt(from, to, PieceType.PAWN, whitesTurn,
                        false, false, false, null, null, false, false, castlingState));
            }
            tmp &= tmp - 1;
        }

        // ------------------ Captures ------------------
        long leftAttacks, rightAttacks;
        if (whitesTurn) {
            leftAttacks = (pawns & ~FileMasks[0]) << 7;
            rightAttacks = (pawns & ~FileMasks[7]) << 9;
        } else {
            leftAttacks = (pawns & ~FileMasks[7]) >>> 7;
            rightAttacks = (pawns & ~FileMasks[0]) >>> 9;
        }

        long capLeft = leftAttacks & opponentPiecesNoKing;
        long capRight = rightAttacks & opponentPiecesNoKing;

        // Regular captures to the left (non-promotion)
        tmp = capLeft & ~promotionRank;
        while (tmp != 0) {
            int to = Long.numberOfTrailingZeros(tmp);
            int from; // simpler below
            from = whitesTurn ? to - 7 : to + 7;
            if (isMoveAllowedByPin(pinState, from, to)) {
                int capBits = pieceBitsAt(to, !whitesTurn);
                if (capBits != 6) {
                    moves.add(createMoveInt(from, to, PieceType.PAWN, whitesTurn,
                            true, false, false, null, pieceTypeFromBits(capBits), false, false, castlingState));
                }
            }
            tmp &= tmp - 1;
        }

        // Regular captures to the right (non-promotion)
        tmp = capRight & ~promotionRank;
        while (tmp != 0) {
            int to = Long.numberOfTrailingZeros(tmp);
            int from = whitesTurn ? to - 9 : to + 9;
            if (isMoveAllowedByPin(pinState, from, to)) {
                int capBits = pieceBitsAt(to, !whitesTurn);
                if (capBits != 6) {
                    moves.add(createMoveInt(from, to, PieceType.PAWN, whitesTurn,
                            true, false, false, null, pieceTypeFromBits(capBits), false, false, castlingState));
                }
            }
            tmp &= tmp - 1;
        }

        // Promotion captures
        tmp = capLeft & promotionRank;
        while (tmp != 0) {
            int to = Long.numberOfTrailingZeros(tmp);
            int from = whitesTurn ? to - 7 : to + 7;
            if (isMoveAllowedByPin(pinState, from, to)) {
                int capBits = pieceBitsAt(to, !whitesTurn);
                if (capBits != 6) {
                    addPromotionMoves(moves, from, to, whitesTurn, true, pieceTypeFromBits(capBits), castlingState);
                }
            }
            tmp &= tmp - 1;
        }

        tmp = capRight & promotionRank;
        while (tmp != 0) {
            int to = Long.numberOfTrailingZeros(tmp);
            int from = whitesTurn ? to - 9 : to + 9;
            if (isMoveAllowedByPin(pinState, from, to)) {
                int capBits = pieceBitsAt(to, !whitesTurn);
                if (capBits != 6) {
                    addPromotionMoves(moves, from, to, whitesTurn, true, pieceTypeFromBits(capBits), castlingState);
                }
            }
            tmp &= tmp - 1;
        }

        // En passant (O(1) attackers)
        addEnPassantIfAny(whitesTurn, moves, pinState, castlingState);
    }

    private void addPromotionMoves(IntArrayList moves, int fromIndex, int toIndex,
                                   boolean whitesTurn, boolean isCapture,
                                   PieceType capturedType, int cs) {
        for (PieceType promotionPiece : PROMOTION_PIECES) {
            moves.add(createMoveInt(fromIndex, toIndex, PieceType.PAWN, whitesTurn,
                    isCapture, false, false, promotionPiece, capturedType, false, false, cs));
        }
    }

    private void addEnPassantIfAny(boolean whitesTurn, IntArrayList moves, PinState pinState, int cs) {
        int ep = getEnPassantTargetIndex();
        if (ep == -1) return;

        int epFile = ep & 7;
        int dir = whitesTurn ? -8 : +8;
        int pawnRowFrom = ep + dir; // square of the just-moved pawn
        int leftFrom  = (epFile > 0) ? pawnRowFrom - 1 : -1;
        int rightFrom = (epFile < 7) ? pawnRowFrom + 1 : -1;

        long ownPawns = whitesTurn ? whitePawns : blackPawns;

        if (leftFrom >= 0) {
            long m = 1L << leftFrom;
            if ((ownPawns & m) != 0 && isMoveAllowedByPin(pinState, leftFrom, ep)) {
                moves.add(createMoveInt(leftFrom, ep, PieceType.PAWN, whitesTurn,
                        true, false, true, null, PieceType.PAWN, false, false, cs));
            }
        }
        if (rightFrom >= 0) {
            long m = 1L << rightFrom;
            if ((ownPawns & m) != 0 && isMoveAllowedByPin(pinState, rightFrom, ep)) {
                moves.add(createMoveInt(rightFrom, ep, PieceType.PAWN, whitesTurn,
                        true, false, true, null, PieceType.PAWN, false, false, cs));
            }
        }
    }

    private void generateKnightMoves(boolean whitesTurn, IntArrayList moves, PinState pinState, int castlingState) {
        final long knights = whitesTurn ? whiteKnights : blackKnights;
        final long ownPieces = whitesTurn ? whitePieces : blackPieces;
        final long oppPiecesNoKing = whitesTurn ? (blackPieces & ~blackKing) : (whitePieces & ~whiteKing);
        final long oppKing = whitesTurn ? blackKing : whiteKing;
        // KNIGHT

        long k = knights;
        while (k != 0) {
            int from = Long.numberOfTrailingZeros(k);
            k &= k - 1;

            long potential = KnightHelper.knightMoveTable[from] & ~ownPieces;
            potential &= ~oppKing;

            final boolean mustRespectPin =
                    pinState != null && pinState.whiteSide() == whitesTurn &&
                            ((pinState.getAllPinned() & (1L << from)) != 0);

            while (potential != 0) {
                int to = Long.numberOfTrailingZeros(potential);
                potential &= potential - 1;

                if (mustRespectPin && !isMoveAllowedByPin(pinState, from, to)) continue;

                long toMask = 1L << to;
                boolean isCapture = (oppPiecesNoKing & toMask) != 0;
                if (isCapture) {
                    int capBits = pieceBitsAt(to, !whitesTurn);
                    // capBits cannot be 6 here (king masked out), but be safe:
                    if (capBits == 6) continue;
                    moves.add(createMoveInt(from, to, PieceType.KNIGHT, whitesTurn,
                            true, false, false, null, pieceTypeFromBits(capBits),
                            false, false, castlingState));
                } else {
                    moves.add(createMoveInt(from, to, PieceType.KNIGHT, whitesTurn,
                            false, false, false, null, null,
                            false, false, castlingState));
                }
            }
        }
    }

    private void generateBishopMoves(boolean whitesTurn, IntArrayList moves, PinState pinState, int castlingState) {
        long bishops = whitesTurn ? whiteBishops : blackBishops;
        final long ownPieces = whitesTurn ? whitePieces : blackPieces;
        final long oppPiecesNoKing = whitesTurn ? (blackPieces & ~blackKing) : (whitePieces & ~whiteKing);
        final long oppKing = whitesTurn ? blackKing : whiteKing;
        // BISHOP

        while (bishops != 0) {
            int from = Long.numberOfTrailingZeros(bishops);
            bishops &= bishops - 1;

            long occ = allPieces & bishopHelper.bishopMasks[from];
            long attacks = bishopHelper.calculateMovesUsingBishopMagic(from, occ) & ~ownPieces;
            attacks &= ~oppKing;

            boolean pinned = pinState != null && pinState.whiteSide() == whitesTurn
                    && ((pinState.getAllPinned() & (1L << from)) != 0);
            PinRayInfo pri = null;
            if (pinned) {
                pri = resolvePinRayInfo(pinState, from);
                long allowedMask = pri.rayMask() & ~(1L << pinState.kingSquare());
                attacks &= allowedMask;
            }

            while (attacks != 0) {
                int to = Long.numberOfTrailingZeros(attacks);
                attacks &= attacks - 1;

                long toMask = 1L << to;
                boolean isCapture = (oppPiecesNoKing & toMask) != 0;
                if (isCapture) {
                    int capBits = pieceBitsAt(to, !whitesTurn);
                    if (capBits == 6) continue; // should not happen (king masked), but guard

                    if (pinned && pri.pinnerSquare() != to) continue;

                    moves.add(createMoveInt(from, to, PieceType.BISHOP, whitesTurn,
                            true, false, false, null, pieceTypeFromBits(capBits),
                            false, false, castlingState));
                } else {
                    moves.add(createMoveInt(from, to, PieceType.BISHOP, whitesTurn,
                            false, false, false, null, null,
                            false, false, castlingState));
                }
            }
        }
    }

    private void generateRookMoves(boolean whitesTurn, IntArrayList moves, PinState pinState, int castlingState) {
        long rooks = whitesTurn ? whiteRooks : blackRooks;
        final long ownPieces = whitesTurn ? whitePieces : blackPieces;
        final long oppPiecesNoKing = whitesTurn ? (blackPieces & ~blackKing) : (whitePieces & ~whiteKing);
        final long oppKing = whitesTurn ? blackKing : whiteKing;
        // ROOK

        while (rooks != 0) {
            int from = Long.numberOfTrailingZeros(rooks);
            rooks &= rooks - 1;

            long occ = allPieces & rookHelper.rookMasks[from];
            long attacks = rookHelper.calculateMovesUsingRookMagic(from, occ) & ~ownPieces;
            attacks &= ~oppKing;

            boolean pinned = pinState != null && pinState.whiteSide() == whitesTurn
                    && ((pinState.getAllPinned() & (1L << from)) != 0);
            PinRayInfo pri = null;
            if (pinned) {
                pri = resolvePinRayInfo(pinState, from);
                long allowedMask = pri.rayMask() & ~(1L << pinState.kingSquare());
                attacks &= allowedMask;
            }

            final boolean isFirstRookMove = !hasRookMoved(from);

            while (attacks != 0) {
                int to = Long.numberOfTrailingZeros(attacks);
                attacks &= attacks - 1;

                long toMask = 1L << to;
                boolean isCapture = (oppPiecesNoKing & toMask) != 0;
                if (isCapture) {
                    int capBits = pieceBitsAt(to, !whitesTurn);
                    if (capBits == 6) continue;
                    if (pinned && pri.pinnerSquare() != to) continue;

                    moves.add(createMoveInt(from, to, PieceType.ROOK, whitesTurn,
                            true, false, false, null, pieceTypeFromBits(capBits),
                            false, isFirstRookMove, castlingState));
                } else {
                    moves.add(createMoveInt(from, to, PieceType.ROOK, whitesTurn,
                            false, false, false, null, null,
                            false, isFirstRookMove, castlingState));
                }
            }
        }
    }

    private void generateQueenMoves(boolean whitesTurn, IntArrayList moves, PinState pinState, int castlingState) {
        long queens = whitesTurn ? whiteQueens : blackQueens;
        final long ownPieces = whitesTurn ? whitePieces : blackPieces;
        final long oppPiecesNoKing = whitesTurn ? (blackPieces & ~blackKing) : (whitePieces & ~whiteKing);
        final long oppKing = whitesTurn ? blackKing : whiteKing;
        // QUEEN

        while (queens != 0) {
            int from = Long.numberOfTrailingZeros(queens);
            queens &= queens - 1;

            long occB = allPieces & bishopHelper.bishopMasks[from];
            long occR = allPieces & rookHelper.rookMasks[from];
            long attacks = (bishopHelper.calculateMovesUsingBishopMagic(from, occB)
                    | rookHelper.calculateMovesUsingRookMagic(from, occR)) & ~ownPieces;
            attacks &= ~oppKing;

            boolean pinned = pinState != null && pinState.whiteSide() == whitesTurn
                    && ((pinState.getAllPinned() & (1L << from)) != 0);
            PinRayInfo pri = null;
            if (pinned) {
                pri = resolvePinRayInfo(pinState, from);
                long allowedMask = pri.rayMask() & ~(1L << pinState.kingSquare());
                attacks &= allowedMask;
            }

            while (attacks != 0) {
                int to = Long.numberOfTrailingZeros(attacks);
                attacks &= attacks - 1;

                long toMask = 1L << to;
                boolean isCapture = (oppPiecesNoKing & toMask) != 0;
                if (isCapture) {
                    int capBits = pieceBitsAt(to, !whitesTurn);
                    if (capBits == 6) continue;
                    if (pinned && pri.pinnerSquare() != to) continue;

                    moves.add(createMoveInt(from, to, PieceType.QUEEN, whitesTurn,
                            true, false, false, null, pieceTypeFromBits(capBits),
                            false, false, castlingState));
                } else {
                    moves.add(createMoveInt(from, to, PieceType.QUEEN, whitesTurn,
                            false, false, false, null, null,
                            false, false, castlingState));
                }
            }
        }
    }

    private void generateKingMoves(boolean whitesTurn, IntArrayList moves, int castlingState) {
        long kingBitboard = whitesTurn ? whiteKing : blackKing;
        if (kingBitboard == 0L) return;

        final int from = Long.numberOfTrailingZeros(kingBitboard);
        final long ownPieces = whitesTurn ? whitePieces : blackPieces;
        final long oppPiecesNoKing = whitesTurn ? (blackPieces & ~blackKing) : (whitePieces & ~whiteKing);
        final long oppKing = whitesTurn ? blackKing : whiteKing;
        final boolean isFirstKingMove = hasKingNotMoved(whitesTurn);

        // Recompute opponent attacks once (lazy inside getAttackBitboard)
        final long oppAttacks = getAttackBitboard(!whitesTurn);

        long legal = KING_ATTACKS[from] & ~ownPieces;
        legal &= ~oppKing;

        while (legal != 0) {
            int to = Long.numberOfTrailingZeros(legal);
            legal &= legal - 1;

            long toMask = 1L << to;
            if ((oppAttacks & toMask) != 0) continue; // destination attacked → illegal

            boolean isCapture = (oppPiecesNoKing & toMask) != 0;
            if (isCapture) {
                int capBits = pieceBitsAt(to, !whitesTurn);
                if (capBits == 6) continue; // masked, but guard anyway
                moves.add(createMoveInt(from, to, PieceType.KING, whitesTurn,
                        true, false, false, null, pieceTypeFromBits(capBits), isFirstKingMove, false, castlingState));
            } else {
                moves.add(createMoveInt(from, to, PieceType.KING, whitesTurn,
                        false, false, false, null, null, isFirstKingMove, false, castlingState));
            }
        }

        addCastlingMoves(whitesTurn, from, moves, castlingState);
    }

    private void addCastlingMoves(boolean whitesTurn, int kingPos, IntArrayList moves, int cs) {
        if (!canKingCastle(whitesTurn, kingPos)) return;
        if (canCastleKingside(whitesTurn, kingPos)) {
            moves.add(createMoveInt(kingPos, kingPos + 2, PieceType.KING,
                    whitesTurn, false, true, false, null, null, true, true, cs));
        }
        if (canCastleQueenside(whitesTurn, kingPos)) {
            moves.add(createMoveInt(kingPos, kingPos - 2, PieceType.KING,
                    whitesTurn, false, true, false, null, null, true, true, cs));
        }
    }


    private boolean canKingCastle(boolean whitesTurn, int kingIndex) {
        // King must not have moved and must not be in check now.
        // isSquareUnderAttack() will lazily recompute opponent attack map if dirty.
        return hasKingNotMoved(whitesTurn) && !isSquareUnderAttack(kingIndex, whitesTurn);
    }

    private boolean canCastleKingside(boolean colorWhite, int kingPositionIndex) {
        // Ensure the squares the KING crosses/lands on are empty and not attacked
        int[] kingsideSquares = {kingPositionIndex + 1, kingPositionIndex + 2}; // f-file, g-file
        for (int square : kingsideSquares) {
            if (isOccupied(square) || isSquareUnderAttack(square, colorWhite)) {
                return false;
            }
        }

        // The rook must exist on the corner and must not have moved
        int rookIndex = colorWhite ? 7 : 63;
        if (hasRookMoved(rookIndex)) {
            return false;
        }

        // Rook presence only (rook being attacked or "pinned" is irrelevant for castling rules)
        return isRookAtIndex(rookIndex);
    }

    private boolean canCastleQueenside(boolean colorWhite, int kingPositionIndex) {
        // Ensure the squares the KING crosses/lands on are empty and not attacked.
        // On queenside, the king goes e->d->c. Square b is only for rook travel and may be attacked.
        int[] queensideSquares = {kingPositionIndex - 1, kingPositionIndex - 2, kingPositionIndex - 3}; // d, c, b
        for (int square : queensideSquares) {
            // The king path is d and c; b must be empty for rook travel but need not be safe for the king.
            boolean isKingPath = (square != kingPositionIndex - 3); // exclude 'b' from attack check
            if (isOccupied(square) || (isKingPath && isSquareUnderAttack(square, colorWhite))) {
                return false;
            }
        }

        // The rook must exist on the corner and must not have moved
        int rookIndex = colorWhite ? 0 : 56;
        if (hasRookMoved(rookIndex)) {
            return false;
        }

        // Rook presence only (rook being attacked or "pinned" is irrelevant for castling rules)
        return isRookAtIndex(rookIndex);
    }


    private boolean isRookAtIndex(int index) {
        PieceType pieceAtPosition = getPieceTypeAtIndex(index);
        return pieceAtPosition == PieceType.ROOK;
    }

    // Fast path: no loads, uses live fields directly.
    private boolean isSquareUnderAttack(int index, boolean colorWhite) {
        long attackers = getAttackBitboard(!colorWhite);
        return (attackers & (1L << index)) != 0;
    }


    private boolean isSquareSafe(int index, boolean colorWhite, long occ,
                                 PieceBitboards whiteState, PieceBitboards blackState) {
        return !isSquareUnderAttack(index, colorWhite, occ,
                whiteState.pawns, whiteState.knights, whiteState.bishops, whiteState.rooks, whiteState.queens, whiteState.king,
                blackState.pawns, blackState.knights, blackState.bishops, blackState.rooks, blackState.queens, blackState.king);
    }

    private boolean isSquareUnderAttack(int index, boolean colorWhite, long occ,
                                        long whitePawnsBB, long whiteKnightsBB, long whiteBishopsBB,
                                        long whiteRooksBB, long whiteQueensBB, long whiteKingBB,
                                        long blackPawnsBB, long blackKnightsBB, long blackBishopsBB,
                                        long blackRooksBB, long blackQueensBB, long blackKingBB) {
        if (colorWhite) {
            if (pawnAttackersToSquare(index, false, whitePawnsBB, blackPawnsBB) != 0) return true;
            if ((KnightHelper.knightMoveTable[index] & blackKnightsBB) != 0) return true;
            if ((KING_ATTACKS[index] & blackKingBB) != 0) return true;
            long bishopRays = bishopAttacksFromWithOcc(index, occ);
            if ((bishopRays & (blackBishopsBB | blackQueensBB)) != 0) return true;
            long rookRays = rookAttacksFromWithOcc(index, occ);
            return (rookRays & (blackRooksBB | blackQueensBB)) != 0;
        } else {
            if (pawnAttackersToSquare(index, true, whitePawnsBB, blackPawnsBB) != 0) return true;
            if ((KnightHelper.knightMoveTable[index] & whiteKnightsBB) != 0) return true;
            if ((KING_ATTACKS[index] & whiteKingBB) != 0) return true;
            long bishopRays = bishopAttacksFromWithOcc(index, occ);
            if ((bishopRays & (whiteBishopsBB | whiteQueensBB)) != 0) return true;
            long rookRays = rookAttacksFromWithOcc(index, occ);
            return (rookRays & (whiteRooksBB | whiteQueensBB)) != 0;
        }
    }

    public boolean isMoveLegalFast(int move, PinState pinState) {
        boolean whiteMove = MoveHelper.isWhitesMove(move);
        if (pinState == null || pinState.whiteSide() != whiteMove) {
            pinState = computePinState(whiteMove);
        }

        int from = MoveHelper.deriveFromIndex(move);
        int to = MoveHelper.deriveToIndex(move);
        int pieceBits = MoveHelper.derivePieceTypeBits(move);
        int promotionBits = MoveHelper.derivePromotionPieceTypeBits(move);
        boolean isCapture = MoveHelper.isCapture(move);
        boolean isEnPassant = MoveHelper.isEnPassantMove(move);
        boolean isCastling = MoveHelper.isCastlingMove(move);

        long fromMask = 1L << from;
        long toMask = 1L << to;

        if (pieceBits != 6 && (pinState.getAllPinned() & fromMask) != 0) {
            if ((pinState.straightPinned() & fromMask) != 0) {
                if (!areAlignedStraight(pinState.kingSquare(), to)
                        || !isOnSameRay(pinState.kingSquare(), from, to, false)) {
                    return false;
                }
            } else if ((pinState.diagonalPinned() & fromMask) != 0) {
                if (!areAlignedDiagonal(pinState.kingSquare(), to)
                        || !isOnSameRay(pinState.kingSquare(), from, to, true)) {
                    return false;
                }
            }
        }

        MoveSnapshot snapshot = moveSnapshot;
        snapshot.load(this);
        PieceBitboards whiteState = snapshot.white;
        PieceBitboards blackState = snapshot.black;
        long occ = snapshot.occ;

        if (isCapture) {
            int capturedBits = MoveHelper.deriveCapturedPieceTypeBits(move);
            int captureSquare = isEnPassant ? (whiteMove ? to - 8 : to + 8) : to;
            long captureMask = 1L << captureSquare;
            if (whiteMove) {
                blackState.clearBit(capturedBits, captureMask);
            } else {
                whiteState.clearBit(capturedBits, captureMask);
            }
            occ &= ~captureMask;
        }

        if (whiteMove) {
            whiteState.clearBit(pieceBits, fromMask);
        } else {
            blackState.clearBit(pieceBits, fromMask);
        }
        occ &= ~fromMask;

        int placedBits = promotionBits != 0 ? promotionBits : pieceBits;
        if (whiteMove) {
            whiteState.addBit(placedBits, toMask);
        } else {
            blackState.addBit(placedBits, toMask);
        }
        occ |= toMask;

        if (isCastling) {
            int rookFrom = (to > from) ? to + 1 : to - 2;
            int rookTo = (to > from) ? to - 1 : to + 1;
            long rookFromMask = 1L << rookFrom;
            long rookToMask = 1L << rookTo;
            if (whiteMove) {
                whiteState.clearBit(4, rookFromMask);
                whiteState.addBit(4, rookToMask);
            } else {
                blackState.clearBit(4, rookFromMask);
                blackState.addBit(4, rookToMask);
            }
            occ &= ~rookFromMask;
            occ |= rookToMask;
        }

        int kingSquare = pinState.kingSquare();
        if (pieceBits == 6) {
            kingSquare = to;
        }

        return isSquareSafe(kingSquare, whiteMove, occ, whiteState, blackState);
    }


    // Replace your current performMove with this version
    public void performMove(int move) {
        halfmoveHistory.push(halfmoveClock);
        fullmoveHistory.push(fullmoveNumber);
        doubleStepHistory.push(lastMoveDoubleStepPawnIndex);

        int fromIndex = MoveHelper.deriveFromIndex(move);
        int toIndex = MoveHelper.deriveToIndex(move);
        int pieceBits = MoveHelper.derivePieceTypeBits(move);
        boolean isWhite = MoveHelper.isWhitesMove(move);
        boolean isCapture = MoveHelper.isCapture(move);
        boolean isEnPassant = MoveHelper.isEnPassantMove(move);
        boolean isCastling = MoveHelper.isCastlingMove(move);
        int promoBits = MoveHelper.derivePromotionPieceTypeBits(move);

        long fromMask = 1L << fromIndex;
        long toMask = 1L << toIndex;

        int oldEp = getEnPassantTargetIndex();
        boolean oldWK = !whiteKingMoved && !whiteRookH1Moved;
        boolean oldWQ = !whiteKingMoved && !whiteRookA1Moved;
        boolean oldBK = !blackKingMoved && !blackRookH8Moved;
        boolean oldBQ = !blackKingMoved && !blackRookA8Moved;

        PieceType movingPiece = pieceTypeFromBits(pieceBits);
        Color moverColor = isWhite ? Color.WHITE : Color.BLACK;
        xorPiece(moverColor, movingPiece, fromIndex);
        PieceType placedPiece = promoBits == 0 ? movingPiece : pieceTypeFromBits(promoBits);
        xorPiece(moverColor, placedPiece, toIndex);

        // ---- 1) Captures (fast, no aggregates yet)
        if (isCapture) {
            int capIndex = isEnPassant ? (isWhite ? toIndex - 8 : toIndex + 8) : toIndex;
            PieceType capType = isEnPassant ? PieceType.PAWN : pieceBoard[capIndex];
            Color capColor = isWhite ? Color.BLACK : Color.WHITE;
            xorPiece(capColor, capType, capIndex);
            long capMask = 1L << capIndex;
            switch (capType) {
                case PAWN -> {
                    if (isWhite) {
                        blackPawns &= ~capMask;
                    } else {
                        whitePawns &= ~capMask;
                    }
                }
                case KNIGHT -> {
                    if (isWhite) {
                        blackKnights &= ~capMask;
                    } else {
                        whiteKnights &= ~capMask;
                    }
                }
                case BISHOP -> {
                    if (isWhite) {
                        blackBishops &= ~capMask;
                    } else {
                        whiteBishops &= ~capMask;
                    }
                }
                case ROOK -> {
                    if (isWhite) {
                        blackRooks &= ~capMask;
                    } else {
                        whiteRooks &= ~capMask;
                    }
                }
                case QUEEN -> {
                    if (isWhite) {
                        blackQueens &= ~capMask;
                    } else {
                        whiteQueens &= ~capMask;
                    }
                }
                case KING -> throw new IllegalStateException("Cannot capture the king");
                default -> throw new IllegalArgumentException("Unknown captured piece type: " + capType);
            }
            if (capType == PieceType.ROOK) {
                markRookCaptured(capIndex);
            }
            pieceBoard[capIndex] = null;
        }

        // ---- 2) Castling (move rook fast)
        if (isCastling) {
            if (isWhite) whiteKingHasCastled = true;
            else blackKingHasCastled = true;
            boolean kingside = toIndex > fromIndex;
            int rookFrom = isWhite ? (kingside ? 7 : 0) : (kingside ? 63 : 56);
            int rookTo = kingside ? (rookFrom - 2) : (rookFrom + 3);
            xorPiece(moverColor, PieceType.ROOK, rookFrom);
            xorPiece(moverColor, PieceType.ROOK, rookTo);
            long rfMask = 1L << rookFrom;
            long rtMask = 1L << rookTo;

            if (isWhite) {
                whiteRooks &= ~rfMask;
                whiteRooks |= rtMask;
            } else {
                blackRooks &= ~rfMask;
                blackRooks |= rtMask;
            }
            pieceBoard[rookFrom] = null;
            pieceBoard[rookTo] = PieceType.ROOK;
            markRookAsMoved(rookFrom);
        }

        // ---- 3) Move the piece (fast)

        // remove from 'from'
        switch (pieceBits) {
            case 1 -> {
                if (isWhite) whitePawns &= ~fromMask;
                else blackPawns &= ~fromMask;
            }
            case 2 -> {
                if (isWhite) whiteKnights &= ~fromMask;
                else blackKnights &= ~fromMask;
            }
            case 3 -> {
                if (isWhite) whiteBishops &= ~fromMask;
                else blackBishops &= ~fromMask;
            }
            case 4 -> {
                if (isWhite) whiteRooks &= ~fromMask;
                else blackRooks &= ~fromMask;
            }
            case 5 -> {
                if (isWhite) whiteQueens &= ~fromMask;
                else blackQueens &= ~fromMask;
            }
            case 6 -> {
                if (isWhite) whiteKing &= ~fromMask;
                else blackKing &= ~fromMask;
            }
            default -> throw new IllegalArgumentException("Unknown piece type: " + pieceBits);
        }

        // place on 'to' (promotion handled just below)
        if (promoBits == 0) {
            switch (pieceBits) {
                case 1 -> {
                    if (isWhite) whitePawns |= toMask;
                    else blackPawns |= toMask;
                }
                case 2 -> {
                    if (isWhite) whiteKnights |= toMask;
                    else blackKnights |= toMask;
                }
                case 3 -> {
                    if (isWhite) whiteBishops |= toMask;
                    else blackBishops |= toMask;
                }
                case 4 -> {
                    if (isWhite) whiteRooks |= toMask;
                    else blackRooks |= toMask;
                }
                case 5 -> {
                    if (isWhite) whiteQueens |= toMask;
                    else blackQueens |= toMask;
                }
                case 6 -> {
                    if (isWhite) whiteKing |= toMask;
                    else blackKing |= toMask;
                }
            }
            pieceBoard[toIndex] = movingPiece;
        } else {
            // Promotion: place promoted piece instead of pawn
            switch (promoBits) {
                case 2 -> {
                    if (isWhite) whiteKnights |= toMask;
                    else blackKnights |= toMask;
                    pieceBoard[toIndex] = PieceType.KNIGHT;
                }
                case 3 -> {
                    if (isWhite) whiteBishops |= toMask;
                    else blackBishops |= toMask;
                    pieceBoard[toIndex] = PieceType.BISHOP;
                }
                case 4 -> {
                    if (isWhite) whiteRooks |= toMask;
                    else blackRooks |= toMask;
                    pieceBoard[toIndex] = PieceType.ROOK;
                }
                case 5 -> {
                    if (isWhite) whiteQueens |= toMask;
                    else blackQueens |= toMask;
                    pieceBoard[toIndex] = PieceType.QUEEN;
                }
                default -> throw new IllegalArgumentException("Invalid promotion piece bits: " + promoBits);
            }
        }

        // board mirror for 'from'
        pieceBoard[fromIndex] = null;

        // ---- 4) State flags
        if (pieceBits == 6) {
            markKingAsMoved(isWhite);
        }
        if (pieceBits == 4) {
            markRookAsMoved(fromIndex);
        }

        // En passant target (only if an enemy pawn could actually capture)
        if (pieceBits == 1 && Math.abs(fromIndex / 8 - toIndex / 8) == 2) {
            long enemyPawns = isWhite ? blackPawns : whitePawns;
            int file = toIndex & 7;
            boolean leftEnemy = file > 0 && ((enemyPawns & (1L << (toIndex - 1))) != 0);
            boolean rightEnemy = file < 7 && ((enemyPawns & (1L << (toIndex + 1))) != 0);
            lastMoveDoubleStepPawnIndex = (leftEnemy || rightEnemy) ? toIndex : 0;
        } else {
            lastMoveDoubleStepPawnIndex = 0;
        }

        int newEp = (lastMoveDoubleStepPawnIndex != 0) ? ((isWhite ? 2 : 5) * 8 + (lastMoveDoubleStepPawnIndex & 7)) : -1;
        if (oldEp != -1) xorEp(oldEp);
        if (newEp != -1) xorEp(newEp);

        boolean newWK = !whiteKingMoved && !whiteRookH1Moved;
        boolean newWQ = !whiteKingMoved && !whiteRookA1Moved;
        boolean newBK = !blackKingMoved && !blackRookH8Moved;
        boolean newBQ = !blackKingMoved && !blackRookA8Moved;
        if (oldWK != newWK) xorCastlingRight(0);
        if (oldWQ != newWQ) xorCastlingRight(1);
        if (oldBK != newBK) xorCastlingRight(2);
        if (oldBQ != newBQ) xorCastlingRight(3);

        // ---- 5) Finalize once
        updateAggregatedBitboards();

        // Any move changes slider lines, so both sides’ maps become stale.
        whiteAttackDirty = true;
        blackAttackDirty = true;

        if (isCapture || pieceBits == 1) {
            halfmoveClock = 0;
        } else {
            halfmoveClock++;
        }

        if (!isWhite) {
            fullmoveNumber++;
        }

        flipSideToMove();
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

    private void markRookCaptured(int rookIndex) {
        switch (rookIndex) {
            case 0 -> whiteRookA1Moved = true;
            case 7 -> whiteRookH1Moved = true;
            case 56 -> blackRookA8Moved = true;
            case 63 -> blackRookH8Moved = true;
            default -> {
            }
        }
    }

    private int packCastlingState() {
        int state = 0;
        if (whiteKingMoved) state |= 0x01;
        if (whiteRookA1Moved) state |= 0x02;
        if (whiteRookH1Moved) state |= 0x04;
        if (blackKingMoved) state |= 0x08;
        if (blackRookA8Moved) state |= 0x10;
        if (blackRookH8Moved) state |= 0x20;
        return state;
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

    public boolean isInCheck(boolean white) {
        int kingIndex = findKingIndex(white);
        return isSquareUnderAttack(kingIndex, white);
    }


    private int findKingIndex(boolean whitesTurn) {
        long kingBitboard = whitesTurn ? whiteKing : blackKing;
        if (kingBitboard == 0L) {
            // Defensive: avoid returning 64 from Long.numberOfTrailingZeros(0)
            // which can cause array/bitshift issues elsewhere.
            throw new IllegalStateException((whitesTurn ? "White" : "Black") + " king missing from board state");
        }
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
        log.debug(logBoard.toString()); // Log the current board state
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

    public void undoMove(int move) {
        int fromIndex = MoveHelper.deriveFromIndex(move);
        int toIndex = MoveHelper.deriveToIndex(move);
        int pieceTypeBits = MoveHelper.derivePieceTypeBits(move);
        boolean isWhite = MoveHelper.isWhitesMove(move);
        boolean isCapture = MoveHelper.isCapture(move);
        boolean isEnPassantMove = MoveHelper.isEnPassantMove(move);
        boolean isCastlingMove = MoveHelper.isCastlingMove(move);
        int promotionPieceTypeBits = MoveHelper.derivePromotionPieceTypeBits(move);
        int capturedPieceTypeBits = MoveHelper.deriveCapturedPieceTypeBits(move);
        int castlingStateBits = MoveHelper.deriveCastlingState(move);

        int oldEp = getEnPassantTargetIndex();
        boolean oldWK = !whiteKingMoved && !whiteRookH1Moved;
        boolean oldWQ = !whiteKingMoved && !whiteRookA1Moved;
        boolean oldBK = !blackKingMoved && !blackRookH8Moved;
        boolean oldBQ = !blackKingMoved && !blackRookA8Moved;

        PieceType movingPiece = pieceTypeFromBits(pieceTypeBits);
        Color moverColor = isWhite ? Color.WHITE : Color.BLACK;
        PieceType toPiece = promotionPieceTypeBits == 0 ? movingPiece : pieceTypeFromBits(promotionPieceTypeBits);
        xorPiece(moverColor, toPiece, toIndex);
        xorPiece(moverColor, movingPiece, fromIndex);

        if (isCapture) {
            int capIndex = isEnPassantMove ? (isWhite ? toIndex - 8 : toIndex + 8) : toIndex;
            PieceType capType = pieceTypeFromBits(capturedPieceTypeBits);
            Color capColor = isWhite ? Color.BLACK : Color.WHITE;
            xorPiece(capColor, capType, capIndex);
        }

        if (isCastlingMove) {
            boolean kingside = toIndex > fromIndex;
            int rookFrom = isWhite ? (kingside ? 7 : 0) : (kingside ? 63 : 56);
            int rookTo = kingside ? (rookFrom - 2) : (rookFrom + 3);
            xorPiece(moverColor, PieceType.ROOK, rookFrom);
            xorPiece(moverColor, PieceType.ROOK, rookTo);
        }

        // 1) restore captured piece (bitboards + pieceBoard)
        undoCapture(toIndex, capturedPieceTypeBits, isCapture, isWhite, isEnPassantMove);

        // 2) undo promotion (bitboards + pieceBoard[fromIndex], pieceBoard[toIndex] if no capture)
        undoPromotion(promotionPieceTypeBits, fromIndex, toIndex, isWhite, isCapture);

        // 3) move mover back on bitboards
        undoPieceMove(pieceTypeBits, fromIndex, toIndex, isWhite);

        // 4) clear landing square when nothing should remain there (quiet moves or en passant)
        if (isEnPassantMove || (!isCapture && promotionPieceTypeBits == 0)) {
            pieceBoard[toIndex] = null;
        }

        // 5) restore mover on pieceBoard[from]
        pieceBoard[fromIndex] = pieceTypeFromBits(pieceTypeBits);

        // 6) undo rook movement in castling (bitboards + pieceBoard)
        undoCastling(fromIndex, toIndex, isCastlingMove, isWhite);

        // 7) restore state flags + ep index
        undoGameState(castlingStateBits);

        int newEp = (lastMoveDoubleStepPawnIndex != 0) ? ((isWhite ? 5 : 2) * 8 + (lastMoveDoubleStepPawnIndex & 7)) : -1;
        if (oldEp != -1) xorEp(oldEp);
        if (newEp != -1) xorEp(newEp);

        boolean newWK = !whiteKingMoved && !whiteRookH1Moved;
        boolean newWQ = !whiteKingMoved && !whiteRookA1Moved;
        boolean newBK = !blackKingMoved && !blackRookH8Moved;
        boolean newBQ = !blackKingMoved && !blackRookA8Moved;
        if (oldWK != newWK) xorCastlingRight(0);
        if (oldWQ != newWQ) xorCastlingRight(1);
        if (oldBK != newBK) xorCastlingRight(2);
        if (oldBQ != newBQ) xorCastlingRight(3);

        // 8) finalize aggregates once; mark attacks dirty (lazy recompute)
        updateAggregatedBitboards();
        whiteAttackDirty = true;
        blackAttackDirty = true;

        if (!halfmoveHistory.isEmpty()) {
            halfmoveClock = halfmoveHistory.popInt();
        } else {
            halfmoveClock = 0;
        }

        if (!fullmoveHistory.isEmpty()) {
            fullmoveNumber = fullmoveHistory.popInt();
        } else {
            fullmoveNumber = 1;
        }

        flipSideToMove();
    }

    private void undoGameState(int castlingStateBits) {

        if (!doubleStepHistory.isEmpty()) {
            lastMoveDoubleStepPawnIndex = doubleStepHistory.popInt();
        } else {
            lastMoveDoubleStepPawnIndex = 0;
        }

        boolean whiteKingMovedPrev = (castlingStateBits & 0x01) != 0;
        boolean whiteRookA1MovedPrev = (castlingStateBits & 0x02) != 0;
        boolean whiteRookH1MovedPrev = (castlingStateBits & 0x04) != 0;
        boolean blackKingMovedPrev = (castlingStateBits & 0x08) != 0;
        boolean blackRookA8MovedPrev = (castlingStateBits & 0x10) != 0;
        boolean blackRookH8MovedPrev = (castlingStateBits & 0x20) != 0;

        whiteKingMoved = whiteKingMovedPrev;
        whiteRookA1Moved = whiteRookA1MovedPrev;
        whiteRookH1Moved = whiteRookH1MovedPrev;
        blackKingMoved = blackKingMovedPrev;
        blackRookA8Moved = blackRookA8MovedPrev;
        blackRookH8Moved = blackRookH8MovedPrev;
    }

    private void undoCastling(int fromIndex, int toIndex, boolean isCastling, boolean isWhite) {
        if (!isCastling) return;

        boolean kingside = toIndex > fromIndex;
        if (isWhite) whiteKingHasCastled = false;
        else blackKingHasCastled = false;

        int rookToIndex = isWhite ? (kingside ? 7 : 0) : (kingside ? 63 : 56);
        int rookFromIndex = kingside ? rookToIndex - 2 : rookToIndex + 3;

        long rooks = intToPiecesBitboard(4, isWhite);
        rooks &= ~(1L << rookFromIndex);
        rooks |= (1L << rookToIndex);
        setBitboardForPieceFast(4, isWhite, rooks);

        // mirror on pieceBoard
        pieceBoard[rookFromIndex] = null;
        pieceBoard[rookToIndex] = PieceType.ROOK;
    }

    private void undoPieceMove(int pieceTypeBits, int fromIndex, int toIndex, boolean isWhite) {
        long bb = intToPiecesBitboard(pieceTypeBits, isWhite);
        // move a bit back
        bb &= ~(1L << toIndex);
        bb |= (1L << fromIndex);
        setBitboardForPieceFast(pieceTypeBits, isWhite, bb);
    }


    private void undoPromotion(int promotionPieceTypeBits, int fromIndex, int toIndex, boolean isWhite, boolean wasCapture) {
        if (promotionPieceTypeBits == 0) return;

        long promoted = intToPiecesBitboard(promotionPieceTypeBits, isWhite);
        promoted &= ~(1L << toIndex);
        setBitboardForPieceFast(promotionPieceTypeBits, isWhite, promoted);

        long pawns = intToPiecesBitboard(1, isWhite) | (1L << fromIndex);
        setBitboardForPieceFast(1, isWhite, pawns);

        // mirror on pieceBoard
        pieceBoard[fromIndex] = PieceType.PAWN;
        if (!wasCapture) {
            pieceBoard[toIndex] = null; // if captured, undoCapture already restored captured piece there
        }
    }

    private void undoCapture(int toIndex, int capturedPieceTypeBits, boolean isCapture, boolean isWhite, boolean isEnPassant) {
        if (!isCapture) return;

        int enPassantModifier = isEnPassant ? (isWhite ? -8 : 8) : 0;
        int restoreIndex = toIndex + enPassantModifier;

        long bb = intToPiecesBitboard(capturedPieceTypeBits, !isWhite) | (1L << restoreIndex);
        setBitboardForPieceFast(capturedPieceTypeBits, !isWhite, bb);

        // mirror on pieceBoard
        pieceBoard[restoreIndex] = pieceTypeFromBits(capturedPieceTypeBits);

        if (isEnPassant) {
            lastMoveDoubleStepPawnIndex = restoreIndex;
        }
    }

    public boolean isEndgame() {
        // Your getPhase() returns 0 (opening) .. 256 (pure endgame).
        // Be conservative: only call it endgame in the last ~20% of phase.
        final int PHASE_ENDGAME_THRESHOLD = 208; // ~80% toward endgame

        int phase = getPhase();
        if (phase >= PHASE_ENDGAME_THRESHOLD) {
            return true;
        }

        // --- Additional conservative fallbacks for queenless positions ---
        // Idea: only treat queenless middlegames as "endgame" when material is very reduced.
        boolean noQueens = (whiteQueens | blackQueens) == 0;

        if (noQueens) {
            int rookCount = Long.bitCount(whiteRooks | blackRooks);
            int whiteMinors = Long.bitCount(whiteKnights | whiteBishops);
            int blackMinors = Long.bitCount(blackKnights | blackBishops);
            int totalMinors = whiteMinors + blackMinors;
            int pawnCount = Long.bitCount(whitePawns | blackPawns);

            // Case 1: No rooks, at most one minor per side, and pawns not too crowded
            if (rookCount == 0 && whiteMinors <= 1 && blackMinors <= 1 && pawnCount <= 10) {
                return true;
            }

            // Case 2: At most one rook *total*, at most one minor total (very simplified), reasonable pawn count
            return rookCount <= 1 && totalMinors <= 1 && pawnCount <= 12;
        }

        // Otherwise, keep treating as non-endgame to avoid early king activity.
        return false;
    }

    public int getPhase() {
        int phase = 0;
        phase += Long.bitCount(whiteQueens | blackQueens) * 4;
        phase += Long.bitCount(whiteRooks | blackRooks) * 2;
        phase += Long.bitCount(whiteBishops | blackBishops);
        phase += Long.bitCount(whiteKnights | blackKnights);
        int maxPhase = 24; // material at game start
        return 256 - (phase * 256 / maxPhase);
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
                blackRookH8Moved == bitBoard.blackRookH8Moved &&
                lastMoveDoubleStepPawnIndex == bitBoard.lastMoveDoubleStepPawnIndex &&   // ← added
                halfmoveClock == bitBoard.halfmoveClock &&
                fullmoveNumber == bitBoard.fullmoveNumber;
    }


    @Override
    public int hashCode() {
        // Mix zKey to 32 bits and salt with clocks for better distribution
        long x = zKey;
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= (x >>> 33);
        int h = Long.hashCode(x);
        h = 31 * h + halfmoveClock;
        h = 31 * h + fullmoveNumber;
        return h;
    }


    /**
     * Returns the en-passant *target* square index [0..63], or -1 if none.
     */
    public int getEnPassantTargetIndex() {
        if (lastMoveDoubleStepPawnIndex == 0) return -1;
        // lastMoveDoubleStepPawnIndex is the double-stepped pawn's landing square
        // The target square is on rank 5 if it's White to move now (Black just moved),
        // or rank 2 if it's Black to move now (White just moved).
        int file = lastMoveDoubleStepPawnIndex & 7;
        int rank = whitesTurn ? 5 : 2;
        return rank * 8 + file;
    }


    private int getPieceIndex(PieceType pieceType, Color pieceColor) {
        int index = pieceType.ordinal() * 2; // There are two colors for each piece type
        if (pieceColor == Color.BLACK) {
            index++; // Add 1 for black pieces
        }
        return index;
    }

    public long getBoardStateHash() {
        return zKey;
    }

}
