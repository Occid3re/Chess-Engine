package julius.game.chessengine.board;

import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.BitboardHelper;
import julius.game.chessengine.helper.KnightHelper;
import julius.game.chessengine.helper.RookHelper;
import julius.game.chessengine.helper.ZobristTable;
import julius.game.chessengine.utils.Color;
import julius.game.chessengine.board.MoveHelper;

import julius.game.chessengine.utils.Score;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Objects;

import static julius.game.chessengine.board.MoveHelper.createMoveInt;
import static julius.game.chessengine.helper.BitHelper.*;
import static julius.game.chessengine.helper.KingHelper.KING_ATTACKS;
import static julius.game.chessengine.helper.KnightHelper.knightMoveTable;

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
    private int halfmoveClock = 0;
    private int fullmoveNumber = 1;
    /**
     * Bitboards caching the squares attacked by each side.
     */
    private long whiteAttackMap = 0L;
    private long blackAttackMap = 0L;
    private boolean whiteAttackDirty = true;
    private boolean blackAttackDirty = true;
    private PieceType[] pieceBoard = new PieceType[64];

    // Reusable buffer for move generation to avoid frequent allocations.
    private final MoveList moveGenerationBuffer = new MoveList();

    /**
     * Scratch buffers for {@link #isMoveLegalFast(int, PinState)}.
     *
     * <p>The buffers are mutable and therefore must only be accessed while holding the engine's
     * {@code boardLock}. Callers are expected to register that monitor via
     * {@link #setMoveLegalityScratchGuard(Object)} before invoking the fast legality checks.</p>
     */
    private final long[][] moveLegalityScratch = {new long[7], new long[7]};
    private volatile Object moveLegalityScratchGuard;

    public static final class PinState {
        private final boolean whiteSide;
        private final int kingSquare;
        private final long diagonalPinned;
        private final long straightPinned;

        public PinState(boolean whiteSide, int kingSquare, long diagonalPinned, long straightPinned) {
            this.whiteSide = whiteSide;
            this.kingSquare = kingSquare;
            this.diagonalPinned = diagonalPinned;
            this.straightPinned = straightPinned;
        }

        public boolean isWhiteSide() {
            return whiteSide;
        }

        public int getKingSquare() {
            return kingSquare;
        }

        public long getDiagonalPinned() {
            return diagonalPinned;
        }

        public long getStraightPinned() {
            return straightPinned;
        }

        public long getAllPinned() {
            return diagonalPinned | straightPinned;
        }
    }

    private static final class PinRayInfo {
        private final long rayMask;
        private final int pinnerSquare;

        private PinRayInfo(long rayMask, int pinnerSquare) {
            this.rayMask = rayMask;
            this.pinnerSquare = pinnerSquare;
        }

        public long getRayMask() {
            return rayMask;
        }

        public int getPinnerSquare() {
            return pinnerSquare;
        }
    }

    @Getter(AccessLevel.NONE)
    private final Deque<Integer> halfmoveHistory = new ArrayDeque<>();
    @Getter(AccessLevel.NONE)
    private final Deque<Integer> fullmoveHistory = new ArrayDeque<>();

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
        if (whitesTurn) {
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
        this.halfmoveHistory.clear();
        this.fullmoveHistory.clear();
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
        }
        this.moveLegalityScratchGuard = other.moveLegalityScratchGuard;
    }

    /**
     * Registers the monitor that must be held while accessing {@link #moveLegalityScratch}.
     * The provided object should be the same {@code boardLock} used by {@code Engine} to guard
     * move generation, ensuring these mutable buffers are never mutated concurrently.
     *
     * @param boardLock the monitor protecting move generation, or {@code null} to clear the guard
     */
    public void setMoveLegalityScratchGuard(Object boardLock) {
        this.moveLegalityScratchGuard = boardLock;
    }

    public BitBoard snapshotWithoutHistory() {
        BitBoard snapshot = new BitBoard(this, false);
        snapshot.moveLegalityScratchGuard = this.moveLegalityScratchGuard;
        return snapshot;
    }
    public void setLastMoveDoubleStepPawnIndex(int index) {
        int oldEp = getEnPassantTargetIndex();
        this.lastMoveDoubleStepPawnIndex = index;
        int newEp = getEnPassantTargetIndex();
        if (oldEp != -1) xorEp(oldEp);
        if (newEp != -1) xorEp(newEp);
    }

    public void setHalfmoveClock(int halfmoveClock) {
        this.halfmoveClock = halfmoveClock;
    }

    public void setFullmoveNumber(int fullmoveNumber) {
        this.fullmoveNumber = fullmoveNumber;
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

    public MoveList getAllCurrentPossibleMoves() {
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
        if ((pinState.getStraightPinned() & fromMask) != 0) {
            return areAlignedStraight(pinState.getKingSquare(), to)
                    && isOnSameRay(pinState.getKingSquare(), from, to, false);
        }
        if ((pinState.getDiagonalPinned() & fromMask) != 0) {
            return areAlignedDiagonal(pinState.getKingSquare(), to)
                    && isOnSameRay(pinState.getKingSquare(), from, to, true);
        }
        return true;
    }

    private PinRayInfo resolvePinRayInfo(PinState pinState, int pinnedSquare) {
        int kingSquare = pinState.getKingSquare();
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
        if (diagonal) {
            return Integer.compare(fromRank, kingRank) == Integer.compare(toRank, kingRank)
                    && Integer.compare(fromFile, kingFile) == Integer.compare(toFile, kingFile);
        }
        if (fromFile == kingFile) {
            return Integer.compare(fromRank, kingRank) == Integer.compare(toRank, kingRank);
        }
        return Integer.compare(fromFile, kingFile) == Integer.compare(toFile, kingFile);
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

    /** Bishop-ray attacks from 'sq' with an explicit occupancy. */
    private long bishopAttacksFromWithOcc(int sq, long occ) {
        long mask = bishopHelper.bishopMasks[sq];
        long occMasked = occ & mask;
        return bishopHelper.calculateMovesUsingBishopMagic(sq, occMasked);
    }

    /** Rook-ray attacks from 'sq' with an explicit occupancy. */
    private long rookAttacksFromWithOcc(int sq, long occ) {
        long mask = rookHelper.rookMasks[sq];
        long occMasked = occ & mask;
        return rookHelper.calculateMovesUsingRookMagic(sq, occMasked);
    }

    /** Bitboard of pawns (for the given side) that attack 'sq'. */
    private long pawnAttackersToSquare(int sq, boolean whiteSide, long whitePawnsBB, long blackPawnsBB) {
        int file = sq & 7;
        long res = 0L;
        if (whiteSide) {
            if (file != 7 && sq >= 7)  res |= (1L << (sq - 7));  // from ... -> to = +7
            if (file != 0 && sq >= 9)  res |= (1L << (sq - 9));  // from ... -> to = +9
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
        int to   = MoveHelper.deriveToIndex(move);
        boolean whiteMove = MoveHelper.isWhitesMove(move);
        int moverBits = MoveHelper.derivePieceTypeBits(move);
        boolean isEp = MoveHelper.isEnPassantMove(move);
        int capturedBits = MoveHelper.deriveCapturedPieceTypeBits(move);

        long occ = allPieces;
        long toMask = 1L << to;
        long fromMask = 1L << from;

        // Local mutable copies of per-piece bitboards (index 1..6)
        long[] W = new long[7];
        long[] B = new long[7];
        W[1]=whitePawns; W[2]=whiteKnights; W[3]=whiteBishops; W[4]=whiteRooks; W[5]=whiteQueens; W[6]=whiteKing;
        B[1]=blackPawns; B[2]=blackKnights; B[3]=blackBishops; B[4]=blackRooks; B[5]=blackQueens; B[6]=blackKing;

        // Remove captured piece from its set/occ
        int capIndex = isEp ? (whiteMove ? (to - 8) : (to + 8)) : to;
        if (isCapture) {
            long capMask = 1L << capIndex;
            if (whiteMove) {
                if (capturedBits >= 1 && capturedBits <= 6) B[capturedBits] &= ~capMask;
            } else {
                if (capturedBits >= 1 && capturedBits <= 6) W[capturedBits] &= ~capMask;
            }
            occ &= ~capMask;
        }

        // Move the attacking piece onto 'to' (promotion changes its type/value)
        int placedBits = (promoBits != 0 ? promoBits : moverBits);
        if (whiteMove) { W[moverBits] &= ~fromMask; W[placedBits] |= toMask; }
        else           { B[moverBits] &= ~fromMask; B[placedBits] |= toMask; }
        occ &= ~fromMask;
        occ |= toMask;

        // Gain stack (swap-off)
        int[] gain = new int[32];
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
            long pawns   = sideWhite ? W[1] : B[1];
            long knights = sideWhite ? W[2] : B[2];
            long bishops = sideWhite ? W[3] : B[3];
            long rooks   = sideWhite ? W[4] : B[4];
            long queens  = sideWhite ? W[5] : B[5];
            long king    = sideWhite ? W[6] : B[6];

            long attPawns   = pawnAttackersToSquare(to, sideWhite, W[1], B[1]);
            long attKnights = KnightHelper.knightMoveTable[to] & knights;
            long bRay = bishopAttacksFromWithOcc(to, occ);
            long rRay = rookAttacksFromWithOcc(to, occ);
            long attBishops = bRay & bishops;
            long attRooks   = rRay & rooks;
            long attQueens  = (bRay | rRay) & queens;
            long attKing    = KING_ATTACKS[to] & king;

            long fromBB = 0L;
            int  attBits = 0;
            int attFrom = -1;
            long attMask = 0L;

            while (true) {
                if (attPawns != 0) {
                    int candidate = selectLegalAttacker(attPawns, 1, sideWhite, W, B, occ, to, toPieceWhite, toPieceBits);
                    if (candidate != -1) {
                        fromBB = 1L << candidate;
                        attBits = 1;
                        attFrom = candidate;
                        attMask = fromBB;
                        break;
                    }
                    attPawns = 0;
                } else if (attKnights != 0) {
                    int candidate = selectLegalAttacker(attKnights, 2, sideWhite, W, B, occ, to, toPieceWhite, toPieceBits);
                    if (candidate != -1) {
                        fromBB = 1L << candidate;
                        attBits = 2;
                        attFrom = candidate;
                        attMask = fromBB;
                        break;
                    }
                    attKnights = 0;
                } else if (attBishops != 0) {
                    int candidate = selectLegalAttacker(attBishops, 3, sideWhite, W, B, occ, to, toPieceWhite, toPieceBits);
                    if (candidate != -1) {
                        fromBB = 1L << candidate;
                        attBits = 3;
                        attFrom = candidate;
                        attMask = fromBB;
                        break;
                    }
                    attBishops = 0;
                } else if (attRooks != 0) {
                    int candidate = selectLegalAttacker(attRooks, 4, sideWhite, W, B, occ, to, toPieceWhite, toPieceBits);
                    if (candidate != -1) {
                        fromBB = 1L << candidate;
                        attBits = 4;
                        attFrom = candidate;
                        attMask = fromBB;
                        break;
                    }
                    attRooks = 0;
                } else if (attQueens != 0) {
                    int candidate = selectLegalAttacker(attQueens, 5, sideWhite, W, B, occ, to, toPieceWhite, toPieceBits);
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
                    if (!isKingCaptureLegal(sideWhite, kingFrom, to, W, B, occ, toPieceWhite, toPieceBits)) {
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
            if (toPieceWhite) W[toPieceBits] &= ~toMask;
            else              B[toPieceBits] &= ~toMask;

            // Move this attacker onto 'to'
            if (sideWhite) {
                switch (attBits) {
                    case 1 -> W[1] &= ~attMask;
                    case 2 -> W[2] &= ~attMask;
                    case 3 -> W[3] &= ~attMask;
                    case 4 -> W[4] &= ~attMask;
                    case 5 -> W[5] &= ~attMask;
                    case 6 -> W[6] &= ~attMask;
                }
            } else {
                switch (attBits) {
                    case 1 -> B[1] &= ~attMask;
                    case 2 -> B[2] &= ~attMask;
                    case 3 -> B[3] &= ~attMask;
                    case 4 -> B[4] &= ~attMask;
                    case 5 -> B[5] &= ~attMask;
                    case 6 -> B[6] &= ~attMask;
                }
            }
            occ &= ~attMask; // from-square cleared
            // place on 'to' (keep occupancy accurate for slider lookups)
            if (sideWhite) {
                switch (attBits) {
                    case 1 -> W[1] |= toMask;
                    case 2 -> W[2] |= toMask;
                    case 3 -> W[3] |= toMask;
                    case 4 -> W[4] |= toMask;
                    case 5 -> W[5] |= toMask;
                    case 6 -> W[6] |= toMask;
                }
            } else {
                switch (attBits) {
                    case 1 -> B[1] |= toMask;
                    case 2 -> B[2] |= toMask;
                    case 3 -> B[3] |= toMask;
                    case 4 -> B[4] |= toMask;
                    case 5 -> B[5] |= toMask;
                    case 6 -> B[6] |= toMask;
                }
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
                                       long[] whiteByType, long[] blackByType, long occ,
                                       boolean toPieceWhite, int toPieceBits) {
        long[] wCopy = whiteByType.clone();
        long[] bCopy = blackByType.clone();
        long occCopy = occ;

        long toMask = 1L << target;
        long fromMask = 1L << kingFrom;

        if (toPieceBits >= 1 && toPieceBits <= 6) {
            if (toPieceWhite) {
                wCopy[toPieceBits] &= ~toMask;
            } else {
                bCopy[toPieceBits] &= ~toMask;
            }
        }

        if (kingIsWhite) {
            wCopy[6] &= ~fromMask;
            wCopy[6] |= toMask;
        } else {
            bCopy[6] &= ~fromMask;
            bCopy[6] |= toMask;
        }

        occCopy &= ~fromMask;
        occCopy |= toMask;

        return !isSquareAttackedInSee(target, !kingIsWhite, wCopy, bCopy, occCopy);
    }

    private int selectLegalAttacker(long attackers, int attBits, boolean sideWhite,
                                    long[] whiteByType, long[] blackByType, long occ, int target,
                                    boolean toPieceWhite, int toPieceBits) {
        while (attackers != 0) {
            int from = Long.numberOfTrailingZeros(attackers);
            if (attBits == 6 || isNonKingCaptureLegal(sideWhite, from, target, attBits, whiteByType, blackByType, occ,
                                                     toPieceWhite, toPieceBits)) {
                return from;
            }
            attackers &= attackers - 1;
        }
        return -1;
    }

    private boolean isNonKingCaptureLegal(boolean attackerWhite, int attackerFrom, int target, int attackerBits,
                                          long[] whiteByType, long[] blackByType, long occ,
                                          boolean toPieceWhite, int toPieceBits) {
        long[] wCopy = whiteByType.clone();
        long[] bCopy = blackByType.clone();
        long occCopy = occ;

        long toMask = 1L << target;
        long fromMask = 1L << attackerFrom;

        if (toPieceBits >= 1 && toPieceBits <= 6) {
            if (toPieceWhite) {
                wCopy[toPieceBits] &= ~toMask;
            } else {
                bCopy[toPieceBits] &= ~toMask;
            }
            occCopy &= ~toMask;
        }

        if (attackerWhite) {
            wCopy[attackerBits] &= ~fromMask;
            wCopy[attackerBits] |= toMask;
        } else {
            bCopy[attackerBits] &= ~fromMask;
            bCopy[attackerBits] |= toMask;
        }

        occCopy &= ~fromMask;
        occCopy |= toMask;

        long kingBB = attackerWhite ? wCopy[6] : bCopy[6];
        if (kingBB == 0) {
            return false;
        }
        int kingSquare = Long.numberOfTrailingZeros(kingBB);
        return !isSquareAttackedInSee(kingSquare, !attackerWhite, wCopy, bCopy, occCopy);
    }

    private boolean isSquareAttackedInSee(int square, boolean attackerWhite,
                                          long[] whiteByType, long[] blackByType, long occ) {
        return isSquareUnderAttack(square, !attackerWhite, occ, whiteByType, blackByType);
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

    public MoveList generateAllPossibleMoves(boolean whitesTurn) {
        // Reuse the internal buffer to cut down on object creation and GC pressure.
        MoveList moves = moveGenerationBuffer;
        moves.clear();

        // Do NOT recompute white/black attack maps here.
        // They are recomputed lazily inside isSquareUnderAttack() only when needed
        // (e.g., castling checks, isInCheck). This saves a full-board attack rebuild
        // on most plies where castling isn’t even considered.

        PinState pinState = computePinState(whitesTurn);

        generatePawnMoves(whitesTurn, moves, pinState);
        generateKnightMoves(whitesTurn, moves, pinState);
        generateBishopMoves(whitesTurn, moves, pinState);
        generateRookMoves(whitesTurn, moves, pinState);
        generateQueenMoves(whitesTurn, moves, pinState);
        generateKingMoves(whitesTurn, moves, pinState);

        return moves;
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

    private void generatePawnMoves(boolean whitesTurn, MoveList moves, PinState pinState) {
        long pawns = whitesTurn ? whitePawns : blackPawns;
        long opponentPieces = whitesTurn ? blackPieces : whitePieces;
        long emptySquares = ~allPieces;

        // ------------------ Single Pushes ------------------
        long singlePushes = whitesTurn ? (pawns << 8) & emptySquares
                : (pawns >>> 8) & emptySquares;

        long promotionRank = whitesTurn ? RankMasks[7] : RankMasks[0];

        // Non-promotion single pushes
        long temp = singlePushes & ~promotionRank;
        while (temp != 0) {
            int toIndex = Long.numberOfTrailingZeros(temp);
            int fromIndex = whitesTurn ? toIndex - 8 : toIndex + 8;
            if (isMoveAllowedByPin(pinState, fromIndex, toIndex)) {
                moves.add(createMoveInt(fromIndex, toIndex, PieceType.PAWN, whitesTurn, false, false, false,
                        null, null, false, false, lastMoveDoubleStepPawnIndex));
            }
            temp &= temp - 1;
        }

        // Promotion pushes
        temp = singlePushes & promotionRank;
        while (temp != 0) {
            int toIndex = Long.numberOfTrailingZeros(temp);
            int fromIndex = whitesTurn ? toIndex - 8 : toIndex + 8;
            if (isMoveAllowedByPin(pinState, fromIndex, toIndex)) {
                addPromotionMoves(moves, fromIndex, toIndex, whitesTurn, false, null);
            }
            temp &= temp - 1;
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

        temp = doublePushes;
        while (temp != 0) {
            int toIndex = Long.numberOfTrailingZeros(temp);
            int fromIndex = whitesTurn ? toIndex - 16 : toIndex + 16;
            if (isMoveAllowedByPin(pinState, fromIndex, toIndex)) {
                moves.add(createMoveInt(fromIndex, toIndex, PieceType.PAWN, whitesTurn, false, false, false,
                        null, null, false, false, lastMoveDoubleStepPawnIndex));
            }
            temp &= temp - 1;
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

        leftAttacks &= opponentPieces;
        rightAttacks &= opponentPieces;

        long promotionLeft = leftAttacks & promotionRank;
        long promotionRight = rightAttacks & promotionRank;
        long captureLeft = leftAttacks & ~promotionRank;
        long captureRight = rightAttacks & ~promotionRank;

        // Regular captures to the left
        temp = captureLeft;
        while (temp != 0) {
            int toIndex = Long.numberOfTrailingZeros(temp);
            int fromIndex = whitesTurn ? toIndex - 7 : toIndex + 7;
            PieceType capturedType = getPieceTypeAtIndex(toIndex);
            if (capturedType != PieceType.KING && isMoveAllowedByPin(pinState, fromIndex, toIndex)) {
                moves.add(createMoveInt(fromIndex, toIndex, PieceType.PAWN, whitesTurn, true, false, false,
                        null, capturedType, false, false, lastMoveDoubleStepPawnIndex));
            }
            temp &= temp - 1;
        }

        // Regular captures to the right
        temp = captureRight;
        while (temp != 0) {
            int toIndex = Long.numberOfTrailingZeros(temp);
            int fromIndex = whitesTurn ? toIndex - 9 : toIndex + 9;
            PieceType capturedType = getPieceTypeAtIndex(toIndex);
            if (capturedType != PieceType.KING && isMoveAllowedByPin(pinState, fromIndex, toIndex)) {
                moves.add(createMoveInt(fromIndex, toIndex, PieceType.PAWN, whitesTurn, true, false, false,
                        null, capturedType, false, false, lastMoveDoubleStepPawnIndex));
            }
            temp &= temp - 1;
        }

        // Promotion captures
        temp = promotionLeft;
        while (temp != 0) {
            int toIndex = Long.numberOfTrailingZeros(temp);
            int fromIndex = whitesTurn ? toIndex - 7 : toIndex + 7;
            PieceType capturedType = getPieceTypeAtIndex(toIndex);
            if (capturedType != PieceType.KING && isMoveAllowedByPin(pinState, fromIndex, toIndex)) {
                addPromotionMoves(moves, fromIndex, toIndex, whitesTurn, true, capturedType);
            }
            temp &= temp - 1;
        }

        temp = promotionRight;
        while (temp != 0) {
            int toIndex = Long.numberOfTrailingZeros(temp);
            int fromIndex = whitesTurn ? toIndex - 9 : toIndex + 9;
            PieceType capturedType = getPieceTypeAtIndex(toIndex);
            if (capturedType != PieceType.KING && isMoveAllowedByPin(pinState, fromIndex, toIndex)) {
                addPromotionMoves(moves, fromIndex, toIndex, whitesTurn, true, capturedType);
            }
            temp &= temp - 1;
        }

        // ------------------ En Passant ------------------
        if (lastMoveDoubleStepPawnIndex != 0) {
            generateEnPassantMoves(moves, pawns, whitesTurn, pinState);
        }

    }

    private void generateEnPassantMoves(MoveList moves, long pawns, boolean whitesTurn, PinState pinState) {
        int enPassantRank = whitesTurn ? 5 : 2;
        int fileIndexOfDoubleSteppedPawn = lastMoveDoubleStepPawnIndex % 8;
        int enPassantTargetIndex = (enPassantRank * 8) + fileIndexOfDoubleSteppedPawn;
        long enPassantTargetSquare = 1L << enPassantTargetIndex;
        long potentialEnPassantAttackers = pawns & RankMasks[whitesTurn ? 4 : 3];

        if (fileIndexOfDoubleSteppedPawn > 0) {
            long leftAttackers = potentialEnPassantAttackers & FileMasks[fileIndexOfDoubleSteppedPawn - 1];
            while (leftAttackers != 0) {
                int from = Long.numberOfTrailingZeros(leftAttackers);
                boolean hits = whitesTurn
                        ? (((1L << from) << 9) & enPassantTargetSquare) != 0
                        : (((1L << from) >> 7) & enPassantTargetSquare) != 0;
                if (hits && isMoveAllowedByPin(pinState, from, enPassantTargetIndex)) {
                    addEnPassantMove(moves, from, enPassantTargetIndex, whitesTurn);
                }
                leftAttackers &= leftAttackers - 1;
            }
        }

        if (fileIndexOfDoubleSteppedPawn < 7) {
            long rightAttackers = potentialEnPassantAttackers & FileMasks[fileIndexOfDoubleSteppedPawn + 1];
            while (rightAttackers != 0) {
                int from = Long.numberOfTrailingZeros(rightAttackers);
                boolean hits = whitesTurn
                        ? (((1L << from) << 7) & enPassantTargetSquare) != 0
                        : (((1L << from) >> 9) & enPassantTargetSquare) != 0;
                if (hits && isMoveAllowedByPin(pinState, from, enPassantTargetIndex)) {
                    addEnPassantMove(moves, from, enPassantTargetIndex, whitesTurn);
                }
                rightAttackers &= rightAttackers - 1;
            }
        }
    }


    private void addEnPassantMove(MoveList moves, int fromIndex, int toIndex, boolean whitesTurn) {
        moves.add(
                createMoveInt(fromIndex, toIndex, PieceType.PAWN, whitesTurn, true, false, true, null, PieceType.PAWN, false, false, lastMoveDoubleStepPawnIndex));
    }

    private void addPromotionMoves(MoveList moves, int fromIndex, int toIndex, boolean whitesTurn, boolean isCapture, PieceType capturedType) {
        for (PieceType promotionPiece : PROMOTION_PIECES) {
            moves.add(createMoveInt(fromIndex, toIndex, PieceType.PAWN, whitesTurn, isCapture, false, false, promotionPiece, capturedType, false, false, lastMoveDoubleStepPawnIndex));
        }
    }


    private void generateKnightMoves(boolean whitesTurn, MoveList moves, PinState pinState) {
        long knights = whitesTurn ? whiteKnights : blackKnights;
        long opponentPieces = whitesTurn ? blackPieces : whitePieces;
        long ownPieces = whitesTurn ? whitePieces : blackPieces;

        while (knights != 0) {
            int knightIndex = Long.numberOfTrailingZeros(knights);
            long potentialMoves = knightMoveTable[knightIndex] & ~ownPieces; // Pre-filter moves that land on own pieces

            while (potentialMoves != 0) {
                int targetIndex = Long.numberOfTrailingZeros(potentialMoves);
                if (!isMoveAllowedByPin(pinState, knightIndex, targetIndex)) {
                    potentialMoves &= potentialMoves - 1;
                    continue;
                }
                boolean isCapture = (opponentPieces & (1L << targetIndex)) != 0;

                PieceType capturedPieceType = isCapture ? getPieceTypeAtIndex(targetIndex) : null;
                if (capturedPieceType != PieceType.KING) {
                    moves.add(createMoveInt(knightIndex, targetIndex, PieceType.KNIGHT, whitesTurn, isCapture, false, false, null, capturedPieceType, false, false, lastMoveDoubleStepPawnIndex));
                }

                potentialMoves &= potentialMoves - 1; // Clear the lowest set bit
            }

            knights &= knights - 1; // Clear the lowest set bit
        }
    }


    private void generateBishopMoves(boolean isWhite, MoveList moves, PinState pinState) {
        long bishops = isWhite ? whiteBishops : blackBishops;
        long ownPieces = isWhite ? whitePieces : blackPieces;
        long opponentPieces = isWhite ? blackPieces : whitePieces;

        while (bishops != 0) {
            int bishopSquare = Long.numberOfTrailingZeros(bishops);
            bishops &= bishops - 1; // Remove the least significant bit representing a bishop

            long occupancy = allPieces & bishopHelper.bishopMasks[bishopSquare];
            long attacks = bishopHelper.calculateMovesUsingBishopMagic(bishopSquare, occupancy) & ~ownPieces;

            PinRayInfo pinRayInfo = null;
            if (pinState != null && pinState.isWhiteSide() == isWhite
                    && (pinState.getAllPinned() & (1L << bishopSquare)) != 0) {
                pinRayInfo = resolvePinRayInfo(pinState, bishopSquare);
                long allowedMask = pinRayInfo.getRayMask() & ~(1L << pinState.getKingSquare());
                attacks &= allowedMask;
            }

            while (attacks != 0) {
                int targetSquare = Long.numberOfTrailingZeros(attacks);
                attacks &= attacks - 1; // Remove the least significant bit representing an attack


                boolean isCapture = (opponentPieces & (1L << targetSquare)) != 0;
                PieceType capturedType = isCapture ? getPieceTypeAtIndex(targetSquare) : null;
                if (capturedType != PieceType.KING) {
                    if (pinRayInfo != null && isCapture && pinRayInfo.getPinnerSquare() != targetSquare) {
                        continue;
                    }
                    moves.add(createMoveInt(bishopSquare, targetSquare, PieceType.BISHOP, isWhite, isCapture, false, false, null, capturedType, false, false, lastMoveDoubleStepPawnIndex));
                }
            }
        }
    }

    private void generateRookMoves(boolean whitesTurn, MoveList moves, PinState pinState) {
        long rooks = whitesTurn ? whiteRooks : blackRooks;
        long ownPieces = whitesTurn ? whitePieces : blackPieces;
        long opponentPieces = whitesTurn ? blackPieces : whitePieces;

        while (rooks != 0) {
            int rookSquare = Long.numberOfTrailingZeros(rooks);
            rooks &= rooks - 1; // Remove the least significant bit representing a rook

            // Use RookHelper to calculate rook moves using magic bitboards
            long occupancy = allPieces & rookHelper.rookMasks[rookSquare];
            long attacks = rookHelper.calculateMovesUsingRookMagic(rookSquare, occupancy) & ~ownPieces;

            PinRayInfo pinRayInfo = null;
            if (pinState != null && pinState.isWhiteSide() == whitesTurn
                    && (pinState.getAllPinned() & (1L << rookSquare)) != 0) {
                pinRayInfo = resolvePinRayInfo(pinState, rookSquare);
                long allowedMask = pinRayInfo.getRayMask() & ~(1L << pinState.getKingSquare());
                attacks &= allowedMask;
            }

            while (attacks != 0) {
                int targetSquare = Long.numberOfTrailingZeros(attacks);
                attacks &= attacks - 1; // Remove the least significant bit representing an attack
                boolean isFirstRookMove = !hasRookMoved(rookSquare);
                boolean isCapture = (opponentPieces & (1L << targetSquare)) != 0;
                PieceType capturedType = isCapture ? getPieceTypeAtIndex(targetSquare) : null;
                if (capturedType != PieceType.KING) {
                    if (pinRayInfo != null && isCapture && pinRayInfo.getPinnerSquare() != targetSquare) {
                        continue;
                    }
                    moves.add(createMoveInt(rookSquare, targetSquare, PieceType.ROOK, whitesTurn, isCapture, false, false, null, capturedType, false, isFirstRookMove, lastMoveDoubleStepPawnIndex));
                }
            }
        }
    }

    private void generateQueenMoves(boolean whitesTurn, MoveList moves, PinState pinState) {
        long queens = whitesTurn ? whiteQueens : blackQueens;
        long ownPieces = whitesTurn ? whitePieces : blackPieces;
        long opponentPieces = whitesTurn ? blackPieces : whitePieces;

        while (queens != 0) {
            int queenSquare = Long.numberOfTrailingZeros(queens);
            queens &= queens - 1;
            long occupancyBishop = allPieces & bishopHelper.bishopMasks[queenSquare];
            long occupancyRook = allPieces & rookHelper.rookMasks[queenSquare];

            long attacks = (
                    bishopHelper.calculateMovesUsingBishopMagic(queenSquare, occupancyBishop) |
                            rookHelper.calculateMovesUsingRookMagic(queenSquare, occupancyRook)
            ) & ~ownPieces;

            PinRayInfo pinRayInfo = null;
            if (pinState != null && pinState.isWhiteSide() == whitesTurn
                    && (pinState.getAllPinned() & (1L << queenSquare)) != 0) {
                pinRayInfo = resolvePinRayInfo(pinState, queenSquare);
                long allowedMask = pinRayInfo.getRayMask() & ~(1L << pinState.getKingSquare());
                attacks &= allowedMask;
            }

            while (attacks != 0) {
                int targetSquare = Long.numberOfTrailingZeros(attacks);
                attacks &= attacks - 1; // Remove the least significant bit representing an attack
                boolean isCapture = (opponentPieces & (1L << targetSquare)) != 0;
                PieceType capturedType = isCapture ? getPieceTypeAtIndex(targetSquare) : null;
                if (capturedType != PieceType.KING) {
                    if (pinRayInfo != null && isCapture && pinRayInfo.getPinnerSquare() != targetSquare) {
                        continue;
                    }
                    moves.add(createMoveInt(queenSquare, targetSquare, PieceType.QUEEN, whitesTurn, isCapture, false, false, null, capturedType, false, false, lastMoveDoubleStepPawnIndex));
                }
            }
        }
    }

    private void generateKingMoves(boolean whitesTurn, MoveList moves, PinState pinState) {
        long kingBitboard = whitesTurn ? whiteKing : blackKing;
        if (kingBitboard == 0L) {
            return; // No king present for the given color; cannot generate moves
        }
        int kingPositionIndex = Long.numberOfTrailingZeros(kingBitboard);
        long kingAttacks = KING_ATTACKS[kingPositionIndex];
        boolean isFirstKingMove = hasKingNotMoved(whitesTurn);

        long ownPieces = whitesTurn ? whitePieces : blackPieces;
        long opponentPieces = whitesTurn ? blackPieces : whitePieces;
        long legalMoves = kingAttacks & ~ownPieces;
        long captureMoves = kingAttacks & opponentPieces;

        for (long possibleMoves = legalMoves; possibleMoves != 0; possibleMoves &= possibleMoves - 1) {
            int targetIndex = Long.numberOfTrailingZeros(possibleMoves);
            boolean isCapture = (captureMoves & (1L << targetIndex)) != 0;
            PieceType capturedType = isCapture ? getPieceTypeAtIndex(targetIndex) : null;
            if (capturedType != PieceType.KING) {
                moves.add(createMoveInt(kingPositionIndex, targetIndex, PieceType.KING, whitesTurn, isCapture, false, false, null, capturedType, isFirstKingMove, false, lastMoveDoubleStepPawnIndex));
            }
        }

        addCastlingMoves(whitesTurn, kingPositionIndex, moves, pinState);
    }

    private void addCastlingMoves(boolean whitesTurn, int kingPositionIndex, MoveList moves, PinState pinState) {
        // Avoid an extra bit scan: we already know the king's square.
        if (canKingCastle(whitesTurn, kingPositionIndex, pinState)) {
            if (canCastleKingside(whitesTurn, kingPositionIndex, pinState)) {
                moves.add(createMoveInt(kingPositionIndex, kingPositionIndex + 2, PieceType.KING,
                        whitesTurn, false, true, false, null, null, true, true, lastMoveDoubleStepPawnIndex));
            }
            if (canCastleQueenside(whitesTurn, kingPositionIndex, pinState)) {
                moves.add(createMoveInt(kingPositionIndex, kingPositionIndex - 2, PieceType.KING,
                        whitesTurn, false, true, false, null, null, true, true, lastMoveDoubleStepPawnIndex));
            }
        }
    }

    private boolean canKingCastle(boolean whitesTurn, int kingIndex, PinState pinState) {
        // King must not have moved and must not be in check now.
        // isSquareUnderAttack() will lazily recompute opponent attack map if dirty.
        return hasKingNotMoved(whitesTurn) && !isSquareUnderAttack(kingIndex, whitesTurn);
    }

    private boolean canCastleKingside(boolean colorWhite, int kingPositionIndex, PinState pinState) {
        // Ensure the squares between the king and the rook are unoccupied and not under attack
        int[] kingsideSquares = {kingPositionIndex + 1, kingPositionIndex + 2};
        for (int square : kingsideSquares) {
            if (isOccupied(square) || isSquareUnderAttack(square, colorWhite)) {
                return false;
            }
        }

        int rookIndex = colorWhite ? 7 : 63;

        // Check if the rook has moved
        if (hasRookMoved(rookIndex)) {
            return false;
        }

        if (pinState != null && pinState.isWhiteSide() == colorWhite
                && (pinState.getAllPinned() & (1L << rookIndex)) != 0) {
            return false;
        }

        // Check if the rook still exists

        return isRookAtIndex(rookIndex);
    }

    private boolean canCastleQueenside(boolean colorWhite, int kingPositionIndex, PinState pinState) {
        // Ensure the squares between the king and the rook are unoccupied and not under attack
        int[] queensideSquares = {kingPositionIndex - 1, kingPositionIndex - 2, kingPositionIndex - 3};
        for (int square : queensideSquares) {
            if (isOccupied(square) || (square != kingPositionIndex - 3 && isSquareUnderAttack(square, colorWhite))) {
                return false;
            }
        }

        int rookIndex = colorWhite ? 0 : 56;

        if (hasRookMoved(rookIndex)) {
            return false;
        }
        if (pinState != null && pinState.isWhiteSide() == colorWhite
                && (pinState.getAllPinned() & (1L << rookIndex)) != 0) {
            return false;
        }
        return isRookAtIndex(rookIndex);
    }

    private boolean isRookAtIndex(int index) {
        PieceType pieceAtPosition = getPieceTypeAtIndex(index);
        return pieceAtPosition == PieceType.ROOK;
    }

    // Replace your current isSquareUnderAttack with this version
    private boolean isSquareUnderAttack(int index, boolean colorWhite) {
        long[] whiteByType = {0L, whitePawns, whiteKnights, whiteBishops, whiteRooks, whiteQueens, whiteKing};
        long[] blackByType = {0L, blackPawns, blackKnights, blackBishops, blackRooks, blackQueens, blackKing};
        return isSquareUnderAttack(index, colorWhite, allPieces, whiteByType, blackByType);
    }

    private boolean isSquareUnderAttack(int index, boolean colorWhite, long occ,
                                        long[] whiteByType, long[] blackByType) {
        if (colorWhite) {
            if (pawnAttackersToSquare(index, false, whiteByType[1], blackByType[1]) != 0) return true;
            if ((KnightHelper.knightMoveTable[index] & blackByType[2]) != 0) return true;
            if ((KING_ATTACKS[index] & blackByType[6]) != 0) return true;
            long bishopRays = bishopAttacksFromWithOcc(index, occ);
            if ((bishopRays & (blackByType[3] | blackByType[5])) != 0) return true;
            long rookRays = rookAttacksFromWithOcc(index, occ);
            return (rookRays & (blackByType[4] | blackByType[5])) != 0;
        } else {
            if (pawnAttackersToSquare(index, true, whiteByType[1], blackByType[1]) != 0) return true;
            if ((KnightHelper.knightMoveTable[index] & whiteByType[2]) != 0) return true;
            if ((KING_ATTACKS[index] & whiteByType[6]) != 0) return true;
            long bishopRays = bishopAttacksFromWithOcc(index, occ);
            if ((bishopRays & (whiteByType[3] | whiteByType[5])) != 0) return true;
            long rookRays = rookAttacksFromWithOcc(index, occ);
            return (rookRays & (whiteByType[4] | whiteByType[5])) != 0;
        }
    }

    public boolean isMoveLegalFast(int move, PinState pinState) {
        Object scratchGuard = this.moveLegalityScratchGuard;
        if (scratchGuard != null && !Thread.holdsLock(scratchGuard)) {
            throw new IllegalStateException("isMoveLegalFast must be called while holding the registered board lock");
        }

        boolean whiteMove = MoveHelper.isWhitesMove(move);
        if (pinState == null || pinState.isWhiteSide() != whiteMove) {
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
            if ((pinState.getStraightPinned() & fromMask) != 0) {
                if (!areAlignedStraight(pinState.getKingSquare(), to)
                        || !isOnSameRay(pinState.getKingSquare(), from, to, false)) {
                    return false;
                }
            } else if ((pinState.getDiagonalPinned() & fromMask) != 0) {
                if (!areAlignedDiagonal(pinState.getKingSquare(), to)
                        || !isOnSameRay(pinState.getKingSquare(), from, to, true)) {
                    return false;
                }
            }
        }

        long[] whiteByType = moveLegalityScratch[0];
        long[] blackByType = moveLegalityScratch[1];
        Arrays.fill(whiteByType, 0L);
        Arrays.fill(blackByType, 0L);
        whiteByType[1] = whitePawns;
        whiteByType[2] = whiteKnights;
        whiteByType[3] = whiteBishops;
        whiteByType[4] = whiteRooks;
        whiteByType[5] = whiteQueens;
        whiteByType[6] = whiteKing;
        blackByType[1] = blackPawns;
        blackByType[2] = blackKnights;
        blackByType[3] = blackBishops;
        blackByType[4] = blackRooks;
        blackByType[5] = blackQueens;
        blackByType[6] = blackKing;
        long occ = allPieces;

        if (isCapture) {
            int capturedBits = MoveHelper.deriveCapturedPieceTypeBits(move);
            int captureSquare = isEnPassant ? (whiteMove ? to - 8 : to + 8) : to;
            long captureMask = 1L << captureSquare;
            if (whiteMove) {
                if (capturedBits >= 1 && capturedBits <= 6) {
                    blackByType[capturedBits] &= ~captureMask;
                }
            } else {
                if (capturedBits >= 1 && capturedBits <= 6) {
                    whiteByType[capturedBits] &= ~captureMask;
                }
            }
            occ &= ~captureMask;
        }

        if (whiteMove) {
            whiteByType[pieceBits] &= ~fromMask;
        } else {
            blackByType[pieceBits] &= ~fromMask;
        }
        occ &= ~fromMask;

        int placedBits = promotionBits != 0 ? promotionBits : pieceBits;
        if (whiteMove) {
            whiteByType[placedBits] |= toMask;
        } else {
            blackByType[placedBits] |= toMask;
        }
        occ |= toMask;

        if (isCastling) {
            int rookFrom = (to > from) ? to + 1 : to - 2;
            int rookTo = (to > from) ? to - 1 : to + 1;
            long rookFromMask = 1L << rookFrom;
            long rookToMask = 1L << rookTo;
            if (whiteMove) {
                whiteByType[4] &= ~rookFromMask;
                whiteByType[4] |= rookToMask;
            } else {
                blackByType[4] &= ~rookFromMask;
                blackByType[4] |= rookToMask;
            }
            occ &= ~rookFromMask;
            occ |= rookToMask;
        }

        int kingSquare = pinState.getKingSquare();
        if (pieceBits == 6) {
            kingSquare = to;
        }

        return !isSquareUnderAttack(kingSquare, whiteMove, occ, whiteByType, blackByType);
    }


    // Replace your current performMove with this version
    public void performMove(int move) {
        halfmoveHistory.push(halfmoveClock);
        fullmoveHistory.push(fullmoveNumber);

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
            if (capType == null) {
                throw new IllegalStateException("No captured piece present at index " + capIndex);
            }
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
        boolean isKingFirstMove = MoveHelper.isKingFirstMove(move);
        boolean isRookFirstMove = MoveHelper.isRookFirstMove(move);
        int doubleStepPawnIndex = MoveHelper.deriveLastMoveDoubleStepPawnIndex(move);

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
        undoGameState(fromIndex, toIndex, pieceTypeBits, isKingFirstMove, isRookFirstMove, isWhite, doubleStepPawnIndex);

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
            halfmoveClock = halfmoveHistory.pop();
        } else {
            halfmoveClock = 0;
        }

        if (!fullmoveHistory.isEmpty()) {
            fullmoveNumber = fullmoveHistory.pop();
        } else {
            fullmoveNumber = 1;
        }

        flipSideToMove();
    }

    private void undoGameState(int fromIndex, int toIndex, int pieceTypeBits, boolean isKingFirstMove, boolean isRookFirstMove, boolean isWhite, int doubleStepPawnIndex) {

        lastMoveDoubleStepPawnIndex = doubleStepPawnIndex;

        if (pieceTypeBits == 6 && isKingFirstMove) {
            if (isWhite) {
                whiteKingMoved = false;
                if (isRookFirstMove) {
                    if (toIndex == 6) { // 'g1'
                        whiteRookH1Moved = false;
                    } else if (toIndex == 2) { // 'c1'
                        whiteRookA1Moved = false;
                    }
                }
            } else {
                blackKingMoved = false;
                if (isRookFirstMove) {
                    if (toIndex == 62) { // 'g8'
                        blackRookH8Moved = false;
                    } else if (toIndex == 58) { // 'c8'
                        blackRookA8Moved = false;
                    }
                }
            }
        }

        if (pieceTypeBits == 4 && isRookFirstMove) {
            if (fromIndex == 0) { // 'a1'
                whiteRookA1Moved = false;
            } else if (fromIndex == 7) { // 'h1'
                whiteRookH1Moved = false;
            } else if (fromIndex == 56) { // 'a8'
                blackRookA8Moved = false;
            } else if (fromIndex == 63) { // 'h8'
                blackRookH8Moved = false;
            }
        }
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
        // move bit back
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
            pieceBoard[toIndex] = null; // if capture, undoCapture already restored captured piece there
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
                halfmoveClock == bitBoard.halfmoveClock &&
                fullmoveNumber == bitBoard.fullmoveNumber;
    }

    @Override
    public int hashCode() {
        return Objects.hash(whitePawns, blackPawns, whiteKnights, blackKnights, whiteBishops, blackBishops,
                whiteRooks, blackRooks, whiteQueens, blackQueens, whiteKing, blackKing,
                whitePieces, blackPieces, allPieces, whiteKingMoved, blackKingMoved,
                whiteRookA1Moved, whiteRookH1Moved, blackRookA8Moved, blackRookH8Moved,
                lastMoveDoubleStepPawnIndex, halfmoveClock, fullmoveNumber);
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


    private PieceType getPieceTypeAtSquare(int square) {

        return getPieceTypeAtIndex(square);
    }

    private Color getPieceColorAtSquare(int square) {
        return getPieceColorAtIndex(square);
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

