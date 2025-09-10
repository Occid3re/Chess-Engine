package julius.game.chessengine.utils;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.MoveList;
import julius.game.chessengine.engine.GameStateEnum;
import lombok.Data;
import lombok.extern.log4j.Log4j2;

import static julius.game.chessengine.helper.BishopHelper.BISHOP_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.KingHelper.*;
import static julius.game.chessengine.helper.KnightHelper.KNIGHT_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.PawnHelper.*;
import static julius.game.chessengine.helper.PawnMoveTables.PAWN_ATTACKS;
import static julius.game.chessengine.helper.PawnMoveTables.PAWN_PUSHES;
import static julius.game.chessengine.helper.QueenHelper.QUEEN_POSITIONAL_VALUES;
import static julius.game.chessengine.helper.RookHelper.*;
import static julius.game.chessengine.helper.BitHelper.FileMasks;
import static julius.game.chessengine.helper.BitHelper.RankMasks;

@Data
@Log4j2
public class Score {

    public static final int CHECKMATE = 100000;
    public static final int CHECK = 50;     // was 100; lighter is more stable
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

    // FIX: steer king away from blocking own passers / reward clear paths
    private static final int OWN_KING_BLOCKS_PASSED_PAWN_PENALTY = -150; // big to outweigh PST pull
    private static final int PASSED_PAWN_FREE_PATH_BONUS_PER_RANK = 12;  // small but helps pushing

    // Other bonuses and penalties
    private static final int NOT_CASTLED_AND_ROOK_MOVE_PENALTY = -20; // was -50 (too harsh early)
    private static final int START_POSITION_PENALTY            = -40; // was -50
    private static final int CASTLING_BONUS                    = 40;  // was 75 (more balanced)

    private static final int ROOK_HALF_OPEN_FILE_BONUS = 15; // was 25
    private static final int ROOK_OPEN_FILE_BONUS      = 25; // was 35

    private static final int MOBILITY_BONUS = 5;
    private static final int MISSING_PAWN_SHIELD_PENALTY = -15;
    private static final int KING_ATTACK_PENALTY = -10;
    public static final int BISHOP_PAIR_BONUS = 40;

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
    private int whiteRooksHalfOpenFileBonus = 0;
    private int blackRooksHalfOpenFileBonus = 0;
    private int whiteRooksOpenFileBonus = 0;
    private int blackRooksOpenFileBonus = 0;
    private int whiteBishopPairBonus = 0;
    private int blackBishopPairBonus = 0;

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
        this.whiteRooksHalfOpenFileBonus = other.whiteRooksHalfOpenFileBonus;
        this.blackRooksHalfOpenFileBonus = other.blackRooksHalfOpenFileBonus;
        this.whiteRooksOpenFileBonus = other.whiteRooksOpenFileBonus;
        this.blackRooksOpenFileBonus = other.blackRooksOpenFileBonus;
        this.whiteBishopPairBonus = other.whiteBishopPairBonus;
        this.blackBishopPairBonus = other.blackBishopPairBonus;

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

        // Apply positional values to the pawns
        updatePawnsPositionBonusWhite(whitePawns);
        updatePawnsPositionBonusBlack(blackPawns);

        updateKnightsPositionBonusWhite(whiteKnights);
        updateKnightsPositionBonusBlack(blackKnights);

        updateBishopsPositionBonusWhite(whiteBishops);
        updateBishopsPositionBonusBlack(blackBishops);

        updateRooksPositionBonusWhite(whiteRooks);
        updateRooksPositionBonusBlack(blackRooks);

        updateQueensPositionBonusWhite(whiteQueens);
        updateQueensPositionBonusBlack(blackQueens);

        updateWhiteKingsPositionBonus(whiteKing, bitBoard.isWhiteKingHasCastled(), bitBoard.isWhiteKingMoved(),
                bitBoard.isWhiteRookA1Moved(), bitBoard.isWhiteRookH1Moved(), bitBoard.isEndgame());
        updateBlackKingsPositionBonus(blackKing, bitBoard.isBlackKingHasCastled(), bitBoard.isBlackKingMoved(),
                bitBoard.isBlackRookA8Moved(), bitBoard.isBlackRookH8Moved(), bitBoard.isEndgame());

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

        totalWhiteScore += whiteRooksHalfOpenFileBonus;
        totalWhiteScore += whiteRooksOpenFileBonus;

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

        totalBlackScore += blackRooksHalfOpenFileBonus;
        totalBlackScore += blackRooksOpenFileBonus;

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

    /**
     * Positional Bonuses
     */

    public void updatePawnsPositionBonusWhite(long whitePawns) {
        whitePawnsPosition = applyPositionalValues(whitePawns, WHITE_PAWN_POSITIONAL_VALUES);
    }

    public void updatePawnsPositionBonusBlack(long blackPawns) {
        blackPawnsPosition = applyPositionalValues(blackPawns, BLACK_PAWN_POSITIONAL_VALUES);
    }

    public void updateKnightsPositionBonusWhite(long whiteKnights) {
        whiteKnightsPosition = applyPositionalValues(whiteKnights, KNIGHT_POSITIONAL_VALUES);
    }

    public void updateKnightsPositionBonusBlack(long blackKnights) {
        blackKnightsPosition = applyPositionalValues(blackKnights, KNIGHT_POSITIONAL_VALUES);
    }

    public void updateBishopsPositionBonusWhite(long whiteBishops) {
        whiteBishopsPosition = applyPositionalValues(whiteBishops, BISHOP_POSITIONAL_VALUES);
    }

    public void updateBishopsPositionBonusBlack(long blackBishops) {
        blackBishopsPosition = applyPositionalValues(blackBishops, BISHOP_POSITIONAL_VALUES);
    }

    public void updateRooksPositionBonusWhite(long whiteRooks) {
        whiteRooksPosition = applyPositionalValues(whiteRooks, WHITE_ROOK_POSITIONAL_VALUES);
    }

    public void updateRooksPositionBonusBlack(long blackRooks) {
        blackRooksPosition = applyPositionalValues(blackRooks, BLACK_ROOK_POSITIONAL_VALUES);
    }

    public void updateQueensPositionBonusWhite(long whiteQueens) {
        whiteQueensPosition = applyPositionalValues(whiteQueens, QUEEN_POSITIONAL_VALUES);
    }

    public void updateQueensPositionBonusBlack(long blackQueens) {
        blackQueensPosition = applyPositionalValues(blackQueens, QUEEN_POSITIONAL_VALUES);
    }

    public void updateWhiteKingsPositionBonus(long whiteKing, boolean isCastled, boolean isWhiteKingMoved, boolean rookA1Moved, boolean rookH1Moved, boolean isEndgame) {
        whiteKingsPosition = applyPositionalValues(whiteKing, isEndgame ? KING_ENDGAME_POSITIONAL_VALUES : WHITE_KING_POSITIONAL_VALUES);
        if (isCastled) {
            whiteKingsPosition += CASTLING_BONUS;
        } else {
            if (rookA1Moved) {
                whiteKingsPosition += NOT_CASTLED_AND_ROOK_MOVE_PENALTY;
            }
            if (rookH1Moved) {
                whiteKingsPosition += NOT_CASTLED_AND_ROOK_MOVE_PENALTY;
            }
            if (isWhiteKingMoved) {
                whiteKingsPosition += NOT_CASTLED_AND_ROOK_MOVE_PENALTY * 2;
            }
        }
    }

    public void updateBlackKingsPositionBonus(long blackKing, boolean isCastled, boolean isBlackKingMoved, boolean rookA8Moved, boolean rookH8Moved, boolean isEndgame) {
        blackKingsPosition = applyPositionalValues(blackKing, isEndgame ? KING_ENDGAME_POSITIONAL_VALUES : BLACK_KING_POSITIONAL_VALUES);
        if (isCastled) {
            blackKingsPosition += CASTLING_BONUS;
        } else {
            if (rookA8Moved) {
                blackKingsPosition += NOT_CASTLED_AND_ROOK_MOVE_PENALTY;
            }
            if (rookH8Moved) {
                blackKingsPosition += NOT_CASTLED_AND_ROOK_MOVE_PENALTY;
            }
            if (isBlackKingMoved) {
                blackKingsPosition += NOT_CASTLED_AND_ROOK_MOVE_PENALTY * 2;
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

    public void updateMobilityScores(int movesWhite, int movesBlack) {
        whiteMobilityScore = movesWhite * MOBILITY_BONUS;
        blackMobilityScore = movesBlack * MOBILITY_BONUS;
    }

    public void updateMobilityScores(BitBoard bitBoard) {
        // Count pseudo-legal moves for a fast mobility estimate. This avoids
        // the expensive per-move legality checks that slowed down search.
        int movesWhite = bitBoard.generateAllPossibleMoves(true).size();
        int movesBlack = bitBoard.generateAllPossibleMoves(false).size();
        updateMobilityScores(movesWhite, movesBlack);

        // Detect stalemate by verifying if any legal moves exist. We only
        // search until the first legal move is found, keeping the check cheap.
        if (!hasAnyLegalMove(bitBoard, false) && !bitBoard.isInCheck(false)) {
            whiteStateBonus += CHECK;
        }
        if (!hasAnyLegalMove(bitBoard, true) && !bitBoard.isInCheck(true)) {
            blackStateBonus += CHECK;
        }
    }

    private boolean hasAnyLegalMove(BitBoard board, boolean white) {
        MoveList moves = board.generateAllPossibleMoves(white);
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getMove(i);
            BitBoard copy = new BitBoard(board);
            copy.performMove(move);
            if (!copy.isInCheck(white)) {
                return true;
            }
        }
        return false;
    }

    public void updateKingSafety(BitBoard bitBoard) {
        boolean isEndgame = bitBoard.isEndgame();
        long whiteKing = bitBoard.getWhiteKing();
        long blackKing = bitBoard.getBlackKing();
        long whitePawns = bitBoard.getWhitePawns();
        long blackPawns = bitBoard.getBlackPawns();

        long whiteAttacks = bitBoard.generateAttackBitboard(true);
        long blackAttacks = bitBoard.generateAttackBitboard(false);

        whiteKingSafetyScore = evaluateKingSafety(whiteKing, whitePawns, blackAttacks, true, isEndgame);
        blackKingSafetyScore = evaluateKingSafety(blackKing, blackPawns, whiteAttacks, false, isEndgame);
    }

    private int evaluateKingSafety(long king, long friendlyPawns, long enemyAttacks, boolean isWhite, boolean isEndgame) {
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

        long kingZone = KING_ATTACKS[kingIndex];
        int attacks = Long.bitCount(enemyAttacks & kingZone);
        int attackPenalty = attacks * KING_ATTACK_PENALTY;

        int total = shieldPenalty + attackPenalty;
        if (isEndgame) {
            total /= 2;
        }
        return total;
    }

    private int applyPositionalValues(long bitboard, int[] positionalValues) {
        int score = 0;
        while (bitboard != 0) {
            long sq = bitboard & -bitboard;
            score += positionalValues[Long.numberOfTrailingZeros(sq)];
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
        long allPieces = bitBoard.getAllPieces();
        long blackAttacks = bitBoard.generateAttackBitboard(false);
        long whiteKing = bitBoard.getWhiteKing();
        int phase = bitBoard.getPhase();

        this.whitePawnsAmountScore = Long.bitCount(whitePawns) * PAWN_VALUE;
        updateIsolatedPawnPenaltyWhite(whitePawns);
        updateDoubledPawnPenaltyWhite(whitePawns);
        updatePawnsPositionBonusWhite(whitePawns);
        updateCenterPawnBonusWhite(whitePawns);

        // FIX: pass allPieces + own king
        updatePassedPawnBonusWhite(whitePawns, bitBoard.getBlackPawns(), allPieces, whiteKing);

        whitePawnAdvanceBonus = calculatePawnAdvanceBonus(whitePawns, allPieces, blackAttacks, true, phase);
        whiteBlockedPawnPenalty = calculateBlockedPawnPenalty(whitePawns, allPieces, true, phase);
        whiteBackwardPawnPenalty = calculateBackwardPawnPenalty(whitePawns, bitBoard.getBlackPawns(), allPieces, true, phase);

        //check if Rook is now on an HalfOpen/Open File
        updateBlackRookValues(bitBoard);
        updateWhiteRookValues(bitBoard);
    }

    public void updateBlackPawnValues(BitBoard bitBoard) {
        long blackPawns = bitBoard.getBlackPawns();
        long allPieces = bitBoard.getAllPieces();
        long whiteAttacks = bitBoard.generateAttackBitboard(true);
        long blackKing = bitBoard.getBlackKing();
        int phase = bitBoard.getPhase();

        this.blackPawnsAmountScore = Long.bitCount(blackPawns) * PAWN_VALUE;
        updateIsolatedPawnPenaltyBlack(blackPawns);
        updateDoubledPawnPenaltyBlack(blackPawns);
        updatePawnsPositionBonusBlack(blackPawns);
        updateCenterPawnBonusBlack(blackPawns);

        // FIX: pass allPieces + own king
        updatePassedPawnBonusBlack(blackPawns, bitBoard.getWhitePawns(), allPieces, blackKing);

        blackPawnAdvanceBonus = calculatePawnAdvanceBonus(blackPawns, allPieces, whiteAttacks, false, phase);
        blackBlockedPawnPenalty = calculateBlockedPawnPenalty(blackPawns, allPieces, false, phase);
        blackBackwardPawnPenalty = calculateBackwardPawnPenalty(blackPawns, bitBoard.getWhitePawns(), allPieces, false, phase);

        //check if Rook is now on an HalfOpen/Open File
        updateBlackRookValues(bitBoard);
        updateWhiteRookValues(bitBoard);
    }

    public void updateWhiteKnightValues(long whiteKnights, long whiteBishops, long whiteRooks) {
        this.whiteKnightsAmountScore = Long.bitCount(whiteKnights) * KNIGHT_VALUE;
        updateKnightsPositionBonusWhite(whiteKnights);
        updateStartingSquarePenaltyWhite(whiteKnights, whiteBishops, whiteRooks);
    }

    public void updateBlackKnightValues(long blackKnights, long blackBishops, long blackRooks) {
        this.blackKnightsAmountScore = Long.bitCount(blackKnights) * KNIGHT_VALUE;
        updateKnightsPositionBonusBlack(blackKnights);
        updateStartingSquarePenaltyBlack(blackKnights, blackBishops, blackRooks);
    }

    public void updateWhiteBishopValues(long whiteBishops, long whiteKnights, long whiteRooks) {
        this.whiteBishopsAmountScore = Long.bitCount(whiteBishops) * BISHOP_VALUE;
        updateBishopsPositionBonusWhite(whiteBishops);
        updateStartingSquarePenaltyWhite(whiteKnights, whiteBishops, whiteRooks);
        whiteBishopPairBonus = Long.bitCount(whiteBishops) == 2 ? BISHOP_PAIR_BONUS : 0;
    }

    public void updateBlackBishopValues(long blackBishops, long blackKnights, long blackRooks) {
        this.blackBishopsAmountScore = Long.bitCount(blackBishops) * BISHOP_VALUE;
        updateBishopsPositionBonusBlack(blackBishops);
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
        boolean isEndgame = bitBoard.isEndgame();


        this.whiteRooksAmountScore = Long.bitCount(whiteRooks) * ROOK_VALUE;

        updateRooksPositionBonusWhite(whiteRooks);

        updateRookHalfOpenFileBonusWhite(whiteRooks, whitePawns, blackPawns);
        updateRookOpenFileBonusWhite(whiteRooks, whitePawns | blackPawns);

        updateStartingSquarePenaltyWhite(whiteKnights, whiteBishops, whiteRooks);
        updateKingValuesWhite(whiteKing, isCastled, isWhiteKingHasMoved, rookA1Moved, rookH1Moved, isEndgame);
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
        boolean isEndgame = bitBoard.isEndgame();

        this.blackRooksAmountScore = Long.bitCount(blackRooks) * ROOK_VALUE;

        updateRooksPositionBonusBlack(blackRooks);

        updateRookHalfOpenFileBonusBlack(blackRooks, blackPawns, whitePawns);
        updateRookOpenFileBonusBlack(blackRooks, whitePawns | blackPawns);

        updateStartingSquarePenaltyBlack(blackKnights, blackBishops, blackRooks);
        updateKingValuesBlack(blackKing, isCastled, isBlackKingMoved, rookA8Moved, rookH8Moved, isEndgame);
    }

    public void updateWhiteQueenValues(long whiteQueens) {
        this.whiteQueensAmountScore = Long.bitCount(whiteQueens) * QUEEN_VALUE;
    }

    public void updateBlackQueenValues(long blackQueens) {
        this.blackQueensAmountScore = Long.bitCount(blackQueens) * QUEEN_VALUE;
    }

    public void updateKingValuesWhite(long whiteKing, boolean isCastled, boolean isWhiteKingMoved, boolean rookA1Moved, boolean rookH1Moved, boolean isEndgame) {
        updateWhiteKingsPositionBonus(whiteKing, isCastled, isWhiteKingMoved, rookA1Moved, rookH1Moved, isEndgame);
    }

    public void updateKingValuesBlack(long blackKing, boolean isCastled, boolean isBlackKingMoved, boolean rookA1Moved, boolean rookH1Moved, boolean isEndgame) {
        updateBlackKingsPositionBonus(blackKing, isCastled, isBlackKingMoved, rookA1Moved, rookH1Moved, isEndgame);
    }

    public void updateStateValuesWhite(GameStateEnum state) {
        if (state.equals(GameStateEnum.BLACK_IN_CHECK)) {
            whiteStateBonus = CHECK;
        } else if (state.equals(GameStateEnum.WHITE_WON)) {
            whiteStateBonus = CHECKMATE;
        } else {
            whiteStateBonus = 0;
        }
    }

    public void updateStateValuesBlack(GameStateEnum state) {
        if (state.equals(GameStateEnum.WHITE_IN_CHECK)) {
            blackStateBonus = CHECK;
        } else if (state.equals(GameStateEnum.BLACK_WON)) {
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
