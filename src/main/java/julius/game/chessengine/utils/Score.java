package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.RookHelper;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static julius.game.chessengine.helper.BishopHelper.BISHOP_ENDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.BishopHelper.BISHOP_MIDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.KingHelper.*;
import static julius.game.chessengine.helper.KnightHelper.KNIGHT_ENDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.KnightHelper.KNIGHT_MIDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.KnightHelper.knightMoveTable;
import static julius.game.chessengine.helper.PawnHelper.*;
import static julius.game.chessengine.helper.PawnMoveTables.PAWN_ATTACKS;
import static julius.game.chessengine.helper.PawnMoveTables.PAWN_PUSHES;
import static julius.game.chessengine.helper.QueenHelper.QUEEN_ENDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.QueenHelper.QUEEN_MIDGAME_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.RookHelper.*;
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

    // --- Pawn structure (cp). Slightly gentler, less swingy. ---
    private static final int DOUBLED_PAWN_PENALTY  = -12; // was -20
    private static final int ISOLATED_PAWN_PENALTY = -10; // ok
    public  static final int PASSED_PAWN_BONUS     = 60;  // kept
    public  static final int CENTER_PAWN_BONUS     = 15;  // was 20
    private static final int ADVANCED_PAWN_BONUS   = 8;   // was 10
    private static final int BLOCKED_PAWN_PENALTY  = -10; // was -8
    private static final int BACKWARD_PAWN_PENALTY = -12; // ok
    private static final int CONNECTED_PAWN_BONUS  = 8;
    private static final int PAWN_ISLAND_PENALTY   = -5;

    // FIX: steer king away from blocking own passers / reward clear paths
    private static final int OWN_KING_BLOCKS_PASSED_PAWN_PENALTY = -150; // big to outweigh PST pull
    private static final int PASSED_PAWN_FREE_PATH_BONUS_PER_RANK = 12;  // small but helps pushing

    // Other bonuses and penalties
    private static final int NOT_CASTLED_AND_ROOK_MOVE_PENALTY = -10; // further reduced
    private static final int START_POSITION_PENALTY            = -40; // was -50
    private static final int CASTLING_BONUS                    = 20;  // further reduced

    // Development tuning constants
    private static final int DEVELOPMENT_PHASE_THRESHOLD              = 64;
    private static final int QUEEN_DEVELOPMENT_PHASE_THRESHOLD        = 80;
    private static final int UNDEVELOPED_MINOR_PENALTY                = -20;
    private static final int EARLY_QUEEN_DEVELOPMENT_PENALTY_PER_MINOR = -15;
    private static final int MIN_UNDEVELOPED_MINORS_FOR_QUEEN_PENALTY  = 2;

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

    private static final long[] EMPTY_LONG_ARRAY = new long[0];

    private Double cachedScoreDifference = null;

    private int whiteScore;
    private int blackScore;

    //initialize number of pieces bonus
    private int whitePawnsAmountScore = 0;
    private int blackPawnsAmountScore = 0;
    private int whiteKnightsAmountScore = 0;
    private int blackKnightsAmountScore = 0;
    private int whiteBishopsAmountScore = 0;
    private int blackBishopsAmountScore = 0;
    private int whiteRooksAmountScore = 0;
    private int blackRooksAmountScore = 0;
    private int whiteQueensAmountScore = 0;
    private int blackQueensAmountScore = 0;

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
    private int whiteBishopPairBonus = 0;
    private int blackBishopPairBonus = 0;
    private int whiteKnightOutpostBonus = 0;
    private int blackKnightOutpostBonus = 0;

    private static final BishopHelper BISHOP_HELPER = BishopHelper.getInstance();
    private static final RookHelper ROOK_HELPER = RookHelper.getInstance();
    // Thread-local accumulator reused to avoid per-call allocations when recomputing mobility.
    private static final ThreadLocal<int[]> MOBILITY_BUFFER = ThreadLocal.withInitial(() -> new int[2]);

    // Initialize positional values
    private int whitePawnsPosition = 0;
    private int blackPawnsPosition = 0;
    private int whiteKnightsPosition = 0;
    private int blackKnightsPosition = 0;
    private int whiteBishopsPosition = 0;
    private int blackBishopsPosition = 0;
    private int whiteRooksPosition = 0;
    private int blackRooksPosition = 0;
    private int whiteQueensPosition = 0;
    private int blackQueensPosition = 0;
    private int whiteKingsPosition = 0;
    private int blackKingsPosition = 0;

    private int whiteStartingSquarePenalty = 0;
    private int blackStartingSquarePenalty = 0;
    private int whiteMinorDevelopmentPenalty = 0;
    private int blackMinorDevelopmentPenalty = 0;
    private int whiteQueenDevelopmentPenalty = 0;
    private int blackQueenDevelopmentPenalty = 0;

    // State bonuses
    private int whiteStateBonus = 0;
    private int blackStateBonus = 0;

    private static final long NOT_A_FILE = ~FileMasks[0];
    private static final long NOT_H_FILE = ~FileMasks[7];

    // Constants for the initial positions of each piece type
    private static final long INITIAL_WHITE_KNIGHT_POSITION = 0x0000000000000042L; // Knights on b1 and g1
    private static final long INITIAL_BLACK_KNIGHT_POSITION = 0x4200000000000000L; // Knights on b8 and g8
    private static final long INITIAL_WHITE_BISHOP_POSITION = 0x0000000000000024L; // Bishops on c1 and f1
    private static final long INITIAL_BLACK_BISHOP_POSITION = 0x2400000000000000L; // Bishops on c8 and f8
    private static final long INITIAL_WHITE_ROOK_POSITION = 0x0000000000000081L; // Rooks on a1 and h1
    private static final long INITIAL_BLACK_ROOK_POSITION = 0x8100000000000000L; // Rooks on a8 and h8
    private static final long INITIAL_WHITE_QUEEN_POSITION = 1L << bitIndex('d', 1);
    private static final long INITIAL_BLACK_QUEEN_POSITION = 1L << bitIndex('d', 8);


    // Cache for pawn structure evaluations to avoid recomputation
    private static final ConcurrentMap<Long, PawnStructure> pawnStructureCache = new ConcurrentHashMap<>();

    private static final class PawnStructure {
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

        private PawnStructure(long whitePawns, long blackPawns) {
            this.whiteCenterPawnBonus = countCenterPawns(whitePawns) * CENTER_PAWN_BONUS;
            this.blackCenterPawnBonus = countCenterPawns(blackPawns) * CENTER_PAWN_BONUS;
            this.whiteDoubledPawnPenalty = countDoubledPawns(whitePawns) * DOUBLED_PAWN_PENALTY;
            this.blackDoubledPawnPenalty = countDoubledPawns(blackPawns) * DOUBLED_PAWN_PENALTY;
            this.whiteIsolatedPawnPenalty = countIsolatedPawns(whitePawns) * ISOLATED_PAWN_PENALTY;
            this.blackIsolatedPawnPenalty = countIsolatedPawns(blackPawns) * ISOLATED_PAWN_PENALTY;
            this.whiteConnectedPawnBonus = countConnectedPawns(whitePawns) * CONNECTED_PAWN_BONUS;
            this.blackConnectedPawnBonus = countConnectedPawns(blackPawns) * CONNECTED_PAWN_BONUS;
            this.whitePawnIslandPenalty = Math.max(0, countPawnIslands(whitePawns) - 1) * PAWN_ISLAND_PENALTY;
            this.blackPawnIslandPenalty = Math.max(0, countPawnIslands(blackPawns) - 1) * PAWN_ISLAND_PENALTY;
        }
    }

    private static long pawnHash(long whitePawns, long blackPawns) {
        return whitePawns ^ (blackPawns << 1);
    }

    private static PawnStructure getPawnStructure(long whitePawns, long blackPawns) {
        long key = pawnHash(whitePawns, blackPawns);
        return pawnStructureCache.computeIfAbsent(key, k -> new PawnStructure(whitePawns, blackPawns));
    }


    public Score() {
        this.whiteScore = 0;
        this.blackScore = 0;
    }

    public Score(Score other) {
        this.whiteScore = other.whiteScore;
        this.blackScore = other.blackScore;

        this.whitePawnsAmountScore = other.whitePawnsAmountScore;
        this.blackPawnsAmountScore = other.blackPawnsAmountScore;
        this.whiteKnightsAmountScore = other.whiteKnightsAmountScore;
        this.blackKnightsAmountScore = other.blackKnightsAmountScore;
        this.whiteBishopsAmountScore = other.whiteBishopsAmountScore;
        this.blackBishopsAmountScore = other.blackBishopsAmountScore;
        this.whiteRooksAmountScore = other.whiteRooksAmountScore;
        this.blackRooksAmountScore = other.blackRooksAmountScore;
        this.whiteQueensAmountScore = other.whiteQueensAmountScore;
        this.blackQueensAmountScore = other.blackQueensAmountScore;

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
        this.whiteBishopPairBonus = other.whiteBishopPairBonus;
        this.blackBishopPairBonus = other.blackBishopPairBonus;
        this.whiteKnightOutpostBonus = other.whiteKnightOutpostBonus;
        this.blackKnightOutpostBonus = other.blackKnightOutpostBonus;

        this.whitePawnsPosition = other.whitePawnsPosition;
        this.blackPawnsPosition = other.blackPawnsPosition;
        this.whiteKnightsPosition = other.whiteKnightsPosition;
        this.blackKnightsPosition = other.blackKnightsPosition;
        this.whiteBishopsPosition = other.whiteBishopsPosition;
        this.blackBishopsPosition = other.blackBishopsPosition;
        this.whiteRooksPosition = other.whiteRooksPosition;
        this.blackRooksPosition = other.blackRooksPosition;
        this.whiteQueensPosition = other.whiteQueensPosition;
        this.blackQueensPosition = other.blackQueensPosition;
        this.whiteKingsPosition = other.whiteKingsPosition;
        this.blackKingsPosition = other.blackKingsPosition;
        this.whiteStartingSquarePenalty = other.whiteStartingSquarePenalty;
        this.blackStartingSquarePenalty = other.blackStartingSquarePenalty;
        this.whiteMinorDevelopmentPenalty = other.whiteMinorDevelopmentPenalty;
        this.blackMinorDevelopmentPenalty = other.blackMinorDevelopmentPenalty;
        this.whiteQueenDevelopmentPenalty = other.whiteQueenDevelopmentPenalty;
        this.blackQueenDevelopmentPenalty = other.blackQueenDevelopmentPenalty;

        this.whiteStateBonus = other.whiteStateBonus;
        this.blackStateBonus = other.blackStateBonus;

        this.cachedScoreDifference = other.cachedScoreDifference;
    }


    /**
     * Score mechanisms of the Game
     */
    public void initializeScore(BitBoard bitBoard) {
        cachedScoreDifference = null;

        long whitePawns = bitBoard.getWhitePawns();
        long blackPawns = bitBoard.getBlackPawns();
        long allPawns = whitePawns | blackPawns;
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
        long allPieces = bitBoard.getAllPieces();
        long whiteAttacks = bitBoard.generateAttackBitboard(true);
        long blackAttacks = bitBoard.generateAttackBitboard(false);
        int phase = bitBoard.getPhase();

        updateCenterPawnBonusWhite(whitePawns);
        updateCenterPawnBonusBlack(blackPawns);

        initializePawnScore(whitePawns, blackPawns);
        initializeKnightScore(whiteKnights, blackKnights);
        initializeBishopScore(whiteBishops, blackBishops);
        whiteBishopPairBonus = Long.bitCount(whiteBishops) == 2 ? BISHOP_PAIR_BONUS : 0;
        blackBishopPairBonus = Long.bitCount(blackBishops) == 2 ? BISHOP_PAIR_BONUS : 0;
        initializeRookScore(whiteRooks, blackRooks);
        initializeQueenScore(whiteQueens, blackQueens);

        updateDoubledPawnPenaltyWhite(whitePawns);
        updateDoubledPawnPenaltyBlack(blackPawns);
        updateIsolatedPawnPenaltyWhite(whitePawns);
        updateIsolatedPawnPenaltyBlack(blackPawns);

        // FIX: passed-pawn bonus considers self-blockers / clear paths
        updatePassedPawnBonusWhite(whitePawns, blackPawns, allPieces, whiteKing);
        updatePassedPawnBonusBlack(blackPawns, whitePawns, allPieces, blackKing);

        whitePawnAdvanceBonus = calculatePawnAdvanceBonus(whitePawns, allPieces, blackAttacks, true, phase);
        blackPawnAdvanceBonus = calculatePawnAdvanceBonus(blackPawns, allPieces, whiteAttacks, false, phase);
        whiteBlockedPawnPenalty = calculateBlockedPawnPenalty(whitePawns, allPieces, true, phase);
        blackBlockedPawnPenalty = calculateBlockedPawnPenalty(blackPawns, allPieces, false, phase);
        whiteBackwardPawnPenalty = calculateBackwardPawnPenalty(whitePawns, blackPawns, allPieces, true, phase);
        blackBackwardPawnPenalty = calculateBackwardPawnPenalty(blackPawns, whitePawns, allPieces, false, phase);

        updateRookHalfOpenFileBonusWhite(whiteRooks, whitePawns, blackPawns);
        updateRookHalfOpenFileBonusBlack(blackRooks, blackPawns, whitePawns);
        updateRookOpenFileBonusWhite(whiteRooks, allPawns);
        updateRookOpenFileBonusBlack(blackRooks, allPawns);
        updateKnightOutpostBonusWhite(whiteKnights, whitePawns, blackPawns);
        updateKnightOutpostBonusBlack(blackKnights, blackPawns, whitePawns);

        // Apply positional values to the pawns
        updatePawnsPositionBonusWhite(whitePawns, phase);
        updatePawnsPositionBonusBlack(blackPawns, phase);

        updateKnightsPositionBonusWhite(whiteKnights, phase);
        updateKnightsPositionBonusBlack(blackKnights, phase);

        updateBishopsPositionBonusWhite(whiteBishops, phase);
        updateBishopsPositionBonusBlack(blackBishops, phase);

        updateRooksPositionBonusWhite(whiteRooks, phase);
        updateRooksPositionBonusBlack(blackRooks, phase);

        updateQueensPositionBonusWhite(whiteQueens, phase);
        updateQueensPositionBonusBlack(blackQueens, phase);

        updateWhiteKingsPositionBonus(whiteKing, whitePawns, blackPawns, bitBoard.isWhiteKingHasCastled(),
                bitBoard.isWhiteKingMoved(), bitBoard.isWhiteRookA1Moved(), bitBoard.isWhiteRookH1Moved(), phase);
        updateBlackKingsPositionBonus(blackKing, blackPawns, whitePawns, bitBoard.isBlackKingHasCastled(),
                bitBoard.isBlackKingMoved(), bitBoard.isBlackRookA8Moved(), bitBoard.isBlackRookH8Moved(), phase);

        updateStartingSquarePenaltyWhite(whiteKnights, whiteBishops, whiteRooks);
        updateStartingSquarePenaltyBlack(blackKnights, blackBishops, blackRooks);
        updateMinorDevelopmentPenaltyWhite(whiteKnights, whiteBishops, phase);
        updateMinorDevelopmentPenaltyBlack(blackKnights, blackBishops, phase);
        updateQueenDevelopmentPenaltyWhite(whiteQueens, whiteKnights, whiteBishops, phase);
        updateQueenDevelopmentPenaltyBlack(blackQueens, blackKnights, blackBishops, phase);

        // Mobility and king safety
        updateMobilityScores(bitBoard);
        updateKingSafety(bitBoard);

        calculateTotalWhiteScore();
        calculateTotalBlackScore();
    }


    public double getScoreDifference() {
        if (cachedScoreDifference == null) {
            cachedScoreDifference = (calculateTotalWhiteScore() - calculateTotalBlackScore()) / 100.0;
        }

        return cachedScoreDifference;
    }

    public int calculateTotalWhiteScore() {
        int totalWhiteScore = 0;

        // Add piece count bonuses
        totalWhiteScore += whitePawnsAmountScore;
        totalWhiteScore += whiteKnightsAmountScore;
        totalWhiteScore += whiteBishopsAmountScore;
        totalWhiteScore += whiteRooksAmountScore;
        totalWhiteScore += whiteQueensAmountScore;
        totalWhiteScore += whiteBishopPairBonus;

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

        totalWhiteScore += whitePawnsPosition;
        totalWhiteScore += whiteKnightsPosition;
        totalWhiteScore += whiteBishopsPosition;
        totalWhiteScore += whiteRooksPosition;
        totalWhiteScore += whiteQueensPosition;
        totalWhiteScore += whiteKingsPosition;
        totalWhiteScore += whiteStartingSquarePenalty;
        totalWhiteScore += whiteMinorDevelopmentPenalty;
        totalWhiteScore += whiteQueenDevelopmentPenalty;

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

        totalBlackScore += blackPawnsAmountScore;
        totalBlackScore += blackKnightsAmountScore;
        totalBlackScore += blackBishopsAmountScore;
        totalBlackScore += blackRooksAmountScore;
        totalBlackScore += blackQueensAmountScore;
        totalBlackScore += blackBishopPairBonus;

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

        totalBlackScore += blackPawnsPosition;
        totalBlackScore += blackKnightsPosition;
        totalBlackScore += blackBishopsPosition;
        totalBlackScore += blackRooksPosition;
        totalBlackScore += blackQueensPosition;
        totalBlackScore += blackKingsPosition;
        totalBlackScore += blackStartingSquarePenalty;
        totalBlackScore += blackMinorDevelopmentPenalty;
        totalBlackScore += blackQueenDevelopmentPenalty;

        totalBlackScore += blackMobilityScore;
        totalBlackScore += blackKingSafetyScore;
        totalBlackScore += blackQueenSafetyScore;
        totalBlackScore += blackMinorPieceSafetyScore;

        totalBlackScore += blackStateBonus;

        blackScore = totalBlackScore;
        // Add other scores and penalties if any
        return totalBlackScore;
    }

    // Methods to incrementally update scores for specific pieces or actions

    public void initializeQueenScore(long whiteQueens, long blackQueens) {
        this.whiteQueensAmountScore = Long.bitCount(whiteQueens) * QUEEN_VALUE;
        this.blackQueensAmountScore = Long.bitCount(blackQueens) * QUEEN_VALUE;
    }

    public void initializeRookScore(long whiteRooks, long blackRooks) {
        this.whiteRooksAmountScore = Long.bitCount(whiteRooks) * ROOK_VALUE;
        this.blackRooksAmountScore = Long.bitCount(blackRooks) * ROOK_VALUE;
    }

    public void initializeBishopScore(long whiteBishops, long blackBishops) {
        this.whiteBishopsAmountScore = Long.bitCount(whiteBishops) * BISHOP_VALUE;
        this.blackBishopsAmountScore = Long.bitCount(blackBishops) * BISHOP_VALUE;
    }

    public void initializeKnightScore(long whiteKnights, long blackKnights) {
        this.whiteKnightsAmountScore = Long.bitCount(whiteKnights) * KNIGHT_VALUE;
        this.blackKnightsAmountScore = Long.bitCount(blackKnights) * KNIGHT_VALUE;
    }

    public void initializePawnScore(long whitePawns, long blackPawns) {
        this.whitePawnsAmountScore = Long.bitCount(whitePawns) * PAWN_VALUE;
        this.blackPawnsAmountScore = Long.bitCount(blackPawns) * PAWN_VALUE;
        updatePawnIslandPenaltyWhite(whitePawns);
        updatePawnIslandPenaltyBlack(blackPawns);
        updateConnectedPawnBonusWhite(whitePawns);
        updateConnectedPawnBonusBlack(blackPawns);
    }

    /**
     * Bonuses and Penalties
     */

    // Method to add penalty for doubled pawns
    public void updateDoubledPawnPenaltyWhite(long whitePawns) {
        whiteDoubledPawnPenalty = countDoubledPawns(whitePawns) * DOUBLED_PAWN_PENALTY;
    }

    public void updateDoubledPawnPenaltyBlack(long blackPawns) {
        blackDoubledPawnPenalty = countDoubledPawns(blackPawns) * DOUBLED_PAWN_PENALTY;
    }

    // Method to add penalty for isolated pawns
    public void updateIsolatedPawnPenaltyWhite(long whitePawns) {
        whiteIsolatedPawnPenalty = countIsolatedPawns(whitePawns) * ISOLATED_PAWN_PENALTY;
    }

    public void updateIsolatedPawnPenaltyBlack(long blackPawns) {
        blackIsolatedPawnPenalty = countIsolatedPawns(blackPawns) * ISOLATED_PAWN_PENALTY;
    }

    public void updatePawnIslandPenaltyWhite(long whitePawns) {
        int islands = countPawnIslands(whitePawns);
        whitePawnIslandPenalty = Math.max(0, islands - 1) * PAWN_ISLAND_PENALTY;
    }

    public void updatePawnIslandPenaltyBlack(long blackPawns) {
        int islands = countPawnIslands(blackPawns);
        blackPawnIslandPenalty = Math.max(0, islands - 1) * PAWN_ISLAND_PENALTY;
    }

    public void updateConnectedPawnBonusWhite(long whitePawns) {
        whiteConnectedPawnBonus = countConnectedPawns(whitePawns) * CONNECTED_PAWN_BONUS;
    }

    public void updateConnectedPawnBonusBlack(long blackPawns) {
        blackConnectedPawnBonus = countConnectedPawns(blackPawns) * CONNECTED_PAWN_BONUS;
    }

    // FIX: new signatures accept allPieces and own king to avoid rewarding self-blocked passers
    public void updatePassedPawnBonusWhite(long whitePawns, long blackPawns, long allPieces, long whiteKing) {
        whitePassedPawnBonus = calculatePassedPawnBonus(whitePawns, blackPawns, allPieces, whiteKing, true);
    }

    public void updatePassedPawnBonusBlack(long blackPawns, long whitePawns, long allPieces, long blackKing) {
        blackPassedPawnBonus = calculatePassedPawnBonus(blackPawns, whitePawns, allPieces, blackKing, false);
    }

    public void updateCenterPawnBonusWhite(long whitePawns) {
        whiteCenterPawnBonus = countCenterPawns(whitePawns) * CENTER_PAWN_BONUS;
    }

    public void updateCenterPawnBonusBlack(long blackPawns) {
        blackCenterPawnBonus = countCenterPawns(blackPawns) * CENTER_PAWN_BONUS;
    }

    private int calculatePawnAdvanceBonus(long pawns, long allPieces, long enemyAttacks, boolean isWhite, int phase) {
        int bonus = 0;
        long advancedRanks = isWhite
                ? (RankMasks[3] | RankMasks[4] | RankMasks[5] | RankMasks[6])
                : (RankMasks[4] | RankMasks[3] | RankMasks[2] | RankMasks[1]);
        long advancedPawns = pawns & advancedRanks;
        while (advancedPawns != 0) {
            int square = Long.numberOfTrailingZeros(advancedPawns);
            long forward = PAWN_PUSHES[isWhite ? 0 : 1][square];
            if ((forward & (allPieces | enemyAttacks)) == 0) {
                int rank = square / 8 + 1;
                int rankBonus = isWhite ? (rank - 3) : (6 - rank);
                bonus += ADVANCED_PAWN_BONUS * rankBonus;
            }
            advancedPawns &= advancedPawns - 1;
        }
        return bonus * (256 + phase) / 256;
    }

    private int calculateBlockedPawnPenalty(long pawns, long allPieces, boolean isWhite, int phase) {
        int penalty = 0;
        long remaining = pawns;
        while (remaining != 0) {
            int square = Long.numberOfTrailingZeros(remaining);
            long forward = PAWN_PUSHES[isWhite ? 0 : 1][square];
            if ((forward & allPieces) != 0) {
                penalty += BLOCKED_PAWN_PENALTY;
            }
            remaining &= remaining - 1;
        }
        return penalty * (256 + phase) / 256;
    }

    private int calculateBackwardPawnPenalty(long pawns, long enemyPawns, long allPieces, boolean isWhite, int phase) {
        int penalty = 0;
        long remaining = pawns;
        while (remaining != 0) {
            int square = Long.numberOfTrailingZeros(remaining);
            long forward = PAWN_PUSHES[isWhite ? 0 : 1][square];
            if ((forward & allPieces) == 0) {
                int forwardIndex = Long.numberOfTrailingZeros(forward);
                long enemyAttack = PAWN_ATTACKS[isWhite ? 1 : 0][forwardIndex] & enemyPawns;
                if (enemyAttack != 0) {
                    penalty += BACKWARD_PAWN_PENALTY;
                }
            }
            remaining &= remaining - 1;
        }
        return penalty * (256 + phase) / 256;
    }

    // FIX: new version considers own king blocking the file, and rewards a clear file to promotion
    private int calculatePassedPawnBonus(long pawns, long opponentPawns, long allPieces, long ownKing, boolean isWhite) {
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
            if (file > 0)  adjFilesMask |= FileMasks[file - 1];
            if (file < 7)  adjFilesMask |= FileMasks[file + 1];

            // Ranks forward (all squares ahead)
            long forwardRanksMask = 0L;
            if (isWhite) {
                for (int r = rank + 1; r <= 8; r++) forwardRanksMask |= RankMasks[r - 1];
            } else {
                for (int r = rank - 1; r >= 1; r--) forwardRanksMask |= RankMasks[r - 1];
            }

            // Passed definition: no enemy pawn ahead on same or adjacent files
            boolean isPassed = (opponentPawns & (adjFilesMask & forwardRanksMask)) == 0;
            if (!isPassed) continue;

            // Base bonus grows with advancement (kept from your logic)
            int base = PASSED_PAWN_BONUS * (isWhite ? (rank - 1) : (8 - rank));

            // Path squares on the same file ahead
            long filePathAhead = fileMask & forwardRanksMask;

            // One-step forward square (for immediate self-block check)
            long oneStep = isWhite ? (pawn << 8) : (pawn >>> 8);

            // If own king sits directly in front, penalize hard (this is the common issue you observed)
            if ((oneStep & ownKing) != 0L) {
                base += OWN_KING_BLOCKS_PASSED_PAWN_PENALTY; // strong nudge to move the king off the file
            }

            // If the entire file to promotion is clear (no pieces at all), add a small "free runway" bonus
            // This slightly prefers stepping aside or clearing the path to start pushing.
            if ((filePathAhead & allPieces) == 0L) {
                int distToPromo = isWhite ? (8 - rank) : (rank - 1);
                base += PASSED_PAWN_FREE_PATH_BONUS_PER_RANK * distToPromo;
            }

            bonus += base;
        }

        return bonus;
    }

    public void updateRookHalfOpenFileBonusWhite(long whiteRooks, long whitePawns, long blackPawns) {
        whiteRooksHalfOpenFileBonus = countRooksOnHalfOpenFiles(whiteRooks, whitePawns, blackPawns) * ROOK_HALF_OPEN_FILE_BONUS;
    }

    public void updateRookHalfOpenFileBonusBlack(long blackRooks, long blackPawns, long whitePawns) {
        blackRooksHalfOpenFileBonus = countRooksOnHalfOpenFiles(blackRooks, blackPawns, whitePawns) * ROOK_HALF_OPEN_FILE_BONUS;
    }

    public void updateRookOpenFileBonusWhite(long whiteRooks, long allPawns) {
        whiteRooksOpenFileBonus = countRooksOnOpenFiles(whiteRooks, allPawns) * ROOK_OPEN_FILE_BONUS;
    }

    public void updateRookOpenFileBonusBlack(long blackRooks, long allPawns) {
        blackRooksOpenFileBonus = countRooksOnOpenFiles(blackRooks, allPawns) * ROOK_OPEN_FILE_BONUS;
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

    /**
     * Positional Bonuses
     */

    public void updatePawnsPositionBonusWhite(long whitePawns, int phase) {
        whitePawnsPosition = applyPositionalValues(whitePawns, WHITE_PAWN_MIDGAME_POSITIONAL_VALUES, WHITE_PAWN_ENDGAME_POSITIONAL_VALUES, phase);
    }

    public void updatePawnsPositionBonusBlack(long blackPawns, int phase) {
        blackPawnsPosition = applyPositionalValues(blackPawns, BLACK_PAWN_MIDGAME_POSITIONAL_VALUES, BLACK_PAWN_ENDGAME_POSITIONAL_VALUES, phase);
    }

    public void updateKnightsPositionBonusWhite(long whiteKnights, int phase) {
        whiteKnightsPosition = applyPositionalValues(whiteKnights, KNIGHT_MIDGAME_POSITIONAL_VALUES, KNIGHT_ENDGAME_POSITIONAL_VALUES, phase);
    }

    public void updateKnightsPositionBonusBlack(long blackKnights, int phase) {
        blackKnightsPosition = applyPositionalValues(blackKnights, KNIGHT_MIDGAME_POSITIONAL_VALUES, KNIGHT_ENDGAME_POSITIONAL_VALUES, phase);
    }

    public void updateBishopsPositionBonusWhite(long whiteBishops, int phase) {
        whiteBishopsPosition = applyPositionalValues(whiteBishops, BISHOP_MIDGAME_POSITIONAL_VALUES, BISHOP_ENDGAME_POSITIONAL_VALUES, phase);
    }

    public void updateBishopsPositionBonusBlack(long blackBishops, int phase) {
        blackBishopsPosition = applyPositionalValues(blackBishops, BISHOP_MIDGAME_POSITIONAL_VALUES, BISHOP_ENDGAME_POSITIONAL_VALUES, phase);
    }

    public void updateRooksPositionBonusWhite(long whiteRooks, int phase) {
        whiteRooksPosition = applyPositionalValues(whiteRooks, WHITE_ROOK_MIDGAME_POSITIONAL_VALUES, WHITE_ROOK_ENDGAME_POSITIONAL_VALUES, phase);
    }

    public void updateRooksPositionBonusBlack(long blackRooks, int phase) {
        blackRooksPosition = applyPositionalValues(blackRooks, BLACK_ROOK_MIDGAME_POSITIONAL_VALUES, BLACK_ROOK_ENDGAME_POSITIONAL_VALUES, phase);
    }

    public void updateQueensPositionBonusWhite(long whiteQueens, int phase) {
        whiteQueensPosition = applyPositionalValues(whiteQueens, QUEEN_MIDGAME_POSITIONAL_VALUES, QUEEN_ENDGAME_POSITIONAL_VALUES, phase);
    }

    public void updateQueensPositionBonusBlack(long blackQueens, int phase) {
        blackQueensPosition = applyPositionalValues(blackQueens, QUEEN_MIDGAME_POSITIONAL_VALUES, QUEEN_ENDGAME_POSITIONAL_VALUES, phase);
    }

    public void updateWhiteKingsPositionBonus(long whiteKing, long whitePawns, long blackPawns,
                                              boolean isCastled, boolean isWhiteKingMoved,
                                              boolean rookA1Moved, boolean rookH1Moved, int phase) {
        whiteKingsPosition = applyPositionalValues(whiteKing, WHITE_KING_POSITIONAL_VALUES,
                KING_ENDGAME_POSITIONAL_VALUES, phase);

        int castlingBonus = CASTLING_BONUS * (256 - phase) / 256;
        int rookMovePenalty = NOT_CASTLED_AND_ROOK_MOVE_PENALTY * (256 - phase) / 256;

        int materialBalance = (whitePawnsAmountScore + whiteKnightsAmountScore + whiteBishopsAmountScore
                + whiteRooksAmountScore + whiteQueensAmountScore)
                - (blackPawnsAmountScore + blackKnightsAmountScore + blackBishopsAmountScore
                + blackRooksAmountScore + blackQueensAmountScore);
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
            whiteKingsPosition += castlingBonus;
        } else if (applyPenalty) {
            if (rookA1Moved) {
                whiteKingsPosition += rookMovePenalty;
            }
            if (rookH1Moved) {
                whiteKingsPosition += rookMovePenalty;
            }
            if (isWhiteKingMoved) {
                whiteKingsPosition += rookMovePenalty * 2;
            }
        }
    }

    public void updateBlackKingsPositionBonus(long blackKing, long blackPawns, long whitePawns,
                                              boolean isCastled, boolean isBlackKingMoved,
                                              boolean rookA8Moved, boolean rookH8Moved, int phase) {
        blackKingsPosition = applyPositionalValues(blackKing, BLACK_KING_POSITIONAL_VALUES,
                KING_ENDGAME_POSITIONAL_VALUES, phase);

        int castlingBonus = CASTLING_BONUS * (256 - phase) / 256;
        int rookMovePenalty = NOT_CASTLED_AND_ROOK_MOVE_PENALTY * (256 - phase) / 256;

        int materialBalance = (whitePawnsAmountScore + whiteKnightsAmountScore + whiteBishopsAmountScore
                + whiteRooksAmountScore + whiteQueensAmountScore)
                - (blackPawnsAmountScore + blackKnightsAmountScore + blackBishopsAmountScore
                + blackRooksAmountScore + blackQueensAmountScore);
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
            blackKingsPosition += castlingBonus;
        } else if (applyPenalty) {
            if (rookA8Moved) {
                blackKingsPosition += rookMovePenalty;
            }
            if (rookH8Moved) {
                blackKingsPosition += rookMovePenalty;
            }
            if (isBlackKingMoved) {
                blackKingsPosition += rookMovePenalty * 2;
            }
        }
    }

    public void updateStartingSquarePenaltyWhite(long whiteKnights, long whiteBishops, long whiteRooks) {
        // Check if white pieces are all on starting squares
        if (areAllPiecesOnStartingSquares(whiteKnights, whiteBishops, whiteRooks, true)) {
            whiteStartingSquarePenalty = START_POSITION_PENALTY;
        } else {
            whiteStartingSquarePenalty = 0;
        }
    }

    public void updateStartingSquarePenaltyBlack(long blackKnights, long blackBishops, long blackRooks) {
        // Check if white pieces are all on starting squares
        if (areAllPiecesOnStartingSquares(blackKnights, blackBishops, blackRooks, false)) {
            blackStartingSquarePenalty = START_POSITION_PENALTY;
        } else {
            blackStartingSquarePenalty = 0;
        }
    }

    private void updateMinorDevelopmentPenaltyWhite(long whiteKnights, long whiteBishops, int phase) {
        if (phase >= DEVELOPMENT_PHASE_THRESHOLD) {
            int undeveloped = Long.bitCount(whiteKnights & INITIAL_WHITE_KNIGHT_POSITION)
                    + Long.bitCount(whiteBishops & INITIAL_WHITE_BISHOP_POSITION);
            whiteMinorDevelopmentPenalty = undeveloped * UNDEVELOPED_MINOR_PENALTY;
        } else {
            whiteMinorDevelopmentPenalty = 0;
        }
    }

    private void updateMinorDevelopmentPenaltyBlack(long blackKnights, long blackBishops, int phase) {
        if (phase >= DEVELOPMENT_PHASE_THRESHOLD) {
            int undeveloped = Long.bitCount(blackKnights & INITIAL_BLACK_KNIGHT_POSITION)
                    + Long.bitCount(blackBishops & INITIAL_BLACK_BISHOP_POSITION);
            blackMinorDevelopmentPenalty = undeveloped * UNDEVELOPED_MINOR_PENALTY;
        } else {
            blackMinorDevelopmentPenalty = 0;
        }
    }

    private void updateQueenDevelopmentPenaltyWhite(long whiteQueens, long whiteKnights, long whiteBishops, int phase) {
        if (phase <= QUEEN_DEVELOPMENT_PHASE_THRESHOLD
                && Long.bitCount(whiteQueens) > 0
                && (whiteQueens & INITIAL_WHITE_QUEEN_POSITION) == 0) {
            int undevelopedMinors = Long.bitCount(whiteKnights & INITIAL_WHITE_KNIGHT_POSITION)
                    + Long.bitCount(whiteBishops & INITIAL_WHITE_BISHOP_POSITION);
            if (undevelopedMinors >= MIN_UNDEVELOPED_MINORS_FOR_QUEEN_PENALTY) {
                whiteQueenDevelopmentPenalty = undevelopedMinors * EARLY_QUEEN_DEVELOPMENT_PENALTY_PER_MINOR;
                return;
            }
        }
        whiteQueenDevelopmentPenalty = 0;
    }

    private void updateQueenDevelopmentPenaltyBlack(long blackQueens, long blackKnights, long blackBishops, int phase) {
        if (phase <= QUEEN_DEVELOPMENT_PHASE_THRESHOLD
                && Long.bitCount(blackQueens) > 0
                && (blackQueens & INITIAL_BLACK_QUEEN_POSITION) == 0) {
            int undevelopedMinors = Long.bitCount(blackKnights & INITIAL_BLACK_KNIGHT_POSITION)
                    + Long.bitCount(blackBishops & INITIAL_BLACK_BISHOP_POSITION);
            if (undevelopedMinors >= MIN_UNDEVELOPED_MINORS_FOR_QUEEN_PENALTY) {
                blackQueenDevelopmentPenalty = undevelopedMinors * EARLY_QUEEN_DEVELOPMENT_PENALTY_PER_MINOR;
                return;
            }
        }
        blackQueenDevelopmentPenalty = 0;
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

    private int evaluateQueenSafety(long queenBits, long enemyAttacks) {
        if (queenBits == 0) return 0;
        int qSq = Long.numberOfTrailingZeros(queenBits);
        return ((enemyAttacks & (1L << qSq)) != 0) ? QUEEN_ATTACKED_PENALTY : 0;
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
        boolean isEndgame = bitBoard.isEndgame();
        long whiteKing = bitBoard.getWhiteKing();
        long blackKing = bitBoard.getBlackKing();
        long whitePawns = bitBoard.getWhitePawns();
        long blackPawns = bitBoard.getBlackPawns();

        long whiteAttacks = bitBoard.generateAttackBitboard(true);
        long blackAttacks = bitBoard.generateAttackBitboard(false);
        long allPieces = bitBoard.getAllPieces();
        long whiteKnights = bitBoard.getWhiteKnights();
        long whiteBishops = bitBoard.getWhiteBishops();
        long whiteRooks = bitBoard.getWhiteRooks();
        long whiteQueens = bitBoard.getWhiteQueens();
        long blackKnights = bitBoard.getBlackKnights();
        long blackBishops = bitBoard.getBlackBishops();
        long blackRooks = bitBoard.getBlackRooks();
        long blackQueens = bitBoard.getBlackQueens();

        PieceAttackMaps blackAttackMaps = computePieceAttackMaps(
                blackPawns,
                false,
                blackKnights,
                blackBishops,
                blackRooks,
                blackQueens,
                allPieces);
        PieceAttackMaps whiteAttackMaps = computePieceAttackMaps(
                whitePawns,
                true,
                whiteKnights,
                whiteBishops,
                whiteRooks,
                whiteQueens,
                allPieces);

        whiteKingSafetyScore = evaluateKingSafety(
                whiteKing,
                whitePawns,
                blackPawns,
                blackAttackMaps.pawnAttacks,
                blackAttackMaps.knightAttacks,
                blackAttackMaps.bishopAttacks,
                blackAttackMaps.rookAttacks,
                blackAttackMaps.queenAttacks,
                whiteAttacks,
                true,
                isEndgame);
        blackKingSafetyScore = evaluateKingSafety(
                blackKing,
                blackPawns,
                whitePawns,
                whiteAttackMaps.pawnAttacks,
                whiteAttackMaps.knightAttacks,
                whiteAttackMaps.bishopAttacks,
                whiteAttackMaps.rookAttacks,
                whiteAttackMaps.queenAttacks,
                blackAttacks,
                false,
                isEndgame);

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

        // --- NEW: queen safety (simple "queen is attacked" penalty) ---
        whiteQueenSafetyScore = evaluateQueenSafety(whiteQueens, blackAttacks);
        blackQueenSafetyScore = evaluateQueenSafety(blackQueens, whiteAttacks);
    }

    private PieceAttackMaps computePieceAttackMaps(long pawns, boolean pawnsWhite,
                                                   long knights, long bishops, long rooks, long queens,
                                                   long occupancy) {
        return new PieceAttackMaps(
                computePawnAttackBitboards(pawns, pawnsWhite),
                computeKnightAttackBitboards(knights),
                computeBishopAttackBitboards(bishops, occupancy),
                computeRookAttackBitboards(rooks, occupancy),
                computeQueenAttackBitboards(queens, occupancy)
        );
    }

    private long[] computePawnAttackBitboards(long pawns, boolean isWhite) {
        if (pawns == 0) {
            return EMPTY_LONG_ARRAY;
        }
        int colorIndex = isWhite ? 0 : 1;
        long[] attacks = new long[Long.bitCount(pawns)];
        int cursor = 0;
        long remaining = pawns;
        while (remaining != 0) {
            long sq = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(sq);
            attacks[cursor++] = PAWN_ATTACKS[colorIndex][index];
            remaining ^= sq;
        }
        return attacks;
    }

    private long[] computeKnightAttackBitboards(long knights) {
        if (knights == 0) {
            return EMPTY_LONG_ARRAY;
        }
        long[] attacks = new long[Long.bitCount(knights)];
        int cursor = 0;
        long remaining = knights;
        while (remaining != 0) {
            long sq = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(sq);
            attacks[cursor++] = knightMoveTable[index];
            remaining ^= sq;
        }
        return attacks;
    }

    private long[] computeBishopAttackBitboards(long bishops, long occupancy) {
        if (bishops == 0) {
            return EMPTY_LONG_ARRAY;
        }
        long[] attacks = new long[Long.bitCount(bishops)];
        int cursor = 0;
        long remaining = bishops;
        while (remaining != 0) {
            long sq = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(sq);
            long mask = BISHOP_HELPER.bishopMasks[index];
            attacks[cursor++] = BISHOP_HELPER.calculateMovesUsingBishopMagic(index, occupancy & mask);
            remaining ^= sq;
        }
        return attacks;
    }

    private long[] computeRookAttackBitboards(long rooks, long occupancy) {
        if (rooks == 0) {
            return EMPTY_LONG_ARRAY;
        }
        long[] attacks = new long[Long.bitCount(rooks)];
        int cursor = 0;
        long remaining = rooks;
        while (remaining != 0) {
            long sq = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(sq);
            long mask = ROOK_HELPER.rookMasks[index];
            attacks[cursor++] = ROOK_HELPER.calculateMovesUsingRookMagic(index, occupancy & mask);
            remaining ^= sq;
        }
        return attacks;
    }

    private long[] computeQueenAttackBitboards(long queens, long occupancy) {
        if (queens == 0) {
            return EMPTY_LONG_ARRAY;
        }
        long[] attacks = new long[Long.bitCount(queens)];
        int cursor = 0;
        long remaining = queens;
        while (remaining != 0) {
            long sq = remaining & -remaining;
            int index = Long.numberOfTrailingZeros(sq);
            long bishopMask = BISHOP_HELPER.bishopMasks[index];
            long rookMask = ROOK_HELPER.rookMasks[index];
            long diagonal = BISHOP_HELPER.calculateMovesUsingBishopMagic(index, occupancy & bishopMask);
            long straight = ROOK_HELPER.calculateMovesUsingRookMagic(index, occupancy & rookMask);
            attacks[cursor++] = diagonal | straight;
            remaining ^= sq;
        }
        return attacks;
    }

    private int countAttacksInZone(long[] attackBitboards, long zone) {
        int count = 0;
        for (long attacks : attackBitboards) {
            count += Long.bitCount(attacks & zone);
        }
        return count;
    }

    private static final class PieceAttackMaps {
        final long[] pawnAttacks;
        final long[] knightAttacks;
        final long[] bishopAttacks;
        final long[] rookAttacks;
        final long[] queenAttacks;

        PieceAttackMaps(long[] pawnAttacks, long[] knightAttacks, long[] bishopAttacks,
                        long[] rookAttacks, long[] queenAttacks) {
            this.pawnAttacks = pawnAttacks;
            this.knightAttacks = knightAttacks;
            this.bishopAttacks = bishopAttacks;
            this.rookAttacks = rookAttacks;
            this.queenAttacks = queenAttacks;
        }
    }

    private int evaluateKingSafety(long king, long friendlyPawns, long enemyPawns,
                                   long[] enemyPawnAttacks, long[] enemyKnightAttacks,
                                   long[] enemyBishopAttacks, long[] enemyRookAttacks, long[] enemyQueenAttacks,
                                   long friendlyAttacks,
                                   boolean isWhite, boolean isEndgame) {
        if (king == 0) {
            return 0;
        }
        int kingIndex = Long.numberOfTrailingZeros(king);
        long forwardMask = 0L;
        if (isWhite) {
            forwardMask |= (king << 8);
            if ((king & NOT_A_FILE) != 0) forwardMask |= (king << 7);
            if ((king & NOT_H_FILE) != 0) forwardMask |= (king << 9);
        } else {
            forwardMask |= (king >>> 8);
            if ((king & NOT_A_FILE) != 0) forwardMask |= (king >>> 9);
            if ((king & NOT_H_FILE) != 0) forwardMask |= (king >>> 7);
        }

        int missing = 3 - Long.bitCount(friendlyPawns & forwardMask);
        int shieldPenalty = missing * MISSING_PAWN_SHIELD_PENALTY;

        int fileIndex = kingIndex % 8;
        long fileMask = FileMasks[fileIndex];
        int filePenalty = 0;
        if ((friendlyPawns & fileMask) == 0) {
            filePenalty = (enemyPawns & fileMask) != 0 ? HALF_OPEN_FILE_PENALTY : OPEN_FILE_PENALTY;
        }

        long kingZone = KING_ATTACKS[kingIndex];
        int pawnAttackCount = countAttacksInZone(enemyPawnAttacks, kingZone);
        int knightAttackCount = countAttacksInZone(enemyKnightAttacks, kingZone);
        int bishopAttackCount = countAttacksInZone(enemyBishopAttacks, kingZone);
        int rookAttackCount = countAttacksInZone(enemyRookAttacks, kingZone);
        int queenAttackCount = countAttacksInZone(enemyQueenAttacks, kingZone);

        int attackPenalty = pawnAttackCount * PAWN_ATTACK_PENALTY
                + knightAttackCount * KNIGHT_ATTACK_PENALTY
                + bishopAttackCount * BISHOP_ATTACK_PENALTY
                + rookAttackCount * ROOK_ATTACK_PENALTY
                + queenAttackCount * QUEEN_ATTACK_PENALTY;

        int defenders = Long.bitCount(friendlyAttacks & kingZone);
        int defenderBonus = defenders * DEFENDER_BONUS;

        int total = shieldPenalty + filePenalty + attackPenalty + defenderBonus;
        if (isEndgame) {
            total /= 2;
        }
        return total;
    }

    private int applyPositionalValues(long bitboard, int[] midgameValues, int[] endgameValues, int phase) {
        int score = 0;
        while (bitboard != 0) {
            long sq = bitboard & -bitboard;
            int index = Long.numberOfTrailingZeros(sq);
            int mg = midgameValues[index];
            int eg = endgameValues[index];
            score += ((mg * (256 - phase)) + (eg * phase)) / 256;
            bitboard ^= sq;
        }
        return score;
    }

    private boolean areAllPiecesOnStartingSquares(long knights, long bishops, long rooks, boolean isWhite) {
        if (isWhite) {
            return (knights == INITIAL_WHITE_KNIGHT_POSITION &&
                    bishops == INITIAL_WHITE_BISHOP_POSITION &&
                    rooks == INITIAL_WHITE_ROOK_POSITION);
        } else {
            return (knights == INITIAL_BLACK_KNIGHT_POSITION &&
                    bishops == INITIAL_BLACK_BISHOP_POSITION &&
                    rooks == INITIAL_BLACK_ROOK_POSITION);
        }
    }

    public void updateWhitePawnValues(BitBoard bitBoard) {
        long whitePawns = bitBoard.getWhitePawns();
        long blackPawns = bitBoard.getBlackPawns();
        long allPieces = bitBoard.getAllPieces();
        long blackAttacks = bitBoard.generateAttackBitboard(false);
        long whiteKing = bitBoard.getWhiteKing();
        int phase = bitBoard.getPhase();

        this.whitePawnsAmountScore = Long.bitCount(whitePawns) * PAWN_VALUE;

        PawnStructure ps = getPawnStructure(whitePawns, blackPawns);
        whiteCenterPawnBonus = ps.whiteCenterPawnBonus;
        blackCenterPawnBonus = ps.blackCenterPawnBonus;
        whiteDoubledPawnPenalty = ps.whiteDoubledPawnPenalty;
        blackDoubledPawnPenalty = ps.blackDoubledPawnPenalty;
        whiteIsolatedPawnPenalty = ps.whiteIsolatedPawnPenalty;
        blackIsolatedPawnPenalty = ps.blackIsolatedPawnPenalty;
        whiteConnectedPawnBonus = ps.whiteConnectedPawnBonus;
        blackConnectedPawnBonus = ps.blackConnectedPawnBonus;
        whitePawnIslandPenalty = ps.whitePawnIslandPenalty;
        blackPawnIslandPenalty = ps.blackPawnIslandPenalty;

        updatePawnsPositionBonusWhite(whitePawns, phase);

        // FIX: pass allPieces + own king
        updatePassedPawnBonusWhite(whitePawns, blackPawns, allPieces, whiteKing);

        whitePawnAdvanceBonus = calculatePawnAdvanceBonus(whitePawns, allPieces, blackAttacks, true, phase);
        whiteBlockedPawnPenalty = calculateBlockedPawnPenalty(whitePawns, allPieces, true, phase);
        whiteBackwardPawnPenalty = calculateBackwardPawnPenalty(whitePawns, blackPawns, allPieces, true, phase);

        //check if Rook is now on an HalfOpen/Open File
        updateBlackRookValues(bitBoard);
        updateWhiteRookValues(bitBoard);
        updateWhiteKnightValues(bitBoard);
        updateBlackKnightValues(bitBoard);
    }

    public void updateBlackPawnValues(BitBoard bitBoard) {
        long blackPawns = bitBoard.getBlackPawns();
        long whitePawns = bitBoard.getWhitePawns();
        long allPieces = bitBoard.getAllPieces();
        long whiteAttacks = bitBoard.generateAttackBitboard(true);
        long blackKing = bitBoard.getBlackKing();
        int phase = bitBoard.getPhase();

        this.blackPawnsAmountScore = Long.bitCount(blackPawns) * PAWN_VALUE;

        PawnStructure ps = getPawnStructure(whitePawns, blackPawns);
        whiteCenterPawnBonus = ps.whiteCenterPawnBonus;
        blackCenterPawnBonus = ps.blackCenterPawnBonus;
        whiteDoubledPawnPenalty = ps.whiteDoubledPawnPenalty;
        blackDoubledPawnPenalty = ps.blackDoubledPawnPenalty;
        whiteIsolatedPawnPenalty = ps.whiteIsolatedPawnPenalty;
        blackIsolatedPawnPenalty = ps.blackIsolatedPawnPenalty;
        whiteConnectedPawnBonus = ps.whiteConnectedPawnBonus;
        blackConnectedPawnBonus = ps.blackConnectedPawnBonus;
        whitePawnIslandPenalty = ps.whitePawnIslandPenalty;
        blackPawnIslandPenalty = ps.blackPawnIslandPenalty;

        updatePawnsPositionBonusBlack(blackPawns, phase);

        // FIX: pass allPieces + own king
        updatePassedPawnBonusBlack(blackPawns, whitePawns, allPieces, blackKing);

        blackPawnAdvanceBonus = calculatePawnAdvanceBonus(blackPawns, allPieces, whiteAttacks, false, phase);
        blackBlockedPawnPenalty = calculateBlockedPawnPenalty(blackPawns, allPieces, false, phase);
        blackBackwardPawnPenalty = calculateBackwardPawnPenalty(blackPawns, whitePawns, allPieces, false, phase);

        //check if Rook is now on an HalfOpen/Open File
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
        int phase = bitBoard.getPhase();
        this.whiteKnightsAmountScore = Long.bitCount(whiteKnights) * KNIGHT_VALUE;
        updateKnightsPositionBonusWhite(whiteKnights, phase);
        updateKnightOutpostBonusWhite(whiteKnights, whitePawns, blackPawns);
        updateStartingSquarePenaltyWhite(whiteKnights, whiteBishops, whiteRooks);
        updateMinorDevelopmentPenaltyWhite(whiteKnights, whiteBishops, phase);
        updateQueenDevelopmentPenaltyWhite(whiteQueens, whiteKnights, whiteBishops, phase);
    }

    public void updateBlackKnightValues(BitBoard bitBoard) {
        long blackKnights = bitBoard.getBlackKnights();
        long blackBishops = bitBoard.getBlackBishops();
        long blackRooks = bitBoard.getBlackRooks();
        long blackQueens = bitBoard.getBlackQueens();
        long blackPawns = bitBoard.getBlackPawns();
        long whitePawns = bitBoard.getWhitePawns();
        int phase = bitBoard.getPhase();
        this.blackKnightsAmountScore = Long.bitCount(blackKnights) * KNIGHT_VALUE;
        updateKnightsPositionBonusBlack(blackKnights, phase);
        updateKnightOutpostBonusBlack(blackKnights, blackPawns, whitePawns);
        updateStartingSquarePenaltyBlack(blackKnights, blackBishops, blackRooks);
        updateMinorDevelopmentPenaltyBlack(blackKnights, blackBishops, phase);
        updateQueenDevelopmentPenaltyBlack(blackQueens, blackKnights, blackBishops, phase);
    }

    public void updateWhiteBishopValues(BitBoard bitBoard) {
        long whiteBishops = bitBoard.getWhiteBishops();
        long whiteKnights = bitBoard.getWhiteKnights();
        long whiteRooks = bitBoard.getWhiteRooks();
        long whiteQueens = bitBoard.getWhiteQueens();
        int phase = bitBoard.getPhase();
        this.whiteBishopsAmountScore = Long.bitCount(whiteBishops) * BISHOP_VALUE;
        updateBishopsPositionBonusWhite(whiteBishops, phase);
        updateStartingSquarePenaltyWhite(whiteKnights, whiteBishops, whiteRooks);
        whiteBishopPairBonus = Long.bitCount(whiteBishops) == 2 ? BISHOP_PAIR_BONUS : 0;
        updateMinorDevelopmentPenaltyWhite(whiteKnights, whiteBishops, phase);
        updateQueenDevelopmentPenaltyWhite(whiteQueens, whiteKnights, whiteBishops, phase);
    }

    public void updateBlackBishopValues(BitBoard bitBoard) {
        long blackBishops = bitBoard.getBlackBishops();
        long blackKnights = bitBoard.getBlackKnights();
        long blackRooks = bitBoard.getBlackRooks();
        long blackQueens = bitBoard.getBlackQueens();
        int phase = bitBoard.getPhase();
        this.blackBishopsAmountScore = Long.bitCount(blackBishops) * BISHOP_VALUE;
        updateBishopsPositionBonusBlack(blackBishops, phase);
        updateStartingSquarePenaltyBlack(blackKnights, blackBishops, blackRooks);
        blackBishopPairBonus = Long.bitCount(blackBishops) == 2 ? BISHOP_PAIR_BONUS : 0;
        updateMinorDevelopmentPenaltyBlack(blackKnights, blackBishops, phase);
        updateQueenDevelopmentPenaltyBlack(blackQueens, blackKnights, blackBishops, phase);
    }

    public void updateWhiteRookValues(BitBoard bitBoard) {

        long whitePawns = bitBoard.getWhitePawns();
        long blackPawns = bitBoard.getBlackPawns();
        long whiteKing = bitBoard.getWhiteKing();

        long whiteKnights = bitBoard.getWhiteKnights();
        long whiteBishops = bitBoard.getWhiteBishops();
        long whiteRooks = bitBoard.getWhiteRooks();
        long whiteQueens = bitBoard.getWhiteQueens();

        boolean isCastled = bitBoard.isWhiteKingHasCastled();
        boolean isWhiteKingHasMoved = bitBoard.isWhiteKingMoved();
        boolean rookA1Moved = bitBoard.isWhiteRookA1Moved();
        boolean rookH1Moved = bitBoard.isWhiteRookH1Moved();
        int phase = bitBoard.getPhase();


        this.whiteRooksAmountScore = Long.bitCount(whiteRooks) * ROOK_VALUE;

        updateRooksPositionBonusWhite(whiteRooks, phase);

        updateRookHalfOpenFileBonusWhite(whiteRooks, whitePawns, blackPawns);
        updateRookOpenFileBonusWhite(whiteRooks, whitePawns | blackPawns);

        updateStartingSquarePenaltyWhite(whiteKnights, whiteBishops, whiteRooks);
        updateKingValuesWhite(whiteKing, whitePawns, blackPawns, isCastled, isWhiteKingHasMoved, rookA1Moved, rookH1Moved, phase);
        updateMinorDevelopmentPenaltyWhite(whiteKnights, whiteBishops, phase);
        updateQueenDevelopmentPenaltyWhite(whiteQueens, whiteKnights, whiteBishops, phase);
    }

    public void updateBlackRookValues(BitBoard bitBoard) {
        long blackPawns = bitBoard.getBlackPawns();
        long whitePawns = bitBoard.getWhitePawns();
        long blackKing = bitBoard.getBlackKing();

        long blackKnights = bitBoard.getBlackKnights();
        long blackBishops = bitBoard.getBlackBishops();
        long blackRooks = bitBoard.getBlackRooks();
        long blackQueens = bitBoard.getBlackQueens();

        boolean isCastled = bitBoard.isBlackKingHasCastled();
        boolean isBlackKingMoved = bitBoard.isBlackKingMoved();
        boolean rookA8Moved = bitBoard.isBlackRookA8Moved();
        boolean rookH8Moved = bitBoard.isBlackRookH8Moved();
        int phase = bitBoard.getPhase();

        this.blackRooksAmountScore = Long.bitCount(blackRooks) * ROOK_VALUE;

        updateRooksPositionBonusBlack(blackRooks, phase);

        updateRookHalfOpenFileBonusBlack(blackRooks, blackPawns, whitePawns);
        updateRookOpenFileBonusBlack(blackRooks, whitePawns | blackPawns);

        updateStartingSquarePenaltyBlack(blackKnights, blackBishops, blackRooks);
        updateKingValuesBlack(blackKing, blackPawns, whitePawns, isCastled, isBlackKingMoved, rookA8Moved, rookH8Moved, phase);
        updateMinorDevelopmentPenaltyBlack(blackKnights, blackBishops, phase);
        updateQueenDevelopmentPenaltyBlack(blackQueens, blackKnights, blackBishops, phase);
    }

    public void updateWhiteQueenValues(BitBoard bitBoard) {
        long whiteQueens = bitBoard.getWhiteQueens();
        long whiteKnights = bitBoard.getWhiteKnights();
        long whiteBishops = bitBoard.getWhiteBishops();
        int phase = bitBoard.getPhase();
        this.whiteQueensAmountScore = Long.bitCount(whiteQueens) * QUEEN_VALUE;
        updateQueensPositionBonusWhite(whiteQueens, phase);
        updateQueenDevelopmentPenaltyWhite(whiteQueens, whiteKnights, whiteBishops, phase);
        updateMinorDevelopmentPenaltyWhite(whiteKnights, whiteBishops, phase);
    }

    public void updateBlackQueenValues(BitBoard bitBoard) {
        long blackQueens = bitBoard.getBlackQueens();
        long blackKnights = bitBoard.getBlackKnights();
        long blackBishops = bitBoard.getBlackBishops();
        int phase = bitBoard.getPhase();
        this.blackQueensAmountScore = Long.bitCount(blackQueens) * QUEEN_VALUE;
        updateQueensPositionBonusBlack(blackQueens, phase);
        updateQueenDevelopmentPenaltyBlack(blackQueens, blackKnights, blackBishops, phase);
        updateMinorDevelopmentPenaltyBlack(blackKnights, blackBishops, phase);
    }

    public void applyMove(BitBoard bitBoard, int move, GameStateEnum state) {
        resetCachedScoreDifference();
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
        int phase = bitBoard.getPhase();
        switch (pieceTypeBits) {
            case 1 -> updateWhitePawnValues(bitBoard);
            case 2 -> updateWhiteKnightValues(bitBoard);
            case 3 -> updateWhiteBishopValues(bitBoard);
            case 4 -> updateWhiteRookValues(bitBoard);
            case 5 -> updateWhiteQueenValues(bitBoard);
            case 6 -> updateKingValuesWhite(bitBoard.getWhiteKing(), bitBoard.getWhitePawns(),
                    bitBoard.getBlackPawns(), bitBoard.isWhiteKingHasCastled(), bitBoard.isWhiteKingMoved(),
                    bitBoard.isWhiteRookA1Moved(), bitBoard.isWhiteRookH1Moved(), phase);
            default -> {
            }
        }
    }

    private void updateValuesForBlack(int pieceTypeBits, BitBoard bitBoard) {
        int phase = bitBoard.getPhase();
        switch (pieceTypeBits) {
            case 1 -> updateBlackPawnValues(bitBoard);
            case 2 -> updateBlackKnightValues(bitBoard);
            case 3 -> updateBlackBishopValues(bitBoard);
            case 4 -> updateBlackRookValues(bitBoard);
            case 5 -> updateBlackQueenValues(bitBoard);
            case 6 -> updateKingValuesBlack(bitBoard.getBlackKing(), bitBoard.getBlackPawns(),
                    bitBoard.getWhitePawns(), bitBoard.isBlackKingHasCastled(), bitBoard.isBlackKingMoved(),
                    bitBoard.isBlackRookA8Moved(), bitBoard.isBlackRookH8Moved(), phase);
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

    public void updateKingValuesWhite(long whiteKing, long whitePawns, long blackPawns,
                                     boolean isCastled, boolean isWhiteKingMoved,
                                     boolean rookA1Moved, boolean rookH1Moved, int phase) {
        updateWhiteKingsPositionBonus(whiteKing, whitePawns, blackPawns, isCastled,
                isWhiteKingMoved, rookA1Moved, rookH1Moved, phase);
    }

    public void updateKingValuesBlack(long blackKing, long blackPawns, long whitePawns,
                                     boolean isCastled, boolean isBlackKingMoved,
                                     boolean rookA8Moved, boolean rookH8Moved, int phase) {
        updateBlackKingsPositionBonus(blackKing, blackPawns, whitePawns, isCastled,
                isBlackKingMoved, rookA8Moved, rookH8Moved, phase);
    }

    public int getWhiteKingsPosition() {
        return whiteKingsPosition;
    }

    public int getBlackKingsPosition() {
        return blackKingsPosition;
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

    public void resetCachedScoreDifference() {
        this.cachedScoreDifference = null;
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
