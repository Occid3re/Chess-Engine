package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.board.ImmutableBoardView;
import julius.game.chessengine.figures.PieceType;
import julius.game.chessengine.helper.PawnHelper;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static julius.game.chessengine.helper.BitHelper.FileMasks;
import static julius.game.chessengine.helper.BitHelper.RankMasks;
import static julius.game.chessengine.helper.PawnMoveTables.PAWN_ATTACKS;
import static julius.game.chessengine.helper.PawnMoveTables.PAWN_PUSHES;

/**
 * Incrementally evaluates pawn structure terms and exposes tapered (midgame/endgame)
 * components so the evaluation pipeline and {@link julius.game.chessengine.utils.Score}
 * can blend them consistently.
 */
public final class PawnStructureModule implements EvaluationModule, MaterialModule.PawnChangeListener {

    public static final int CENTER_PAWN_BONUS = 15;
    public static final int PASSED_PAWN_BONUS = 60;
    public static final int CONNECTED_PAWN_BONUS = 8;
    public static final int PAWN_ISLAND_PENALTY = -5;
    public static final int DOUBLED_PAWN_PENALTY = -12;
    public static final int ISOLATED_PAWN_PENALTY = -10;
    public static final int ADVANCED_PAWN_BONUS = 8;
    public static final int BLOCKED_PAWN_PENALTY = -10;
    public static final int BACKWARD_PAWN_PENALTY = -12;
    public static final int OWN_KING_BLOCKS_PASSED_PAWN_PENALTY = -150;
    public static final int PASSED_PAWN_FREE_PATH_BONUS_PER_RANK = 12;
    public static final int ROOK_HALF_OPEN_FILE_BONUS = 15;
    public static final int ROOK_OPEN_FILE_BONUS = 25;

    private static final int WHITE = 0;
    private static final int BLACK = 1;
    private static final int PAWN = MoveHelper.pieceTypeToInt(PieceType.PAWN);

    private static final ConcurrentMap<PawnStructureKey, CachedStructure> STRUCTURE_CACHE = new ConcurrentHashMap<>();

    private final FileState[] fileStates = new FileState[8];
    private final boolean[] dirtyFiles = new boolean[8];

    private PawnStructureView currentView = PawnStructureView.empty();
    private int midgameScoreCache;
    private int endgameScoreCache;
    private boolean dirty = true;
    private boolean metadataDirty = true;

    public PawnStructureModule() {
        for (int file = 0; file < fileStates.length; file++) {
            fileStates[file] = new FileState(file);
            dirtyFiles[file] = true;
        }
    }

    @Override
    public void initialize(EvaluationContext context) {
        markAllFilesDirty();
        evaluateContext(Objects.requireNonNull(context, "context"));
    }

    @Override
    public void evaluate(EvaluationContext context) {
        evaluateContext(Objects.requireNonNull(context, "context"));
    }

    private PawnStructureView evaluateContext(EvaluationContext context) {
        if (context == null) {
            currentView = PawnStructureView.empty();
            midgameScoreCache = 0;
            endgameScoreCache = 0;
            dirty = false;
            metadataDirty = false;
            return currentView;
        }
        ImmutableBoardView board = context.getBoardView();
        if (board == null) {
            currentView = PawnStructureView.empty();
            midgameScoreCache = 0;
            endgameScoreCache = 0;
            dirty = false;
            metadataDirty = false;
            return currentView;
        }
        refreshFileMetadata(board);
        if (!dirty) {
            return currentView;
        }

        long whitePawns = board.getWhitePawns();
        long blackPawns = board.getBlackPawns();
        long allPieces = board.getAllPieces();
        long whiteRooks = board.getWhiteRooks();
        long blackRooks = board.getBlackRooks();
        long whiteAttacks = context.getWhiteAttackMap();
        long blackAttacks = context.getBlackAttackMap();
        long whiteKing = board.getWhiteKing();
        long blackKing = board.getBlackKing();

        CachedStructure structure = getStructure(whitePawns, blackPawns);

        PhaseScore whiteCenter = PhaseScore.constant(structure.whiteCenterPawnBonus);
        PhaseScore blackCenter = PhaseScore.constant(structure.blackCenterPawnBonus);
        PhaseScore whiteDoubled = PhaseScore.constant(structure.whiteDoubledPawnPenalty);
        PhaseScore blackDoubled = PhaseScore.constant(structure.blackDoubledPawnPenalty);
        PhaseScore whiteIsolated = PhaseScore.constant(structure.whiteIsolatedPawnPenalty);
        PhaseScore blackIsolated = PhaseScore.constant(structure.blackIsolatedPawnPenalty);
        PhaseScore whiteConnected = PhaseScore.constant(structure.whiteConnectedPawnBonus);
        PhaseScore blackConnected = PhaseScore.constant(structure.blackConnectedPawnBonus);
        PhaseScore whiteIslands = PhaseScore.constant(structure.whitePawnIslandPenalty);
        PhaseScore blackIslands = PhaseScore.constant(structure.blackPawnIslandPenalty);

        PhaseScore whiteRookHalfOpen = PhaseScore.constant(
                countHalfOpenFilesWithRooks(board, whiteRooks, true) * ROOK_HALF_OPEN_FILE_BONUS);
        PhaseScore blackRookHalfOpen = PhaseScore.constant(
                countHalfOpenFilesWithRooks(board, blackRooks, false) * ROOK_HALF_OPEN_FILE_BONUS);

        PhaseScore whiteRookOpen = PhaseScore.constant(
                countOpenFilesWithRooks(board, whiteRooks) * ROOK_OPEN_FILE_BONUS);
        PhaseScore blackRookOpen = PhaseScore.constant(
                countOpenFilesWithRooks(board, blackRooks) * ROOK_OPEN_FILE_BONUS);

        PhaseScore whitePassed = PhaseScore.constant(
                calculatePassedPawnBonus(whitePawns, blackPawns, allPieces, whiteKing, true));
        PhaseScore blackPassed = PhaseScore.constant(
                calculatePassedPawnBonus(blackPawns, whitePawns, allPieces, blackKing, false));

        PhaseScore whiteAdvance = PhaseScore.of(
                calculatePawnAdvanceBonus(whitePawns, allPieces, blackAttacks, true, 0),
                calculatePawnAdvanceBonus(whitePawns, allPieces, blackAttacks, true, 256));
        PhaseScore blackAdvance = PhaseScore.of(
                calculatePawnAdvanceBonus(blackPawns, allPieces, whiteAttacks, false, 0),
                calculatePawnAdvanceBonus(blackPawns, allPieces, whiteAttacks, false, 256));

        PhaseScore whiteBlocked = PhaseScore.of(
                calculateBlockedPawnPenalty(whitePawns, allPieces, true, 0),
                calculateBlockedPawnPenalty(whitePawns, allPieces, true, 256));
        PhaseScore blackBlocked = PhaseScore.of(
                calculateBlockedPawnPenalty(blackPawns, allPieces, false, 0),
                calculateBlockedPawnPenalty(blackPawns, allPieces, false, 256));

        PhaseScore whiteBackward = PhaseScore.of(
                calculateBackwardPawnPenalty(whitePawns, blackPawns, allPieces, true, 0),
                calculateBackwardPawnPenalty(whitePawns, blackPawns, allPieces, true, 256));
        PhaseScore blackBackward = PhaseScore.of(
                calculateBackwardPawnPenalty(blackPawns, whitePawns, allPieces, false, 0),
                calculateBackwardPawnPenalty(blackPawns, whitePawns, allPieces, false, 256));

        PhaseScore[] whiteComponents = new PhaseScore[]{
                whiteCenter,
                whiteDoubled,
                whiteIsolated,
                whiteConnected,
                whiteRookHalfOpen,
                whiteRookOpen,
                whiteIslands,
                whitePassed,
                whiteAdvance,
                whiteBlocked,
                whiteBackward
        };
        PhaseScore[] blackComponents = new PhaseScore[]{
                blackCenter,
                blackDoubled,
                blackIsolated,
                blackConnected,
                blackRookHalfOpen,
                blackRookOpen,
                blackIslands,
                blackPassed,
                blackAdvance,
                blackBlocked,
                blackBackward
        };

        int whiteMidgameTotal = sumMidgame(whiteComponents);
        int whiteEndgameTotal = sumEndgame(whiteComponents);
        int blackMidgameTotal = sumMidgame(blackComponents);
        int blackEndgameTotal = sumEndgame(blackComponents);

        currentView = new PawnStructureView(
                whiteCenter,
                blackCenter,
                whiteDoubled,
                blackDoubled,
                whiteIsolated,
                blackIsolated,
                whiteConnected,
                blackConnected,
                whiteRookHalfOpen,
                blackRookHalfOpen,
                whiteRookOpen,
                blackRookOpen,
                whiteIslands,
                blackIslands,
                whitePassed,
                blackPassed,
                whiteAdvance,
                blackAdvance,
                whiteBlocked,
                blackBlocked,
                whiteBackward,
                blackBackward,
                whiteMidgameTotal,
                whiteEndgameTotal,
                blackMidgameTotal,
                blackEndgameTotal
        );

        midgameScoreCache = whiteMidgameTotal - blackMidgameTotal;
        endgameScoreCache = whiteEndgameTotal - blackEndgameTotal;
        dirty = false;
        return currentView;
    }

    @Override
    public void applyMove(MoveContext moveContext) {
        handleMove(moveContext.getMove());
    }

    @Override
    public void undoMove(MoveContext moveContext) {
        handleMove(moveContext.getMove());
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
        metadataDirty = true;
        markAllFilesDirty();
    }

    @Override
    public void onPawnAdded(boolean isWhite, int squareIndex) {
        markFileDirty(squareIndex & 7);
        dirty = true;
    }

    @Override
    public void onPawnRemoved(boolean isWhite, int squareIndex) {
        markFileDirty(squareIndex & 7);
        dirty = true;
    }

    public PawnStructureView getView(BitBoard board) {
        if (board == null) {
            return evaluateContext(null);
        }
        EvaluationContext context = EvaluationContext.from(board, null);
        return evaluateContext(context);
    }

    public int countHalfOpenFilesWithRooks(BitBoard board, long rooksBitboard, boolean isWhite) {
        if (board == null) {
            return 0;
        }
        return countHalfOpenFilesWithRooks(ImmutableBoardView.from(board), rooksBitboard, isWhite);
    }

    private int countHalfOpenFilesWithRooks(ImmutableBoardView board, long rooksBitboard, boolean isWhite) {
        if (rooksBitboard == 0L) {
            return 0;
        }
        refreshFileMetadata(board);
        int count = 0;
        for (int file = 0; file < 8; file++) {
            long fileMask = FileMasks[file];
            if ((rooksBitboard & fileMask) == 0) {
                continue;
            }
            if (isWhite) {
                if (fileStates[file].halfOpenForWhite) {
                    count++;
                }
            } else {
                if (fileStates[file].halfOpenForBlack) {
                    count++;
                }
            }
        }
        return count;
    }

    public int countOpenFilesWithRooks(BitBoard board, long rooksBitboard) {
        if (board == null) {
            return 0;
        }
        return countOpenFilesWithRooks(ImmutableBoardView.from(board), rooksBitboard);
    }

    private int countOpenFilesWithRooks(ImmutableBoardView board, long rooksBitboard) {
        if (rooksBitboard == 0L) {
            return 0;
        }
        refreshFileMetadata(board);
        int count = 0;
        for (int file = 0; file < 8; file++) {
            if ((rooksBitboard & FileMasks[file]) == 0) {
                continue;
            }
            if (fileStates[file].open) {
                count++;
            }
        }
        return count;
    }

    private void handleMove(int move) {
        dirty = true;
        int movedPiece = MoveHelper.derivePieceTypeBits(move);
        int capturedPiece = MoveHelper.deriveCapturedPieceTypeBits(move);
        int promotionPiece = MoveHelper.derivePromotionPieceTypeBits(move);

        if (movedPiece == PAWN) {
            markFileDirty(MoveHelper.deriveFromIndex(move) & 7);
            markFileDirty(MoveHelper.deriveToIndex(move) & 7);
            if (MoveHelper.isEnPassantMove(move)) {
                int captureIndex = enPassantCaptureIndex(move);
                markFileDirty(captureIndex & 7);
            }
        }
        if (capturedPiece == PAWN) {
            if (MoveHelper.isEnPassantMove(move)) {
                int captureIndex = enPassantCaptureIndex(move);
                markFileDirty(captureIndex & 7);
            } else {
                markFileDirty(MoveHelper.deriveToIndex(move) & 7);
            }
        }
        if (promotionPiece != 0) {
            markFileDirty(MoveHelper.deriveFromIndex(move) & 7);
        }
    }

    private void refreshFileMetadata(ImmutableBoardView board) {
        if (!metadataDirty || board == null) {
            return;
        }
        long whitePawns = board.getWhitePawns();
        long blackPawns = board.getBlackPawns();
        boolean touched = false;
        for (int file = 0; file < 8; file++) {
            if (!dirtyFiles[file]) {
                continue;
            }
            fileStates[file].refresh(whitePawns, blackPawns);
            dirtyFiles[file] = false;
            touched = true;
        }
        if (touched) {
            metadataDirty = false;
            for (boolean fileDirty : dirtyFiles) {
                if (fileDirty) {
                    metadataDirty = true;
                    break;
                }
            }
        } else {
            metadataDirty = false;
        }
    }

    private void markFileDirty(int file) {
        if (file < 0 || file >= dirtyFiles.length) {
            return;
        }
        dirtyFiles[file] = true;
        metadataDirty = true;
    }

    private void markAllFilesDirty() {
        Arrays.fill(dirtyFiles, true);
        metadataDirty = true;
    }

    private static int enPassantCaptureIndex(int move) {
        int toIndex = MoveHelper.deriveToIndex(move);
        return MoveHelper.isWhitesMove(move) ? toIndex - 8 : toIndex + 8;
    }

    private static CachedStructure getStructure(long whitePawns, long blackPawns) {
        PawnStructureKey key = new PawnStructureKey(whitePawns, blackPawns);
        return STRUCTURE_CACHE.computeIfAbsent(key, k -> new CachedStructure(whitePawns, blackPawns));
    }

    private static int calculatePawnAdvanceBonus(long pawns, long allPieces, long enemyAttacks, boolean isWhite, int phase) {
        int bonus = 0;
        long advancedRanks = isWhite
                ? (RankMasks[3] | RankMasks[4] | RankMasks[5] | RankMasks[6])
                : (RankMasks[4] | RankMasks[3] | RankMasks[2] | RankMasks[1]);
        long advancedPawns = pawns & advancedRanks;
        while (advancedPawns != 0) {
            int square = Long.numberOfTrailingZeros(advancedPawns);
            long forward = PAWN_PUSHES[isWhite ? WHITE : BLACK][square];
            if ((forward & (allPieces | enemyAttacks)) == 0) {
                int rank = square / 8 + 1;
                int rankBonus = isWhite ? (rank - 3) : (6 - rank);
                bonus += ADVANCED_PAWN_BONUS * rankBonus;
            }
            advancedPawns &= advancedPawns - 1;
        }
        return scaleByPhase(bonus, phase);
    }

    private static int calculateBlockedPawnPenalty(long pawns, long allPieces, boolean isWhite, int phase) {
        int penalty = 0;
        long remaining = pawns;
        while (remaining != 0) {
            int square = Long.numberOfTrailingZeros(remaining);
            long forward = PAWN_PUSHES[isWhite ? WHITE : BLACK][square];
            if ((forward & allPieces) != 0) {
                penalty += BLOCKED_PAWN_PENALTY;
            }
            remaining &= remaining - 1;
        }
        return scaleByPhase(penalty, phase);
    }

    private static int calculateBackwardPawnPenalty(long pawns, long enemyPawns, long allPieces, boolean isWhite, int phase) {
        int penalty = 0;
        long remaining = pawns;
        while (remaining != 0) {
            int square = Long.numberOfTrailingZeros(remaining);
            long forward = PAWN_PUSHES[isWhite ? WHITE : BLACK][square];
            if ((forward & allPieces) == 0) {
                int forwardIndex = Long.numberOfTrailingZeros(forward);
                long enemyAttack = PAWN_ATTACKS[isWhite ? WHITE : BLACK][forwardIndex] & enemyPawns;
                if (enemyAttack != 0) {
                    penalty += BACKWARD_PAWN_PENALTY;
                }
            }
            remaining &= remaining - 1;
        }
        return scaleByPhase(penalty, phase);
    }

    private static int sumMidgame(PhaseScore... scores) {
        int total = 0;
        if (scores == null) {
            return total;
        }
        for (PhaseScore score : scores) {
            if (score != null) {
                total += score.midgame();
            }
        }
        return total;
    }

    private static int sumEndgame(PhaseScore... scores) {
        int total = 0;
        if (scores == null) {
            return total;
        }
        for (PhaseScore score : scores) {
            if (score != null) {
                total += score.endgame();
            }
        }
        return total;
    }

    private static int calculatePassedPawnBonus(long pawns, long opponentPawns, long allPieces, long ownKing, boolean isWhite) {
        int bonus = 0;
        long remaining = pawns;

        while (remaining != 0) {
            long pawn = remaining & -remaining;
            remaining ^= pawn;

            int square = Long.numberOfTrailingZeros(pawn);
            int file = square % 8;
            int rank = square / 8 + 1;

            long fileMask = FileMasks[file];
            long adjFilesMask = fileMask;
            if (file > 0) {
                adjFilesMask |= FileMasks[file - 1];
            }
            if (file < 7) {
                adjFilesMask |= FileMasks[file + 1];
            }

            long forwardRanksMask = 0L;
            if (isWhite) {
                for (int r = rank + 1; r <= 8; r++) {
                    forwardRanksMask |= RankMasks[r - 1];
                }
            } else {
                for (int r = rank - 1; r >= 1; r--) {
                    forwardRanksMask |= RankMasks[r - 1];
                }
            }

            boolean isPassed = (opponentPawns & (adjFilesMask & forwardRanksMask)) == 0;
            if (!isPassed) {
                continue;
            }

            int base = PASSED_PAWN_BONUS * (isWhite ? (rank - 1) : (8 - rank));

            long filePathAhead = fileMask & forwardRanksMask;
            long oneStep = isWhite ? (pawn << 8) : (pawn >>> 8);

            if ((oneStep & ownKing) != 0L) {
                base += OWN_KING_BLOCKS_PASSED_PAWN_PENALTY;
            }

            if ((filePathAhead & allPieces) == 0L) {
                int distToPromo = isWhite ? (8 - rank) : (rank - 1);
                base += PASSED_PAWN_FREE_PATH_BONUS_PER_RANK * distToPromo;
            }

            bonus += base;
        }

        return bonus;
    }

    private static int scaleByPhase(int value, int phase) {
        int clamped = Math.max(0, Math.min(256, phase));
        return value * (256 + clamped) / 256;
    }

    private record PawnStructureKey(long white, long black) { }

    private static final class CachedStructure {
        private final int whiteCenterPawnBonus;
        private final int blackCenterPawnBonus;
        private final int whiteDoubledPawnPenalty;
        private final int blackDoubledPawnPenalty;
        private final int whiteIsolatedPawnPenalty;
        private final int blackIsolatedPawnPenalty;
        private final int whiteConnectedPawnBonus;
        private final int blackConnectedPawnBonus;
        private final int whitePawnIslandPenalty;
        private final int blackPawnIslandPenalty;

        private CachedStructure(long whitePawns, long blackPawns) {
            this.whiteCenterPawnBonus = PawnHelper.countCenterPawns(whitePawns) * CENTER_PAWN_BONUS;
            this.blackCenterPawnBonus = PawnHelper.countCenterPawns(blackPawns) * CENTER_PAWN_BONUS;
            this.whiteDoubledPawnPenalty = PawnHelper.countDoubledPawns(whitePawns) * DOUBLED_PAWN_PENALTY;
            this.blackDoubledPawnPenalty = PawnHelper.countDoubledPawns(blackPawns) * DOUBLED_PAWN_PENALTY;
            this.whiteIsolatedPawnPenalty = PawnHelper.countIsolatedPawns(whitePawns) * ISOLATED_PAWN_PENALTY;
            this.blackIsolatedPawnPenalty = PawnHelper.countIsolatedPawns(blackPawns) * ISOLATED_PAWN_PENALTY;
            this.whiteConnectedPawnBonus = PawnHelper.countConnectedPawns(whitePawns) * CONNECTED_PAWN_BONUS;
            this.blackConnectedPawnBonus = PawnHelper.countConnectedPawns(blackPawns) * CONNECTED_PAWN_BONUS;
            this.whitePawnIslandPenalty = Math.max(0, PawnHelper.countPawnIslands(whitePawns) - 1) * PAWN_ISLAND_PENALTY;
            this.blackPawnIslandPenalty = Math.max(0, PawnHelper.countPawnIslands(blackPawns) - 1) * PAWN_ISLAND_PENALTY;
        }
    }

    public static final class PhaseScore {
        private final int midgame;
        private final int endgame;

        private PhaseScore(int midgame, int endgame) {
            this.midgame = midgame;
            this.endgame = endgame;
        }

        public static PhaseScore of(int midgame, int endgame) {
            return new PhaseScore(midgame, endgame);
        }

        public static PhaseScore constant(int value) {
            return new PhaseScore(value, value);
        }

        public int blend(int phase) {
            int clamped = Math.max(0, Math.min(256, phase));
            int midWeight = 256 - clamped;
            int endWeight = clamped;
            long blended = (long) midgame * midWeight + (long) endgame * endWeight;
            return (int) (blended / 256);
        }

        public int midgame() {
            return midgame;
        }

        public int endgame() {
            return endgame;
        }
    }

    public static final class PawnStructureView {
        private final PhaseScore whiteCenter;
        private final PhaseScore blackCenter;
        private final PhaseScore whiteDoubled;
        private final PhaseScore blackDoubled;
        private final PhaseScore whiteIsolated;
        private final PhaseScore blackIsolated;
        private final PhaseScore whiteConnected;
        private final PhaseScore blackConnected;
        private final PhaseScore whiteRookHalfOpen;
        private final PhaseScore blackRookHalfOpen;
        private final PhaseScore whiteRookOpen;
        private final PhaseScore blackRookOpen;
        private final PhaseScore whiteIslands;
        private final PhaseScore blackIslands;
        private final PhaseScore whitePassed;
        private final PhaseScore blackPassed;
        private final PhaseScore whiteAdvance;
        private final PhaseScore blackAdvance;
        private final PhaseScore whiteBlocked;
        private final PhaseScore blackBlocked;
        private final PhaseScore whiteBackward;
        private final PhaseScore blackBackward;

        private final int whiteMidgameTotal;
        private final int whiteEndgameTotal;
        private final int blackMidgameTotal;
        private final int blackEndgameTotal;

        private PawnStructureView(
                PhaseScore whiteCenter,
                PhaseScore blackCenter,
                PhaseScore whiteDoubled,
                PhaseScore blackDoubled,
                PhaseScore whiteIsolated,
                PhaseScore blackIsolated,
                PhaseScore whiteConnected,
                PhaseScore blackConnected,
                PhaseScore whiteRookHalfOpen,
                PhaseScore blackRookHalfOpen,
                PhaseScore whiteRookOpen,
                PhaseScore blackRookOpen,
                PhaseScore whiteIslands,
                PhaseScore blackIslands,
                PhaseScore whitePassed,
                PhaseScore blackPassed,
                PhaseScore whiteAdvance,
                PhaseScore blackAdvance,
                PhaseScore whiteBlocked,
                PhaseScore blackBlocked,
                PhaseScore whiteBackward,
                PhaseScore blackBackward,
                int whiteMidgameTotal,
                int whiteEndgameTotal,
                int blackMidgameTotal,
                int blackEndgameTotal) {
            this.whiteCenter = whiteCenter;
            this.blackCenter = blackCenter;
            this.whiteDoubled = whiteDoubled;
            this.blackDoubled = blackDoubled;
            this.whiteIsolated = whiteIsolated;
            this.blackIsolated = blackIsolated;
            this.whiteConnected = whiteConnected;
            this.blackConnected = blackConnected;
            this.whiteRookHalfOpen = whiteRookHalfOpen;
            this.blackRookHalfOpen = blackRookHalfOpen;
            this.whiteRookOpen = whiteRookOpen;
            this.blackRookOpen = blackRookOpen;
            this.whiteIslands = whiteIslands;
            this.blackIslands = blackIslands;
            this.whitePassed = whitePassed;
            this.blackPassed = blackPassed;
            this.whiteAdvance = whiteAdvance;
            this.blackAdvance = blackAdvance;
            this.whiteBlocked = whiteBlocked;
            this.blackBlocked = blackBlocked;
            this.whiteBackward = whiteBackward;
            this.blackBackward = blackBackward;
            this.whiteMidgameTotal = whiteMidgameTotal;
            this.whiteEndgameTotal = whiteEndgameTotal;
            this.blackMidgameTotal = blackMidgameTotal;
            this.blackEndgameTotal = blackEndgameTotal;
        }

        public static PawnStructureView empty() {
            PhaseScore zero = PhaseScore.constant(0);
            return new PawnStructureView(zero, zero, zero, zero, zero, zero, zero, zero,
            zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero,
                    0, 0, 0, 0);
        }

        public PhaseScore whiteCenter() {
            return whiteCenter;
        }

        public PhaseScore blackCenter() {
            return blackCenter;
        }

        public PhaseScore whiteDoubled() {
            return whiteDoubled;
        }

        public PhaseScore blackDoubled() {
            return blackDoubled;
        }

        public PhaseScore whiteIsolated() {
            return whiteIsolated;
        }

        public PhaseScore blackIsolated() {
            return blackIsolated;
        }

        public PhaseScore whiteConnected() {
            return whiteConnected;
        }

        public PhaseScore blackConnected() {
            return blackConnected;
        }

        public PhaseScore whiteRookHalfOpen() {
            return whiteRookHalfOpen;
        }

        public PhaseScore blackRookHalfOpen() {
            return blackRookHalfOpen;
        }

        public PhaseScore whiteRookOpen() {
            return whiteRookOpen;
        }

        public PhaseScore blackRookOpen() {
            return blackRookOpen;
        }

        public PhaseScore whiteIslands() {
            return whiteIslands;
        }

        public PhaseScore blackIslands() {
            return blackIslands;
        }

        public PhaseScore whitePassed() {
            return whitePassed;
        }

        public PhaseScore blackPassed() {
            return blackPassed;
        }

        public PhaseScore whiteAdvance() {
            return whiteAdvance;
        }

        public PhaseScore blackAdvance() {
            return blackAdvance;
        }

        public PhaseScore whiteBlocked() {
            return whiteBlocked;
        }

        public PhaseScore blackBlocked() {
            return blackBlocked;
        }

        public PhaseScore whiteBackward() {
            return whiteBackward;
        }

        public PhaseScore blackBackward() {
            return blackBackward;
        }

        public int blendWhiteTotal(int phase) {
            return blendTotal(whiteMidgameTotal, whiteEndgameTotal, phase);
        }

        public int blendBlackTotal(int phase) {
            return blendTotal(blackMidgameTotal, blackEndgameTotal, phase);
        }

        private static int blendTotal(int midgame, int endgame, int phase) {
            int clamped = Math.max(0, Math.min(256, phase));
            int midWeight = 256 - clamped;
            int endWeight = clamped;
            long blended = (long) midgame * midWeight + (long) endgame * endWeight;
            return (int) (blended / 256);
        }
    }

    private static final class FileState {
        private final int file;
        private boolean open;
        private boolean halfOpenForWhite;
        private boolean halfOpenForBlack;

        private FileState(int file) {
            this.file = file;
        }

        private void refresh(long whitePawns, long blackPawns) {
            long mask = FileMasks[file];
            long whiteMask = whitePawns & mask;
            long blackMask = blackPawns & mask;
            open = (whiteMask | blackMask) == 0;
            halfOpenForWhite = whiteMask == 0 && blackMask != 0;
            halfOpenForBlack = blackMask == 0 && whiteMask != 0;
        }
    }
}
