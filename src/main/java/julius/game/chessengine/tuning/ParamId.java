package julius.game.chessengine.tuning;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public enum ParamId {
    MATERIAL_PAWN_VALUE("material.pawnValue", 100),
    MATERIAL_KNIGHT_VALUE("material.knightValue", 320),
    MATERIAL_BISHOP_VALUE("material.bishopValue", 330),
    MATERIAL_ROOK_VALUE("material.rookValue", 500),
    MATERIAL_QUEEN_VALUE("material.queenValue", 900),
    MATERIAL_BISHOP_PAIR_BONUS("material.bishopPairBonus", 40),

    PAWN_STRUCTURE_CENTER_PAWN_BONUS("pawnStructure.centerPawnBonus", 15),
    PAWN_STRUCTURE_PASSED_PAWN_BONUS("pawnStructure.passedPawnBonus", 60),
    PAWN_STRUCTURE_CONNECTED_PAWN_BONUS("pawnStructure.connectedPawnBonus", 8),
    PAWN_STRUCTURE_ISLAND_PENALTY("pawnStructure.islandPenalty", -5),
    PAWN_STRUCTURE_DOUBLED_PAWN_PENALTY("pawnStructure.doubledPawnPenalty", -12),
    PAWN_STRUCTURE_ISOLATED_PAWN_PENALTY("pawnStructure.isolatedPawnPenalty", -10),
    PAWN_STRUCTURE_ADVANCED_PAWN_BONUS("pawnStructure.advancedPawnBonus", 8),
    PAWN_STRUCTURE_BLOCKED_PAWN_PENALTY("pawnStructure.blockedPawnPenalty", -10),
    PAWN_STRUCTURE_BACKWARD_PAWN_PENALTY("pawnStructure.backwardPawnPenalty", -12),
    PAWN_STRUCTURE_OWN_KING_BLOCKS_PASSED_PAWN_PENALTY("pawnStructure.ownKingBlocksPassedPawnPenalty", -150),
    PAWN_STRUCTURE_PASSED_PAWN_FREE_PATH_BONUS_PER_RANK("pawnStructure.passedPawnFreePathBonusPerRank", 12),
    PAWN_STRUCTURE_ROOK_HALF_OPEN_FILE_BONUS("pawnStructure.rookHalfOpenFileBonus", 15),
    PAWN_STRUCTURE_ROOK_OPEN_FILE_BONUS("pawnStructure.rookOpenFileBonus", 25),

    ACTIVITY_MIDGAME_MOBILITY_KNIGHT("activity.midgameMobilityKnight", 2),
    ACTIVITY_MIDGAME_MOBILITY_BISHOP("activity.midgameMobilityBishop", 4),
    ACTIVITY_MIDGAME_MOBILITY_ROOK("activity.midgameMobilityRook", 2),
    ACTIVITY_MIDGAME_MOBILITY_QUEEN("activity.midgameMobilityQueen", 1),
    ACTIVITY_MIDGAME_MOBILITY_KING("activity.midgameMobilityKing", 0),
    ACTIVITY_ENDGAME_MOBILITY_KNIGHT("activity.endgameMobilityKnight", 2),
    ACTIVITY_ENDGAME_MOBILITY_BISHOP("activity.endgameMobilityBishop", 4),
    ACTIVITY_ENDGAME_MOBILITY_ROOK("activity.endgameMobilityRook", 2),
    ACTIVITY_ENDGAME_MOBILITY_QUEEN("activity.endgameMobilityQueen", 1),
    ACTIVITY_ENDGAME_MOBILITY_KING("activity.endgameMobilityKing", 2),
    ACTIVITY_MIDGAME_CENTER_KNIGHT("activity.midgameCenterKnight", 3),
    ACTIVITY_MIDGAME_CENTER_BISHOP("activity.midgameCenterBishop", 3),
    ACTIVITY_MIDGAME_CENTER_ROOK("activity.midgameCenterRook", 3),
    ACTIVITY_MIDGAME_CENTER_QUEEN("activity.midgameCenterQueen", 3),
    ACTIVITY_MIDGAME_CENTER_KING("activity.midgameCenterKing", 0),
    ACTIVITY_ENDGAME_CENTER_KNIGHT("activity.endgameCenterKnight", 3),
    ACTIVITY_ENDGAME_CENTER_BISHOP("activity.endgameCenterBishop", 3),
    ACTIVITY_ENDGAME_CENTER_ROOK("activity.endgameCenterRook", 3),
    ACTIVITY_ENDGAME_CENTER_QUEEN("activity.endgameCenterQueen", 3),
    ACTIVITY_ENDGAME_CENTER_KING("activity.endgameCenterKing", 2),

    KING_SAFETY_MISSING_PAWN_SHIELD_PENALTY("kingSafety.missingPawnShieldPenalty", -15),
    KING_SAFETY_HALF_OPEN_FILE_PENALTY("kingSafety.halfOpenFilePenalty", -15),
    KING_SAFETY_OPEN_FILE_PENALTY("kingSafety.openFilePenalty", -25),
    KING_SAFETY_DEFENDER_BONUS("kingSafety.defenderBonus", 5),
    KING_SAFETY_QUEEN_ATTACKED_PENALTY("kingSafety.queenAttackedPenalty", -75),
    KING_SAFETY_BACKRANK_WEAKNESS_MIDGAME_PENALTY("kingSafety.backrankWeaknessMidgamePenalty", -100),
    KING_SAFETY_BACKRANK_WEAKNESS_ENDGAME_PENALTY("kingSafety.backrankWeaknessEndgamePenalty", -50),
    KING_SAFETY_ATTACK_WEIGHT_PAWN("kingSafety.attackWeightPawn", 5),
    KING_SAFETY_ATTACK_WEIGHT_KNIGHT("kingSafety.attackWeightKnight", 10),
    KING_SAFETY_ATTACK_WEIGHT_BISHOP("kingSafety.attackWeightBishop", 10),
    KING_SAFETY_ATTACK_WEIGHT_ROOK("kingSafety.attackWeightRook", 15),
    KING_SAFETY_ATTACK_WEIGHT_QUEEN("kingSafety.attackWeightQueen", 20),

    THREAT_HANGING_PAWN_PENALTY("threat.hangingPawnPenalty", -12),
    THREAT_HANGING_KNIGHT_PENALTY("threat.hangingKnightPenalty", -30),
    THREAT_HANGING_BISHOP_PENALTY("threat.hangingBishopPenalty", -30),
    THREAT_HANGING_ROOK_PENALTY("threat.hangingRookPenalty", -45),
    THREAT_HANGING_QUEEN_PENALTY("threat.hangingQueenPenalty", -70),
    THREAT_PAWN_THREAT_KNIGHT_PENALTY("threat.pawnThreatKnightPenalty", -10),
    THREAT_PAWN_THREAT_BISHOP_PENALTY("threat.pawnThreatBishopPenalty", -10),
    THREAT_PAWN_THREAT_ROOK_PENALTY("threat.pawnThreatRookPenalty", -18),
    THREAT_PAWN_THREAT_QUEEN_PENALTY("threat.pawnThreatQueenPenalty", -25),

    EVALUATION_BLEND_SCALE("evaluation.blendScale", 256, 1.0, 1024.0),

    MOVE_ORDERING_KILLER_MOVE_SCORE("moveOrdering.killerMoveScore", 10_000),
    MOVE_ORDERING_PROMOTION_BONUS("moveOrdering.promotionBonus", 900),
    MOVE_ORDERING_KILLER0_BONUS("moveOrdering.killer0Bonus", 50),
    MOVE_ORDERING_KILLER1_BONUS("moveOrdering.killer1Bonus", 30),
    MOVE_ORDERING_COUNTER_MOVE_BONUS("moveOrdering.counterMoveBonus", 400),
    MOVE_ORDERING_CAPTURE_MVV_MULTIPLIER("moveOrdering.captureMvvMultiplier", 16),
    MOVE_ORDERING_CAPTURE_SEE_MULTIPLIER("moveOrdering.captureSeeMultiplier", 32),
    MOVE_ORDERING_PROMOTION_SEE_MULTIPLIER("moveOrdering.promotionSeeMultiplier", 16),
    MOVE_ORDERING_QUIET_CENTRALITY_MULTIPLIER("moveOrdering.quietCentralityMultiplier", 24),
    MOVE_ORDERING_QUIET_HISTORY_THRESHOLD("moveOrdering.quietHistoryThreshold", 3200);

    private final String key;
    private final double defaultValue;
    private final Double minValue;
    private final Double maxValue;

    private static final Map<String, ParamId> BY_KEY;

    static {
        Map<String, ParamId> map = new HashMap<>();
        for (ParamId id : values()) {
            String normalized = ParameterNormalizer.normalizeKey(id.key);
            map.put(normalized, id);
        }
        BY_KEY = Collections.unmodifiableMap(map);
    }

    ParamId(String key, double defaultValue) {
        this(key, defaultValue, null, null);
    }

    ParamId(String key, double defaultValue, Double minValue, Double maxValue) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    public String key() {
        return key;
    }

    public double defaultValue() {
        return defaultValue;
    }

    public Double minValue() {
        return minValue;
    }

    public Double maxValue() {
        return maxValue;
    }

    public static ParamId forKey(String key) {
        if (key == null) {
            return null;
        }
        return BY_KEY.get(ParameterNormalizer.normalizeKey(key));
    }
}
