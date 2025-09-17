package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.evaluation.ActivityModule;
import julius.game.chessengine.evaluation.EvaluationContext;
import julius.game.chessengine.evaluation.EvaluationModule;
import julius.game.chessengine.evaluation.EvaluationPipeline;
import julius.game.chessengine.evaluation.MoveContext;
import julius.game.chessengine.evaluation.KingSafetyModule;
import julius.game.chessengine.evaluation.MaterialModule;
import julius.game.chessengine.evaluation.PawnStructureModule;
import julius.game.chessengine.evaluation.PieceSquareModule;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.RookHelper;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import java.util.List;

import static julius.game.chessengine.helper.KingHelper.*;
import static julius.game.chessengine.helper.KnightHelper.knightMoveTable;
import static julius.game.chessengine.helper.PawnMoveTables.PAWN_ATTACKS;
import static julius.game.chessengine.helper.BitHelper.FileMasks;
import static julius.game.chessengine.helper.BitHelper.RankMasks;
import static julius.game.chessengine.helper.BitHelper.bitIndex;

@Data
@Log4j2
public class Score {

    public static final int CHECKMATE = 100000;
    public static final int CHECK = 0;
    public static final int DRAW = 0;

    public static final int KILLER_MOVE_SCORE = 10000;

    // --- Material (centipawns) ---
    public static final int PAWN_VALUE   = 100;
    public static final int KNIGHT_VALUE = 320;  // was 300
    public static final int BISHOP_VALUE = 330;  // ok
    public static final int ROOK_VALUE   = 500;  // ok
    public static final int QUEEN_VALUE  = 900;  // ok

    public  static final int PASSED_PAWN_BONUS     = PawnStructureModule.PASSED_PAWN_BONUS;
    public  static final int CENTER_PAWN_BONUS     = PawnStructureModule.CENTER_PAWN_BONUS;

    // Other bonuses and penalties
    private static final int NOT_CASTLED_AND_ROOK_MOVE_PENALTY = -10; // further reduced
    private static final int CASTLING_BONUS                    = 20;  // further reduced

    private static final int ROOK_HALF_OPEN_FILE_BONUS = 15; // was 25
    private static final int ROOK_OPEN_FILE_BONUS      = 25; // was 35

    // Piece-specific mobility weights
    private static final int KNIGHT_MOBILITY_BONUS = 2;
    private static final int BISHOP_MOBILITY_BONUS = 4;
    private static final int ROOK_MOBILITY_BONUS   = 2;
    private static final int QUEEN_MOBILITY_BONUS  = 1;

    // Bonus for moves that reach or control central squares (d4, e4, d5, e5)
    private static final int CENTER_CONTROL_BONUS = 3;
    private static final long CENTRAL_SQUARES =
            (1L << bitIndex('d', 4)) | (1L << bitIndex('e', 4)) |
            (1L << bitIndex('d', 5)) | (1L << bitIndex('e', 5));
    private static final int MISSING_PAWN_SHIELD_PENALTY = -15;
    private static final int HALF_OPEN_FILE_PENALTY = -15;
    private static final int OPEN_FILE_PENALTY = -25;
    private static final int PAWN_ATTACK_PENALTY = -5;
    private static final int KNIGHT_ATTACK_PENALTY = -10;
    private static final int BISHOP_ATTACK_PENALTY = -10;
    private static final int ROOK_ATTACK_PENALTY = -15;
    private static final int QUEEN_ATTACK_PENALTY = -20;
    private static final int DEFENDER_BONUS = 5;
    private static final int QUEEN_ATTACKED_PENALTY = -135;
    private static final int MINOR_PIECE_ATTACK_PENALTY = -12;
    private static final int MINOR_PIECE_PAWN_ATTACK_PENALTY = -15;
    public static final int BISHOP_PAIR_BONUS = 40;
    private static final int KNIGHT_OUTPOST_BONUS = 15;
    private static final int KNIGHT_OUTPOST_DEFENDED_BONUS = 5;

    private int whiteScore;
    private int blackScore;

    private final MaterialModule materialModule = new MaterialModule();
    private final PawnStructureModule pawnStructureModule = new PawnStructureModule();
    private final PieceSquareModule pieceSquareModule = new PieceSquareModule();
    private final ActivityModule activityModule = new ActivityModule();
    private final KingSafetyModule kingSafetyModule = new KingSafetyModule();
    private final EvaluationPipeline evaluationPipeline;
    private EvaluationContext evaluationContext;
    private boolean pipelineInitialized;

    // Mobility
    private int whiteMobilityScore = 0;
    private int blackMobilityScore = 0;

    // King safety
    private int whiteKingSafetyScore = 0;
    private int blackKingSafetyScore = 0;
    private int whiteQueenSafetyScore = 0;
    private int blackQueenSafetyScore = 0;
    private int whiteMinorPieceSafetyScore = 0;
    private int blackMinorPieceSafetyScore = 0;

    // Initialize bonuses and penalties
    private int whiteCenterPawnBonus = 0;
    private int blackCenterPawnBonus = 0;
    private int whiteDoubledPawnPenalty = 0;
    private int blackDoubledPawnPenalty = 0;
    private int whiteIsolatedPawnPenalty = 0;
    private int blackIsolatedPawnPenalty = 0;
    private int whitePassedPawnBonus = 0;
    private int blackPassedPawnBonus = 0;
    private int whitePawnAdvanceBonus = 0;
    private int blackPawnAdvanceBonus = 0;
    private int whiteBlockedPawnPenalty = 0;
    private int blackBlockedPawnPenalty = 0;
    private int whiteBackwardPawnPenalty = 0;
    private int blackBackwardPawnPenalty = 0;
    private int whiteConnectedPawnBonus = 0;
    private int blackConnectedPawnBonus = 0;
    private int whitePawnIslandPenalty = 0;
    private int blackPawnIslandPenalty = 0;
    private int whiteRooksHalfOpenFileBonus = 0;
    private int blackRooksHalfOpenFileBonus = 0;
    private int whiteRooksOpenFileBonus = 0;
    private int blackRooksOpenFileBonus = 0;
    private int whiteKnightOutpostBonus = 0;
    private int blackKnightOutpostBonus = 0;

    private static final BishopHelper BISHOP_HELPER = BishopHelper.getInstance();
    private static final RookHelper ROOK_HELPER = RookHelper.getInstance();
    // Thread-local accumulator reused to avoid per-call allocations when recomputing mobility.
    private static final ThreadLocal<int[]> MOBILITY_BUFFER = ThreadLocal.withInitial(() -> new int[2]);

    // Initialize positional values
    private int whiteKingCastlingAdjustment = 0;
    private int blackKingCastlingAdjustment = 0;

    // State bonuses
    private int whiteStateBonus = 0;
    private int blackStateBonus = 0;

    private static final long NOT_A_FILE = ~FileMasks[0];
    private static final long NOT_H_FILE = ~FileMasks[7];

    public Score() {
        materialModule.setPawnChangeListener(pawnStructureModule);
        this.evaluationPipeline = createPipeline();
        this.evaluationContext = null;
        this.pipelineInitialized = false;
        this.whiteScore = 0;
        this.blackScore = 0;
    }

    public Score(Score other) {
        materialModule.setPawnChangeListener(pawnStructureModule);
        this.evaluationPipeline = createPipeline();
        this.pipelineInitialized = false;
        this.evaluationContext = other.evaluationContext != null ? other.evaluationContext.copy() : null;
        this.whiteScore = other.whiteScore;
        this.blackScore = other.blackScore;


        this.whiteMobilityScore = other.whiteMobilityScore;
        this.blackMobilityScore = other.blackMobilityScore;
        this.whiteKingSafetyScore = other.whiteKingSafetyScore;
        this.blackKingSafetyScore = other.blackKingSafetyScore;
        this.whiteQueenSafetyScore = other.whiteQueenSafetyScore;
        this.blackQueenSafetyScore = other.blackQueenSafetyScore;
        this.whiteMinorPieceSafetyScore = other.whiteMinorPieceSafetyScore;
        this.blackMinorPieceSafetyScore = other.blackMinorPieceSafetyScore;

        this.whiteCenterPawnBonus = other.whiteCenterPawnBonus;
        this.blackCenterPawnBonus = other.blackCenterPawnBonus;
        this.whiteDoubledPawnPenalty = other.whiteDoubledPawnPenalty;
        this.blackDoubledPawnPenalty = other.blackDoubledPawnPenalty;
        this.whiteIsolatedPawnPenalty = other.whiteIsolatedPawnPenalty;
        this.blackIsolatedPawnPenalty = other.blackIsolatedPawnPenalty;
        this.whitePassedPawnBonus = other.whitePassedPawnBonus;
        this.blackPassedPawnBonus = other.blackPassedPawnBonus;
        this.whitePawnAdvanceBonus = other.whitePawnAdvanceBonus;
        this.blackPawnAdvanceBonus = other.blackPawnAdvanceBonus;
        this.whiteBlockedPawnPenalty = other.whiteBlockedPawnPenalty;
        this.blackBlockedPawnPenalty = other.blackBlockedPawnPenalty;
        this.whiteBackwardPawnPenalty = other.whiteBackwardPawnPenalty;
        this.blackBackwardPawnPenalty = other.blackBackwardPawnPenalty;
        this.whiteConnectedPawnBonus = other.whiteConnectedPawnBonus;
        this.blackConnectedPawnBonus = other.blackConnectedPawnBonus;
        this.whitePawnIslandPenalty = other.whitePawnIslandPenalty;
        this.blackPawnIslandPenalty = other.blackPawnIslandPenalty;
        this.whiteRooksHalfOpenFileBonus = other.whiteRooksHalfOpenFileBonus;
        this.blackRooksHalfOpenFileBonus = other.blackRooksHalfOpenFileBonus;
        this.whiteRooksOpenFileBonus = other.whiteRooksOpenFileBonus;
        this.blackRooksOpenFileBonus = other.blackRooksOpenFileBonus;
        this.whiteKnightOutpostBonus = other.whiteKnightOutpostBonus;
        this.blackKnightOutpostBonus = other.blackKnightOutpostBonus;

        this.whiteKingCastlingAdjustment = other.whiteKingCastlingAdjustment;
        this.blackKingCastlingAdjustment = other.blackKingCastlingAdjustment;

        this.whiteStateBonus = other.whiteStateBonus;
        this.blackStateBonus = other.blackStateBonus;

        if (other.pipelineInitialized && this.evaluationContext != null) {
            syncPipeline(this.evaluationContext);
        }
    }


    private EvaluationPipeline createPipeline() {
        EvaluationModule module = new LegacyEvaluationModule();
        return new EvaluationPipeline(List.of(
                materialModule,
                pawnStructureModule,
                pieceSquareModule,
                activityModule,
                kingSafetyModule,
                module));
    }

    private void syncPipeline(EvaluationContext context) {
        if (context == null) {
            return;
        }
        if (!pipelineInitialized) {
            evaluationPipeline.initialize(context);
            pipelineInitialized = true;
        } else {
            evaluationPipeline.updateContext(context);
        }
    }

    private void applyPawnStructure(BitBoard bitBoard) {
        PawnStructureModule.PawnStructureView view = pawnStructureModule.getView(bitBoard);
        int phase = clampPhase(bitBoard.getPhase());
        whiteCenterPawnBonus = view.whiteCenter().blend(phase);
        blackCenterPawnBonus = view.blackCenter().blend(phase);
        whiteDoubledPawnPenalty = view.whiteDoubled().blend(phase);
        blackDoubledPawnPenalty = view.blackDoubled().blend(phase);
        whiteIsolatedPawnPenalty = view.whiteIsolated().blend(phase);
        blackIsolatedPawnPenalty = view.blackIsolated().blend(phase);
        whiteConnectedPawnBonus = view.whiteConnected().blend(phase);
        blackConnectedPawnBonus = view.blackConnected().blend(phase);
        whitePawnIslandPenalty = view.whiteIslands().blend(phase);
        blackPawnIslandPenalty = view.blackIslands().blend(phase);
        whitePassedPawnBonus = view.whitePassed().blend(phase);
        blackPassedPawnBonus = view.blackPassed().blend(phase);
        whitePawnAdvanceBonus = view.whiteAdvance().blend(phase);
        blackPawnAdvanceBonus = view.blackAdvance().blend(phase);
        whiteBlockedPawnPenalty = view.whiteBlocked().blend(phase);
        blackBlockedPawnPenalty = view.blackBlocked().blend(phase);
        whiteBackwardPawnPenalty = view.whiteBackward().blend(phase);
        blackBackwardPawnPenalty = view.blackBackward().blend(phase);
    }

    private static int clampPhase(int phase) {
        if (phase < 0) {
            return 0;
        }
        if (phase > 256) {
            return 256;
        }
        return phase;
    }

    private final class LegacyEvaluationModule implements EvaluationModule {

        private int midgameScoreCache;
        private int endgameScoreCache;
        private boolean dirty = true;

        @Override
        public void initialize(EvaluationContext context) {
            markDirty();
        }

        @Override
        public void evaluate(EvaluationContext context) {
            int totalWhite = calculateTotalWhiteScore();
            int totalBlack = calculateTotalBlackScore();
            if (context != null) {
                BitBoard board = context.getBoard();
                if (board != null) {
                    PawnStructureModule.PawnStructureView view = pawnStructureModule.getView(board);
                    int phase = clampPhase(board.getPhase());
                    totalWhite -= view.blendWhiteTotal(phase);
                    totalBlack -= view.blendBlackTotal(phase);
                }
            }
            midgameScoreCache = totalWhite - totalBlack;
            endgameScoreCache = midgameScoreCache;
            dirty = false;
        }

        @Override
        public void applyMove(MoveContext moveContext) {
            markDirty();
        }

        @Override
        public void undoMove(MoveContext moveContext) {
            markDirty();
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
    }


    /**
     * Score mechanisms of the Game
     */
    public static Score initializeScore(BitBoard bitBoard) {
        Score score = new Score();
        score.initializeFrom(bitBoard);
        return score;
    }

    private void initializeFrom(BitBoard bitBoard) {
        long whitePawns = bitBoard.getWhitePawns();
        long blackPawns = bitBoard.getBlackPawns();
        long whiteKnights = bitBoard.getWhiteKnights();
        long blackKnights = bitBoard.getBlackKnights();
        long whiteBishops = bitBoard.getWhiteBishops();
        long blackBishops = bitBoard.getBlackBishops();
        long whiteRooks = bitBoard.getWhiteRooks();
        long blackRooks = bitBoard.getBlackRooks();
        long whiteQueens = bitBoard.getWhiteQueens();
        long blackQueens = bitBoard.getBlackQueens();
        long whiteKing = bitBoard.getWhiteKing();
        long blackKing = bitBoard.getBlackKing();
        int phase = bitBoard.getPhase();

        applyPawnStructure(bitBoard);

        updateRookHalfOpenFileBonusWhite(bitBoard);
        updateRookHalfOpenFileBonusBlack(bitBoard);
        updateRookOpenFileBonusWhite(bitBoard);
        updateRookOpenFileBonusBlack(bitBoard);
        updateKnightOutpostBonusWhite(whiteKnights, whitePawns, blackPawns);
        updateKnightOutpostBonusBlack(blackKnights, blackPawns, whitePawns);

        // Apply positional values to the pawns
        int materialBalance = computeMaterialBalance(bitBoard);
        updateWhiteKingsPositionBonus(whiteKing, whitePawns, blackPawns, bitBoard.isWhiteKingHasCastled(),
                bitBoard.isWhiteKingMoved(), bitBoard.isWhiteRookA1Moved(), bitBoard.isWhiteRookH1Moved(), phase,
                materialBalance);
        updateBlackKingsPositionBonus(blackKing, blackPawns, whitePawns, bitBoard.isBlackKingHasCastled(),
                bitBoard.isBlackKingMoved(), bitBoard.isBlackRookA8Moved(), bitBoard.isBlackRookH8Moved(), phase,
                materialBalance);

        // Mobility and king safety
        updateMobilityScores(bitBoard);
        updateKingSafety(bitBoard);

        calculateTotalWhiteScore();
        calculateTotalBlackScore();

        EvaluationContext context = EvaluationContext.from(bitBoard, null);
        this.evaluationContext = context;
        syncPipeline(context);
    }


    public double getScoreDifference() {
        if (pipelineInitialized) {
            return evaluationPipeline.getScoreDifference();
        }
        return (calculateTotalWhiteScore() - calculateTotalBlackScore()) / 100.0;
    }

    public int calculateTotalWhiteScore() {
        int totalWhiteScore = 0;

        totalWhiteScore += whiteCenterPawnBonus;
        totalWhiteScore += whiteDoubledPawnPenalty;
        totalWhiteScore += whiteIsolatedPawnPenalty;
        totalWhiteScore += whitePassedPawnBonus;
        totalWhiteScore += whitePawnAdvanceBonus;
        totalWhiteScore += whiteBlockedPawnPenalty;
        totalWhiteScore += whiteBackwardPawnPenalty;
        totalWhiteScore += whiteConnectedPawnBonus;
        totalWhiteScore += whitePawnIslandPenalty;

        totalWhiteScore += whiteRooksHalfOpenFileBonus;
        totalWhiteScore += whiteRooksOpenFileBonus;
        totalWhiteScore += whiteKnightOutpostBonus;

        totalWhiteScore += whiteKingCastlingAdjustment;

        totalWhiteScore += whiteMobilityScore;
        totalWhiteScore += whiteKingSafetyScore;
        totalWhiteScore += whiteQueenSafetyScore;
        totalWhiteScore += whiteMinorPieceSafetyScore;

        totalWhiteScore += whiteStateBonus;

        whiteScore = totalWhiteScore;
        // Add other scores and penalties if any
        return totalWhiteScore;
    }

    /**
     * Method to calculate the total score for black.
     */
    public int calculateTotalBlackScore() {
        int totalBlackScore = 0;

        totalBlackScore += blackCenterPawnBonus;
        totalBlackScore += blackDoubledPawnPenalty;
        totalBlackScore += blackIsolatedPawnPenalty;
        totalBlackScore += blackPassedPawnBonus;
        totalBlackScore += blackPawnAdvanceBonus;
        totalBlackScore += blackBlockedPawnPenalty;
        totalBlackScore += blackBackwardPawnPenalty;
        totalBlackScore += blackConnectedPawnBonus;
        totalBlackScore += blackPawnIslandPenalty;

        totalBlackScore += blackRooksHalfOpenFileBonus;
        totalBlackScore += blackRooksOpenFileBonus;
        totalBlackScore += blackKnightOutpostBonus;

        totalBlackScore += blackKingCastlingAdjustment;

        totalBlackScore += blackMobilityScore;
        totalBlackScore += blackKingSafetyScore;
        totalBlackScore += blackQueenSafetyScore;
        totalBlackScore += blackMinorPieceSafetyScore;

        totalBlackScore += blackStateBonus;

        blackScore = totalBlackScore;
        // Add other scores and penalties if any
        return totalBlackScore;
    }

    public void updateRookHalfOpenFileBonusWhite(BitBoard bitBoard) {
        long whiteRooks = bitBoard.getWhiteRooks();
        int count = pawnStructureModule.countHalfOpenFilesWithRooks(bitBoard, whiteRooks, true);
        whiteRooksHalfOpenFileBonus = count * ROOK_HALF_OPEN_FILE_BONUS;
    }

    public void updateRookHalfOpenFileBonusBlack(BitBoard bitBoard) {
        long blackRooks = bitBoard.getBlackRooks();
        int count = pawnStructureModule.countHalfOpenFilesWithRooks(bitBoard, blackRooks, false);
        blackRooksHalfOpenFileBonus = count * ROOK_HALF_OPEN_FILE_BONUS;
    }

    public void updateRookOpenFileBonusWhite(BitBoard bitBoard) {
        long whiteRooks = bitBoard.getWhiteRooks();
        int count = pawnStructureModule.countOpenFilesWithRooks(bitBoard, whiteRooks);
        whiteRooksOpenFileBonus = count * ROOK_OPEN_FILE_BONUS;
    }

    public void updateRookOpenFileBonusBlack(BitBoard bitBoard) {
        long blackRooks = bitBoard.getBlackRooks();
        int count = pawnStructureModule.countOpenFilesWithRooks(bitBoard, blackRooks);
        blackRooksOpenFileBonus = count * ROOK_OPEN_FILE_BONUS;
    }

    public void updateKnightOutpostBonusWhite(long whiteKnights, long whitePawns, long blackPawns) {
        long enemyAttacks = ((blackPawns & NOT_H_FILE) >>> 7) | ((blackPawns & NOT_A_FILE) >>> 9);
        long ownAttacks = ((whitePawns & NOT_A_FILE) << 7) | ((whitePawns & NOT_H_FILE) << 9);
        whiteKnightOutpostBonus = calculateKnightOutpostBonus(whiteKnights, ownAttacks, enemyAttacks);
    }

    public void updateKnightOutpostBonusBlack(long blackKnights, long blackPawns, long whitePawns) {
        long enemyAttacks = ((whitePawns & NOT_A_FILE) << 7) | ((whitePawns & NOT_H_FILE) << 9);
        long ownAttacks = ((blackPawns & NOT_H_FILE) >>> 7) | ((blackPawns & NOT_A_FILE) >>> 9);
        blackKnightOutpostBonus = calculateKnightOutpostBonus(blackKnights, ownAttacks, enemyAttacks);
    }

    private int calculateKnightOutpostBonus(long knights, long ownPawnAttacks, long enemyPawnAttacks) {
        int bonus = 0;
        long remaining = knights;
        while (remaining != 0) {
            long knight = remaining & -remaining;
            if ((enemyPawnAttacks & knight) == 0) {
                bonus += KNIGHT_OUTPOST_BONUS;
                if ((ownPawnAttacks & knight) != 0) {
                    bonus += KNIGHT_OUTPOST_DEFENDED_BONUS;
                }
            }
            remaining ^= knight;
        }
        return bonus;
    }

    private static int computeMaterialBalance(BitBoard bitBoard) {
        int whiteMaterial = Long.bitCount(bitBoard.getWhitePawns()) * PAWN_VALUE
                + Long.bitCount(bitBoard.getWhiteKnights()) * KNIGHT_VALUE
                + Long.bitCount(bitBoard.getWhiteBishops()) * BISHOP_VALUE
                + Long.bitCount(bitBoard.getWhiteRooks()) * ROOK_VALUE
                + Long.bitCount(bitBoard.getWhiteQueens()) * QUEEN_VALUE;
        int blackMaterial = Long.bitCount(bitBoard.getBlackPawns()) * PAWN_VALUE
                + Long.bitCount(bitBoard.getBlackKnights()) * KNIGHT_VALUE
                + Long.bitCount(bitBoard.getBlackBishops()) * BISHOP_VALUE
                + Long.bitCount(bitBoard.getBlackRooks()) * ROOK_VALUE
                + Long.bitCount(bitBoard.getBlackQueens()) * QUEEN_VALUE;
        return whiteMaterial - blackMaterial;
    }

    public void updateWhiteKingsPositionBonus(long whiteKing, long whitePawns, long blackPawns,
                                              boolean isCastled, boolean isWhiteKingMoved,
                                              boolean rookA1Moved, boolean rookH1Moved, int phase,
                                              int materialBalance) {
        whiteKingCastlingAdjustment = 0;
        int castlingBonus = CASTLING_BONUS * (256 - phase) / 256;
        int rookMovePenalty = NOT_CASTLED_AND_ROOK_MOVE_PENALTY * (256 - phase) / 256;
        if (materialBalance < 0) {
            rookMovePenalty /= 2;
        }

        boolean applyPenalty = false;
        if (!isCastled) {
            int kingIndex = Long.numberOfTrailingZeros(whiteKing);
            long forwardMask = (whiteKing << 8);
            if ((whiteKing & NOT_A_FILE) != 0) forwardMask |= (whiteKing << 7);
            if ((whiteKing & NOT_H_FILE) != 0) forwardMask |= (whiteKing << 9);

            boolean missingShield = Long.bitCount(whitePawns & forwardMask) < 3;

            int fileIndex = kingIndex % 8;
            long fileMask = FileMasks[fileIndex];
            boolean openOrHalfOpen = (whitePawns & fileMask) == 0;

            applyPenalty = missingShield || openOrHalfOpen;
        }

        if (isCastled) {
            whiteKingCastlingAdjustment += castlingBonus;
        } else if (applyPenalty) {
            if (rookA1Moved) {
                whiteKingCastlingAdjustment += rookMovePenalty;
            }
            if (rookH1Moved) {
                whiteKingCastlingAdjustment += rookMovePenalty;
            }
            if (isWhiteKingMoved) {
                whiteKingCastlingAdjustment += rookMovePenalty * 2;
            }
        }
    }

    public void updateBlackKingsPositionBonus(long blackKing, long blackPawns, long whitePawns,
                                              boolean isCastled, boolean isBlackKingMoved,
                                              boolean rookA8Moved, boolean rookH8Moved, int phase,
                                              int materialBalance) {
        blackKingCastlingAdjustment = 0;
        int castlingBonus = CASTLING_BONUS * (256 - phase) / 256;
        int rookMovePenalty = NOT_CASTLED_AND_ROOK_MOVE_PENALTY * (256 - phase) / 256;
        if (materialBalance > 0) {
            rookMovePenalty /= 2;
        }

        boolean applyPenalty = false;
        if (!isCastled) {
            int kingIndex = Long.numberOfTrailingZeros(blackKing);
            long forwardMask = (blackKing >>> 8);
            if ((blackKing & NOT_A_FILE) != 0) forwardMask |= (blackKing >>> 9);
            if ((blackKing & NOT_H_FILE) != 0) forwardMask |= (blackKing >>> 7);

            boolean missingShield = Long.bitCount(blackPawns & forwardMask) < 3;

            int fileIndex = kingIndex % 8;
            long fileMask = FileMasks[fileIndex];
            boolean openOrHalfOpen = (blackPawns & fileMask) == 0;

            applyPenalty = missingShield || openOrHalfOpen;
        }

        if (isCastled) {
            blackKingCastlingAdjustment += castlingBonus;
        } else if (applyPenalty) {
            if (rookA8Moved) {
                blackKingCastlingAdjustment += rookMovePenalty;
            }
            if (rookH8Moved) {
                blackKingCastlingAdjustment += rookMovePenalty;
            }
            if (isBlackKingMoved) {
                blackKingCastlingAdjustment += rookMovePenalty * 2;
            }
        }
    }


    public void updateMobilityScores(int whiteScore, int blackScore) {
        whiteMobilityScore = whiteScore;
        blackMobilityScore = blackScore;
    }

    public void updateMobilityScores(BitBoard bitBoard) {
        int[] mobility = MOBILITY_BUFFER.get();
        mobility[0] = calculateMobility(bitBoard, true);
        mobility[1] = calculateMobility(bitBoard, false);

        updateMobilityScores(mobility[0], mobility[1]);
    }

    private int calculateMobility(BitBoard bitBoard, boolean white) {
        long friendlyPieces = white ? bitBoard.getWhitePieces() : bitBoard.getBlackPieces();
        long knights = white ? bitBoard.getWhiteKnights() : bitBoard.getBlackKnights();
        long bishops = white ? bitBoard.getWhiteBishops() : bitBoard.getBlackBishops();
        long rooks = white ? bitBoard.getWhiteRooks() : bitBoard.getBlackRooks();
        long queens = white ? bitBoard.getWhiteQueens() : bitBoard.getBlackQueens();
        long occupancy = bitBoard.getAllPieces();

        int score = 0;

        while (knights != 0) {
            int square = Long.numberOfTrailingZeros(knights);
            long attacks = knightMoveTable[square] & ~friendlyPieces;
            score += mobilityFromAttacks(attacks, KNIGHT_MOBILITY_BONUS);
            knights &= knights - 1;
        }

        while (bishops != 0) {
            int square = Long.numberOfTrailingZeros(bishops);
            long attacks = BISHOP_HELPER.calculateBishopMoves(square, occupancy) & ~friendlyPieces;
            score += mobilityFromAttacks(attacks, BISHOP_MOBILITY_BONUS);
            bishops &= bishops - 1;
        }

        while (rooks != 0) {
            int square = Long.numberOfTrailingZeros(rooks);
            long attacks = ROOK_HELPER.calculateRookMoves(square, occupancy) & ~friendlyPieces;
            score += mobilityFromAttacks(attacks, ROOK_MOBILITY_BONUS);
            rooks &= rooks - 1;
        }

        while (queens != 0) {
            int square = Long.numberOfTrailingZeros(queens);
            long attacks = (BISHOP_HELPER.calculateBishopMoves(square, occupancy)
                    | ROOK_HELPER.calculateRookMoves(square, occupancy)) & ~friendlyPieces;
            score += mobilityFromAttacks(attacks, QUEEN_MOBILITY_BONUS);
            queens &= queens - 1;
        }

        return score;
    }

    private static int mobilityFromAttacks(long attacks, int mobilityBonus) {
        if (attacks == 0) {
            return 0;
        }
        int moves = Long.bitCount(attacks);
        return (mobilityBonus * moves) + centerControlContribution(attacks);
    }

    private static int centerControlContribution(long attacks) {
        long centerTargets = attacks & CENTRAL_SQUARES;
        return centerTargets != 0 ? CENTER_CONTROL_BONUS * Long.bitCount(centerTargets) : 0;
    }

    private boolean hasNoLegalMove(BitBoard board, boolean white) {
        MoveList moves = board.generateAllPossibleMoves(white);
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getMove(i);
            BitBoard copy = new BitBoard(board);
            copy.performMove(move);
            if (!copy.isInCheck(white)) {
                return false;
            }
        }
        return true;
    }

    private int evaluateMinorPieceSafety(
            long knights,
            long bishops,
            long rooks,
            long friendlyPawns,
            long friendlyQueens,
            long friendlyKing,
            long enemyPawns,
            long enemyKnights,
            long enemyBishops,
            long enemyRooks,
            long enemyQueens,
            long enemyKing,
            long allPieces,
            long enemyAttacks,
            boolean isWhite) {
        long pieces = knights | bishops | rooks;
        if (pieces == 0) {
            return 0;
        }

        int penalty = 0;
        BishopHelper bishopHelper = BishopHelper.getInstance();
        RookHelper rookHelper = RookHelper.getInstance();
        int enemyColor = isWhite ? 1 : 0;
        int friendlyColor = isWhite ? 0 : 1;

        while (pieces != 0) {
            long sq = pieces & -pieces;
            int index = Long.numberOfTrailingZeros(sq);

            if ((enemyAttacks & sq) == 0) {
                pieces ^= sq;
                continue;
            }

            int pawnAttackers = countPawnAttacks(enemyPawns, index, enemyColor);
            int knightAttackers = countKnightAttacks(enemyKnights, index);
            int bishopAttackers = countBishopAttacks(enemyBishops, index, allPieces, bishopHelper);
            int rookAttackers = countRookAttacks(enemyRooks, index, allPieces, rookHelper);
            int queenAttackers = countQueenAttacks(enemyQueens, index, allPieces, bishopHelper, rookHelper);
            int kingAttackers = countKingAttacks(enemyKing, index);

            int totalAttackers = pawnAttackers + knightAttackers + bishopAttackers
                    + rookAttackers + queenAttackers + kingAttackers;
            if (totalAttackers == 0) {
                pieces ^= sq;
                continue;
            }

            long ignoreMask = sq;
            int pawnDefenders = countPawnAttacks(friendlyPawns, index, friendlyColor);
            int knightDefenders = countKnightAttacks(knights & ~ignoreMask, index);
            int bishopDefenders = countBishopAttacks(bishops & ~ignoreMask, index, allPieces, bishopHelper);
            int rookDefenders = countRookAttacks(rooks & ~ignoreMask, index, allPieces, rookHelper);
            int queenDefenders = countQueenAttacks(friendlyQueens & ~ignoreMask, index, allPieces, bishopHelper, rookHelper);
            int kingDefenders = countKingAttacks(friendlyKing & ~ignoreMask, index);

            int totalDefenders = pawnDefenders + knightDefenders + bishopDefenders
                    + rookDefenders + queenDefenders + kingDefenders;

            if (totalAttackers > totalDefenders) {
                penalty += MINOR_PIECE_ATTACK_PENALTY;
            }
            if (pawnAttackers > 0) {
                penalty += MINOR_PIECE_PAWN_ATTACK_PENALTY;
            }

            pieces ^= sq;
        }

        return penalty;
    }

    private int countPawnAttacks(long pawns, int targetIndex, int pawnColor) {
        if (pawns == 0) {
            return 0;
        }
        int count = 0;
        long mask = 1L << targetIndex;
        long remaining = pawns;
        while (remaining != 0) {
            long sq = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(sq);
            if ((PAWN_ATTACKS[pawnColor][index] & mask) != 0) {
                count++;
            }
            remaining ^= sq;
        }
        return count;
    }

    private int countKnightAttacks(long knights, int targetIndex) {
        if (knights == 0) {
            return 0;
        }
        int count = 0;
        long mask = 1L << targetIndex;
        long remaining = knights;
        while (remaining != 0) {
            long sq = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(sq);
            if ((knightMoveTable[index] & mask) != 0) {
                count++;
            }
            remaining ^= sq;
        }
        return count;
    }

    private int countBishopAttacks(long bishops, int targetIndex, long allPieces, BishopHelper bishopHelper) {
        if (bishops == 0) {
            return 0;
        }
        int count = 0;
        long mask = 1L << targetIndex;
        long remaining = bishops;
        while (remaining != 0) {
            long sq = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(sq);
            if ((bishopHelper.calculateBishopMoves(index, allPieces) & mask) != 0) {
                count++;
            }
            remaining ^= sq;
        }
        return count;
    }

    private int countRookAttacks(long rooks, int targetIndex, long allPieces, RookHelper rookHelper) {
        if (rooks == 0) {
            return 0;
        }
        int count = 0;
        long mask = 1L << targetIndex;
        long remaining = rooks;
        while (remaining != 0) {
            long sq = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(sq);
            if ((rookHelper.calculateRookMoves(index, allPieces) & mask) != 0) {
                count++;
            }
            remaining ^= sq;
        }
        return count;
    }

    private int countQueenAttacks(long queens, int targetIndex, long allPieces,
                                  BishopHelper bishopHelper, RookHelper rookHelper) {
        if (queens == 0) {
            return 0;
        }
        int count = 0;
        long mask = 1L << targetIndex;
        long remaining = queens;
        while (remaining != 0) {
            long sq = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(sq);
            long attacks = bishopHelper.calculateBishopMoves(index, allPieces)
                    | rookHelper.calculateRookMoves(index, allPieces);
            if ((attacks & mask) != 0) {
                count++;
            }
            remaining ^= sq;
        }
        return count;
    }

    private int countKingAttacks(long king, int targetIndex) {
        if (king == 0) {
            return 0;
        }
        int kingIndex = Long.numberOfTrailingZeros(king);
        return (KING_ATTACKS[kingIndex] & (1L << targetIndex)) != 0 ? 1 : 0;
    }

    public void updateKingSafety(BitBoard bitBoard) {
        long whiteKing = bitBoard.getWhiteKing();
        long blackKing = bitBoard.getBlackKing();
        long whitePawns = bitBoard.getWhitePawns();
        long blackPawns = bitBoard.getBlackPawns();

        long whiteAttacks = bitBoard.getAttackBitboard(true);
        long blackAttacks = bitBoard.getAttackBitboard(false);
        long allPieces = bitBoard.getAllPieces();
        long whiteKnights = bitBoard.getWhiteKnights();
        long whiteBishops = bitBoard.getWhiteBishops();
        long whiteRooks = bitBoard.getWhiteRooks();
        long whiteQueens = bitBoard.getWhiteQueens();
        long blackKnights = bitBoard.getBlackKnights();
        long blackBishops = bitBoard.getBlackBishops();
        long blackRooks = bitBoard.getBlackRooks();
        long blackQueens = bitBoard.getBlackQueens();

        KingSafetyModule.KingSafetyView safetyView = kingSafetyModule.evaluate(bitBoard);
        int phase = clampPhase(bitBoard.getPhase());
        whiteKingSafetyScore = safetyView.whiteKing().blend(phase);
        blackKingSafetyScore = safetyView.blackKing().blend(phase);
        whiteQueenSafetyScore = safetyView.whiteQueen().blend(phase);
        blackQueenSafetyScore = safetyView.blackQueen().blend(phase);

        whiteMinorPieceSafetyScore = evaluateMinorPieceSafety(
                whiteKnights,
                whiteBishops,
                whiteRooks,
                whitePawns,
                whiteQueens,
                whiteKing,
                blackPawns,
                blackKnights,
                blackBishops,
                blackRooks,
                blackQueens,
                blackKing,
                allPieces,
                blackAttacks,
                true);

        blackMinorPieceSafetyScore = evaluateMinorPieceSafety(
                blackKnights,
                blackBishops,
                blackRooks,
                blackPawns,
                blackQueens,
                blackKing,
                whitePawns,
                whiteKnights,
                whiteBishops,
                whiteRooks,
                whiteQueens,
                whiteKing,
                allPieces,
                whiteAttacks,
                false);

    }

    public void updateWhitePawnValues(BitBoard bitBoard) {
        applyPawnStructure(bitBoard);
        updateBlackRookValues(bitBoard);
        updateWhiteRookValues(bitBoard);
        updateWhiteKnightValues(bitBoard);
        updateBlackKnightValues(bitBoard);
    }

    public void updateBlackPawnValues(BitBoard bitBoard) {
        applyPawnStructure(bitBoard);
        updateBlackRookValues(bitBoard);
        updateWhiteRookValues(bitBoard);
        updateWhiteKnightValues(bitBoard);
        updateBlackKnightValues(bitBoard);
    }

    public void updateWhiteKnightValues(BitBoard bitBoard) {
        long whiteKnights = bitBoard.getWhiteKnights();
        long whiteBishops = bitBoard.getWhiteBishops();
        long whiteRooks = bitBoard.getWhiteRooks();
        long whiteQueens = bitBoard.getWhiteQueens();
        long whitePawns = bitBoard.getWhitePawns();
        long blackPawns = bitBoard.getBlackPawns();
        updateKnightOutpostBonusWhite(whiteKnights, whitePawns, blackPawns);
    }

    public void updateBlackKnightValues(BitBoard bitBoard) {
        long blackKnights = bitBoard.getBlackKnights();
        long blackBishops = bitBoard.getBlackBishops();
        long blackRooks = bitBoard.getBlackRooks();
        long blackQueens = bitBoard.getBlackQueens();
        long blackPawns = bitBoard.getBlackPawns();
        long whitePawns = bitBoard.getWhitePawns();
        updateKnightOutpostBonusBlack(blackKnights, blackPawns, whitePawns);
    }

    public void updateWhiteRookValues(BitBoard bitBoard) {
        updateRookHalfOpenFileBonusWhite(bitBoard);
        updateRookOpenFileBonusWhite(bitBoard);
        updateKingValuesWhite(bitBoard);
    }

    public void updateBlackRookValues(BitBoard bitBoard) {
        updateRookHalfOpenFileBonusBlack(bitBoard);
        updateRookOpenFileBonusBlack(bitBoard);
        updateKingValuesBlack(bitBoard);
    }

    public void applyMove(BitBoard bitBoard, int move, GameStateEnum state) {
        boolean isWhite = MoveHelper.isWhitesMove(move);
        int pieceTypeBits = MoveHelper.derivePieceTypeBits(move);
        int capturedPieceTypeBits = MoveHelper.deriveCapturedPieceTypeBits(move);
        int promotionPieceTypeBits = MoveHelper.derivePromotionPieceTypeBits(move);

        updatePieceValues(isWhite, pieceTypeBits, bitBoard, state);

        if (capturedPieceTypeBits != 0) {
            updateCapturedPieceValues(isWhite, capturedPieceTypeBits, bitBoard);
        }
        if (promotionPieceTypeBits != 0) {
            updatePromotionPieceValues(isWhite, promotionPieceTypeBits, bitBoard);
        }

        updateMobilityScores(bitBoard);
        updateKingSafety(bitBoard);

        EvaluationContext previousContext = this.evaluationContext;
        EvaluationContext updatedContext = EvaluationContext.from(bitBoard, state);
        this.evaluationContext = updatedContext;
        syncPipeline(updatedContext);
        MoveContext moveContext = new MoveContext(move, previousContext, updatedContext);
        evaluationPipeline.applyMove(moveContext);
    }

    public void undoMove(BitBoard bitBoard, int move, GameStateEnum state) {
        // Board has already been reverted; reuse applyMove logic
        applyMove(bitBoard, move, state);
    }

    private void updatePieceValues(boolean isWhite, int pieceTypeBits, BitBoard bitBoard, GameStateEnum state) {
        if (isWhite) {
            updateValuesForWhite(pieceTypeBits, bitBoard);
            updateStateValuesWhite(state);
        } else {
            updateValuesForBlack(pieceTypeBits, bitBoard);
            updateStateValuesBlack(state);
        }
    }

    private void updateValuesForWhite(int pieceTypeBits, BitBoard bitBoard) {
        switch (pieceTypeBits) {
            case 1 -> updateWhitePawnValues(bitBoard);
            case 2 -> updateWhiteKnightValues(bitBoard);
            case 4 -> updateWhiteRookValues(bitBoard);
            case 6 -> updateKingValuesWhite(bitBoard);
            default -> {
            }
        }
    }

    private void updateValuesForBlack(int pieceTypeBits, BitBoard bitBoard) {
        switch (pieceTypeBits) {
            case 1 -> updateBlackPawnValues(bitBoard);
            case 2 -> updateBlackKnightValues(bitBoard);
            case 4 -> updateBlackRookValues(bitBoard);
            case 6 -> updateKingValuesBlack(bitBoard);
            default -> {
            }
        }
    }

    private void updateCapturedPieceValues(boolean isWhite, int capturedPieceTypeBits, BitBoard bitBoard) {
        if (isWhite) {
            updateValuesForBlack(capturedPieceTypeBits, bitBoard);
        } else {
            updateValuesForWhite(capturedPieceTypeBits, bitBoard);
        }
    }

    private void updatePromotionPieceValues(boolean isWhite, int promotionPieceTypeBits, BitBoard bitBoard) {
        if (isWhite) {
            updateValuesForWhite(promotionPieceTypeBits, bitBoard);
        } else {
            updateValuesForBlack(promotionPieceTypeBits, bitBoard);
        }
    }

    public void updateKingValuesWhite(BitBoard bitBoard) {
        updateWhiteKingsPositionBonus(bitBoard.getWhiteKing(), bitBoard.getWhitePawns(), bitBoard.getBlackPawns(),
                bitBoard.isWhiteKingHasCastled(), bitBoard.isWhiteKingMoved(), bitBoard.isWhiteRookA1Moved(),
                bitBoard.isWhiteRookH1Moved(), bitBoard.getPhase(), computeMaterialBalance(bitBoard));
    }

    public void updateKingValuesBlack(BitBoard bitBoard) {
        updateBlackKingsPositionBonus(bitBoard.getBlackKing(), bitBoard.getBlackPawns(), bitBoard.getWhitePawns(),
                bitBoard.isBlackKingHasCastled(), bitBoard.isBlackKingMoved(), bitBoard.isBlackRookA8Moved(),
                bitBoard.isBlackRookH8Moved(), bitBoard.getPhase(), computeMaterialBalance(bitBoard));
    }

    public int getWhiteKingsPosition() {
        return whiteKingCastlingAdjustment;
    }

    public int getBlackKingsPosition() {
        return blackKingCastlingAdjustment;
    }

    public void updateStateValuesWhite(GameStateEnum state) {
        if (state.equals(GameStateEnum.WHITE_WON)) {
            whiteStateBonus = CHECKMATE;
        } else {
            whiteStateBonus = 0;
        }
    }

    public void updateStateValuesBlack(GameStateEnum state) {
        if (state.equals(GameStateEnum.BLACK_WON)) {
            blackStateBonus = CHECKMATE;
        } else {
            blackStateBonus = 0;
        }
    }

    public static int getPieceValue(int pieceTypeBits) {
        return switch (pieceTypeBits) {
            case 1 -> PAWN_VALUE / 100;
            case 2 -> KNIGHT_VALUE / 100;
            case 3 -> BISHOP_VALUE / 100;
            case 4 -> ROOK_VALUE / 100;
            case 5 -> QUEEN_VALUE / 100;
            case 6 -> 1000;
            default -> throw new IllegalStateException("Unexpected value: " + pieceTypeBits);
        };
    }


}
