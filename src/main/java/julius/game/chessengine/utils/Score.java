package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.board.MoveHelper;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.RookHelper;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import java.util.Map;
import java.util.HashMap;

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

    private static final int ROOK_HALF_OPEN_FILE_BONUS = 15; // was 25
    private static final int ROOK_OPEN_FILE_BONUS      = 25; // was 35

    // Piece-specific mobility weights
    private static final int KNIGHT_MOBILITY_BONUS = 4;
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
    public static final int BISHOP_PAIR_BONUS = 40;
    private static final int KNIGHT_OUTPOST_BONUS = 30;
    private static final int KNIGHT_OUTPOST_DEFENDED_BONUS = 10;

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


    // Cache for pawn structure evaluations to avoid recomputation
    private static final Map<Long, PawnStructure> pawnStructureCache = new HashMap<>();

    private static class PawnStructure {
        int whiteCenterPawnBonus;
        int blackCenterPawnBonus;
        int whiteDoubledPawnPenalty;
        int blackDoubledPawnPenalty;
        int whiteIsolatedPawnPenalty;
        int blackIsolatedPawnPenalty;
        int whiteConnectedPawnBonus;
        int blackConnectedPawnBonus;
        int whitePawnIslandPenalty;
        int blackPawnIslandPenalty;
    }

    private static long pawnHash(long whitePawns, long blackPawns) {
        return whitePawns ^ (blackPawns << 1);
    }

    private static PawnStructure getPawnStructure(long whitePawns, long blackPawns) {
        long key = pawnHash(whitePawns, blackPawns);
        PawnStructure ps = pawnStructureCache.get(key);
        if (ps == null) {
            ps = new PawnStructure();
            ps.whiteCenterPawnBonus = countCenterPawns(whitePawns) * CENTER_PAWN_BONUS;
            ps.blackCenterPawnBonus = countCenterPawns(blackPawns) * CENTER_PAWN_BONUS;
            ps.whiteDoubledPawnPenalty = countDoubledPawns(whitePawns) * DOUBLED_PAWN_PENALTY;
            ps.blackDoubledPawnPenalty = countDoubledPawns(blackPawns) * DOUBLED_PAWN_PENALTY;
            ps.whiteIsolatedPawnPenalty = countIsolatedPawns(whitePawns) * ISOLATED_PAWN_PENALTY;
            ps.blackIsolatedPawnPenalty = countIsolatedPawns(blackPawns) * ISOLATED_PAWN_PENALTY;
            ps.whiteConnectedPawnBonus = countConnectedPawns(whitePawns) * CONNECTED_PAWN_BONUS;
            ps.blackConnectedPawnBonus = countConnectedPawns(blackPawns) * CONNECTED_PAWN_BONUS;
            ps.whitePawnIslandPenalty = Math.max(0, countPawnIslands(whitePawns) - 1) * PAWN_ISLAND_PENALTY;
            ps.blackPawnIslandPenalty = Math.max(0, countPawnIslands(blackPawns) - 1) * PAWN_ISLAND_PENALTY;
            pawnStructureCache.put(key, ps);
        }
        return ps;
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

        totalWhiteScore += whiteMobilityScore;
        totalWhiteScore += whiteKingSafetyScore;

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

        totalBlackScore += blackMobilityScore;
        totalBlackScore += blackKingSafetyScore;

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

    public void updateMobilityScores(int whiteScore, int blackScore) {
        whiteMobilityScore = whiteScore;
        blackMobilityScore = blackScore;
    }

    public void updateMobilityScores(BitBoard bitBoard) {
        // Generate pseudo-legal moves for mobility estimation. We process
        // each side separately because the move generator reuses an internal
        // buffer for efficiency.
        MoveList moves = bitBoard.generateAllPossibleMoves(true);
        int whiteScore = calculateMobility(moves);

        moves = bitBoard.generateAllPossibleMoves(false);
        int blackScore = calculateMobility(moves);

        updateMobilityScores(whiteScore, blackScore);
    }

    private int calculateMobility(MoveList moves) {
        int score = 0;
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getMove(i);
            switch (MoveHelper.derivePieceTypeBits(move)) {
                case 2:
                    score += KNIGHT_MOBILITY_BONUS;
                    break;
                case 3:
                    score += BISHOP_MOBILITY_BONUS;
                    break;
                case 4:
                    score += ROOK_MOBILITY_BONUS;
                    break;
                case 5:
                    score += QUEEN_MOBILITY_BONUS;
                    break;
                default:
                    break;
            }

            int toIndex = MoveHelper.deriveToIndex(move);
            if (((1L << toIndex) & CENTRAL_SQUARES) != 0) {
                score += CENTER_CONTROL_BONUS;
            }
        }
        return score;
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

    public void updateKingSafety(BitBoard bitBoard) {
        boolean isEndgame = bitBoard.isEndgame();
        long whiteKing = bitBoard.getWhiteKing();
        long blackKing = bitBoard.getBlackKing();
        long whitePawns = bitBoard.getWhitePawns();
        long blackPawns = bitBoard.getBlackPawns();

        long whiteAttacks = bitBoard.generateAttackBitboard(true);
        long blackAttacks = bitBoard.generateAttackBitboard(false);
        long allPieces = bitBoard.getAllPieces();

        whiteKingSafetyScore = evaluateKingSafety(
                whiteKing,
                whitePawns,
                blackPawns,
                bitBoard.getBlackKnights(),
                bitBoard.getBlackBishops(),
                bitBoard.getBlackRooks(),
                bitBoard.getBlackQueens(),
                whiteAttacks,
                allPieces,
                true,
                isEndgame);
        blackKingSafetyScore = evaluateKingSafety(
                blackKing,
                blackPawns,
                whitePawns,
                bitBoard.getWhiteKnights(),
                bitBoard.getWhiteBishops(),
                bitBoard.getWhiteRooks(),
                bitBoard.getWhiteQueens(),
                blackAttacks,
                allPieces,
                false,
                isEndgame);
    }

    private int evaluateKingSafety(long king, long friendlyPawns, long enemyPawns,
                                   long enemyKnights, long enemyBishops, long enemyRooks, long enemyQueens,
                                   long friendlyAttacks, long allPieces,
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
        int enemyColor = isWhite ? 1 : 0;

        int pawnAttackCount = 0;
        long pawns = enemyPawns;
        while (pawns != 0) {
            long sq = pawns & -pawns;
            int idx = Long.numberOfTrailingZeros(sq);
            pawnAttackCount += Long.bitCount(PAWN_ATTACKS[enemyColor][idx] & kingZone);
            pawns ^= sq;
        }

        int knightAttackCount = 0;
        long knights = enemyKnights;
        while (knights != 0) {
            long sq = knights & -knights;
            int idx = Long.numberOfTrailingZeros(sq);
            knightAttackCount += Long.bitCount(knightMoveTable[idx] & kingZone);
            knights ^= sq;
        }

        BishopHelper bishopHelper = BishopHelper.getInstance();
        RookHelper rookHelper = RookHelper.getInstance();

        int bishopAttackCount = 0;
        long bishops = enemyBishops;
        while (bishops != 0) {
            long sq = bishops & -bishops;
            int idx = Long.numberOfTrailingZeros(sq);
            bishopAttackCount += Long.bitCount(bishopHelper.calculateBishopMoves(idx, allPieces) & kingZone);
            bishops ^= sq;
        }

        int rookAttackCount = 0;
        long rooks = enemyRooks;
        while (rooks != 0) {
            long sq = rooks & -rooks;
            int idx = Long.numberOfTrailingZeros(sq);
            rookAttackCount += Long.bitCount(rookHelper.calculateRookMoves(idx, allPieces) & kingZone);
            rooks ^= sq;
        }

        int queenAttackCount = 0;
        long queens = enemyQueens;
        while (queens != 0) {
            long sq = queens & -queens;
            int idx = Long.numberOfTrailingZeros(sq);
            long attacks = bishopHelper.calculateBishopMoves(idx, allPieces) | rookHelper.calculateRookMoves(idx, allPieces);
            queenAttackCount += Long.bitCount(attacks & kingZone);
            queens ^= sq;
        }

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
        long whitePawns = bitBoard.getWhitePawns();
        long blackPawns = bitBoard.getBlackPawns();
        int phase = bitBoard.getPhase();
        this.whiteKnightsAmountScore = Long.bitCount(whiteKnights) * KNIGHT_VALUE;
        updateKnightsPositionBonusWhite(whiteKnights, phase);
        updateKnightOutpostBonusWhite(whiteKnights, whitePawns, blackPawns);
        updateStartingSquarePenaltyWhite(whiteKnights, whiteBishops, whiteRooks);
    }

    public void updateBlackKnightValues(BitBoard bitBoard) {
        long blackKnights = bitBoard.getBlackKnights();
        long blackBishops = bitBoard.getBlackBishops();
        long blackRooks = bitBoard.getBlackRooks();
        long blackPawns = bitBoard.getBlackPawns();
        long whitePawns = bitBoard.getWhitePawns();
        int phase = bitBoard.getPhase();
        this.blackKnightsAmountScore = Long.bitCount(blackKnights) * KNIGHT_VALUE;
        updateKnightsPositionBonusBlack(blackKnights, phase);
        updateKnightOutpostBonusBlack(blackKnights, blackPawns, whitePawns);
        updateStartingSquarePenaltyBlack(blackKnights, blackBishops, blackRooks);
    }

    public void updateWhiteBishopValues(BitBoard bitBoard) {
        long whiteBishops = bitBoard.getWhiteBishops();
        long whiteKnights = bitBoard.getWhiteKnights();
        long whiteRooks = bitBoard.getWhiteRooks();
        int phase = bitBoard.getPhase();
        this.whiteBishopsAmountScore = Long.bitCount(whiteBishops) * BISHOP_VALUE;
        updateBishopsPositionBonusWhite(whiteBishops, phase);
        updateStartingSquarePenaltyWhite(whiteKnights, whiteBishops, whiteRooks);
        whiteBishopPairBonus = Long.bitCount(whiteBishops) == 2 ? BISHOP_PAIR_BONUS : 0;
    }

    public void updateBlackBishopValues(BitBoard bitBoard) {
        long blackBishops = bitBoard.getBlackBishops();
        long blackKnights = bitBoard.getBlackKnights();
        long blackRooks = bitBoard.getBlackRooks();
        int phase = bitBoard.getPhase();
        this.blackBishopsAmountScore = Long.bitCount(blackBishops) * BISHOP_VALUE;
        updateBishopsPositionBonusBlack(blackBishops, phase);
        updateStartingSquarePenaltyBlack(blackKnights, blackBishops, blackRooks);
        blackBishopPairBonus = Long.bitCount(blackBishops) == 2 ? BISHOP_PAIR_BONUS : 0;
    }

    public void updateWhiteRookValues(BitBoard bitBoard) {

        long whitePawns = bitBoard.getWhitePawns();
        long blackPawns = bitBoard.getBlackPawns();
        long whiteKing = bitBoard.getWhiteKing();

        long whiteKnights = bitBoard.getWhiteKnights();
        long whiteBishops = bitBoard.getWhiteBishops();
        long whiteRooks = bitBoard.getWhiteRooks();

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
    }

    public void updateBlackRookValues(BitBoard bitBoard) {
        long blackPawns = bitBoard.getBlackPawns();
        long whitePawns = bitBoard.getWhitePawns();
        long blackKing = bitBoard.getBlackKing();

        long blackKnights = bitBoard.getBlackKnights();
        long blackBishops = bitBoard.getBlackBishops();
        long blackRooks = bitBoard.getBlackRooks();

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
    }

    public void updateWhiteQueenValues(BitBoard bitBoard) {
        long whiteQueens = bitBoard.getWhiteQueens();
        int phase = bitBoard.getPhase();
        this.whiteQueensAmountScore = Long.bitCount(whiteQueens) * QUEEN_VALUE;
        updateQueensPositionBonusWhite(whiteQueens, phase);
    }

    public void updateBlackQueenValues(BitBoard bitBoard) {
        long blackQueens = bitBoard.getBlackQueens();
        int phase = bitBoard.getPhase();
        this.blackQueensAmountScore = Long.bitCount(blackQueens) * QUEEN_VALUE;
        updateQueensPositionBonusBlack(blackQueens, phase);
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
