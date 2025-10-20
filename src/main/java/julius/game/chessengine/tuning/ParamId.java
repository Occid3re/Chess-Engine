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
    KING_SAFETY_BACKRANK_COVER_MIDGAME_BONUS("kingSafety.backrankCoverMidgameBonus", 0, -128.0, 128.0),
    KING_SAFETY_BACKRANK_COVER_ENDGAME_BONUS("kingSafety.backrankCoverEndgameBonus", 0, -128.0, 128.0),
    KING_SAFETY_BACKRANK_ATTACK_PENALTY_MIDGAME("kingSafety.backrankAttackPenaltyMidgame", 0, 0.0, 512.0),
    KING_SAFETY_BACKRANK_ATTACK_PENALTY_ENDGAME("kingSafety.backrankAttackPenaltyEndgame", 0, 0.0, 512.0),
    KING_SAFETY_ATTACK_WEIGHT_PAWN("kingSafety.attackWeightPawn", 5),
    KING_SAFETY_ATTACK_WEIGHT_KNIGHT("kingSafety.attackWeightKnight", 10),
    KING_SAFETY_ATTACK_WEIGHT_BISHOP("kingSafety.attackWeightBishop", 10),
    KING_SAFETY_ATTACK_WEIGHT_ROOK("kingSafety.attackWeightRook", 15),
    KING_SAFETY_ATTACK_WEIGHT_QUEEN("kingSafety.attackWeightQueen", 20),
    KING_SAFETY_ATTACK_QUADRATIC_SCALE("kingSafety.attackQuadraticScale", 0, 0.0, 4096.0),

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

    MOVE_ORDERING_CATEGORY_TT("moveOrdering.category.tt", 7, 6.0, 8.0),
    MOVE_ORDERING_CATEGORY_PROMOTION("moveOrdering.category.promotion", 6, 5.0, 7.0),
    MOVE_ORDERING_CATEGORY_CAPTURE_GOOD("moveOrdering.category.captureGood", 5, 4.0, 6.0),
    MOVE_ORDERING_CATEGORY_CAPTURE_EQUAL("moveOrdering.category.captureEqual", 4, 3.0, 5.0),
    MOVE_ORDERING_CATEGORY_KILLER0("moveOrdering.category.killer0", 3, 2.0, 4.0),
    MOVE_ORDERING_CATEGORY_KILLER1("moveOrdering.category.killer1", 2, 1.0, 3.0),
    MOVE_ORDERING_CATEGORY_QUIET("moveOrdering.category.quiet", 1, 0.0, 2.0),
    MOVE_ORDERING_CATEGORY_CAPTURE_BAD("moveOrdering.category.captureBad", 0, -1.0, 1.0),

    MOVE_ORDERING_KILLER_MOVE_SCORE("moveOrdering.killerMoveScore", 10_000),
    MOVE_ORDERING_PROMOTION_BONUS("moveOrdering.promotionBonus", 900),
    MOVE_ORDERING_KILLER0_BONUS("moveOrdering.killer0Bonus", 50),
    MOVE_ORDERING_KILLER1_BONUS("moveOrdering.killer1Bonus", 30),
    MOVE_ORDERING_COUNTER_MOVE_BONUS("moveOrdering.counterMoveBonus", 400),
    MOVE_ORDERING_CAPTURE_MVV_MULTIPLIER("moveOrdering.captureMvvMultiplier", 16),
    MOVE_ORDERING_CAPTURE_SEE_MULTIPLIER("moveOrdering.captureSeeMultiplier", 32),
    MOVE_ORDERING_PROMOTION_SEE_MULTIPLIER("moveOrdering.promotionSeeMultiplier", 16),
    MOVE_ORDERING_CASTLING_BONUS("moveOrdering.castlingBonus", 2000),
    MOVE_ORDERING_CAPTURE_SEE_CLAMP("moveOrdering.captureSeeClamp", 2048, 0.0, 32768.0),
    MOVE_ORDERING_PROMOTION_SEE_CLAMP("moveOrdering.promotionSeeClamp", 512, 0.0, 32768.0),
    MOVE_ORDERING_CAPTURE_GOOD_BONUS("moveOrdering.captureGoodBonus", 0, -8192.0, 8192.0),
    MOVE_ORDERING_CAPTURE_EQUAL_BONUS("moveOrdering.captureEqualBonus", 0, -8192.0, 8192.0),
    MOVE_ORDERING_CAPTURE_BAD_BONUS("moveOrdering.captureBadBonus", 0, -8192.0, 8192.0),
    MOVE_ORDERING_CAPTURE_LOSING_SEE_PENALTY("moveOrdering.captureLosingSeePenalty", 0, 0.0, 4096.0),
    MOVE_ORDERING_QUIET_HISTORY_MULTIPLIER("moveOrdering.quietHistoryMultiplier", 1.0, 0.0, 8.0),
    MOVE_ORDERING_QUIET_HISTORY_BONUS("moveOrdering.quietHistoryBonus", 0, -32768.0, 32768.0),
    MOVE_ORDERING_MAX_SCORE("moveOrdering.maxScore", 0x00FFFFFF, 1024.0, 16777215.0),
    MOVE_ORDERING_HISTORY_SCALE("moveOrdering.historyScale", 1.0, 0.0, null),
    MOVE_ORDERING_HISTORY_DECAY_DIVISOR("moveOrdering.historyDecayDivisor", 2, 1.0, null),

    SEARCH_FP_MARGIN_DEPTH1("search.fpMarginDepth1", 0, 0.0, 4000.0),
    SEARCH_FP_MARGIN_DEPTH2("search.fpMarginDepth2", 0, 0.0, 8000.0),
    SEARCH_LMP_BASE("search.lmpBase", 8, 0.0, 512.0),
    SEARCH_LMP_PER_DEPTH("search.lmpPerDepth", 2, 0.0, 64.0),
    SEARCH_FP_MAX_DEPTH("search.fpMaxDepth", 3, 0.0, 16.0),
    SEARCH_LMP_MAX_DEPTH("search.lmpMaxDepth", 3, 0.0, 16.0),
    SEARCH_HMP_MIN_INDEX("search.hmpMinIndex", -1, -1.0, 512.0),
    SEARCH_HMP_HISTORY_MAX("search.hmpHistoryMax", -1, -32768.0, 32767.0),
    SEARCH_IID_REDUCE_DEPTH("search.iidReduceDepth", 0, 0.0, 8.0),
    SEARCH_LMR_PROTECT_PLY_MAX("search.lmrProtectPlyMax", 1, 0.0, 8.0),
    SEARCH_LMR_PROTECT_INDEX_MAX("search.lmrProtectIndexMax", 2, 0.0, 64.0),
    SEARCH_LMR_CAP_GOOD_QUIET("search.lmrCapGoodQuiet", 63, 0.0, 64.0),
    SEARCH_LMR_HISTORY_BUCKETS("search.lmrHistoryBuckets", 5, 1.0, 16.0),
    SEARCH_LMR_HISTORY_WEIGHT_SLOPE("search.lmrHistoryWeightSlope", 0.5, 0.0, 2.0),
    SEARCH_LMR_SCALE_DIVISOR("search.lmrScaleDivisor", 1.5, 0.1, 8.0),
    SEARCH_LMR_DEPTH_LOG_OFFSET("search.lmrDepthLogOffset", 1.0, 0.0, 8.0),
    SEARCH_LMR_MOVE_LOG_OFFSET("search.lmrMoveLogOffset", 2.0, 0.0, 8.0),
    SEARCH_MAX_CHECK_EXTENSION_STREAK("search.maxCheckExtensionStreak", 2, 0.0, 16.0),
    SEARCH_SEE_PRUNE_NEAR_ROOT_PLY("search.seePruneNearRootPly", 2, 0.0, 16.0),
    SEARCH_HISTORY_REDUCTION_MAX("search.historyReductionMax", 4000, 0.0, 65536.0),

    SEARCH_ASP_MIN_SPAN_CP("search.aspMinSpanCp", 12, 1.0, 2048.0),
    SEARCH_ASP_MAX_SPAN_CP("search.aspMaxSpanCp", 256, 16.0, 4096.0),
    SEARCH_ASP_DEFAULT_SPAN_CP("search.aspDefaultSpanCp", 48, 4.0, 4096.0),
    SEARCH_ASP_HISTORY_BLEND("search.aspHistoryBlend", 0.40, 0.0, 1.0),
    SEARCH_ASP_MOMENTUM_STEP_CP("search.aspMomentumStepCp", 8, 0.0, 256.0),
    SEARCH_ASP_MOMENTUM_CAP("search.aspMomentumCap", 8, 0.0, 64.0),
    SEARCH_ASP_FAILURE_RATIO("search.aspFailureRatio", 0.60, 0.0, 1.0),
    SEARCH_ASP_BASE_OFFSET_CP("search.aspBaseOffsetCp", 24, 0.0, 2048.0),
    SEARCH_ASP_SWING_WEIGHT("search.aspSwingWeight", 0.25, 0.0, 4.0),
    SEARCH_ASP_VOLATILITY_WEIGHT("search.aspVolatilityWeight", 0.60, 0.0, 4.0),
    SEARCH_ASP_DEPTH_SCALE("search.aspDepthScale", 0.04, 0.0, 1.0),
    SEARCH_ASP_DEPTH_PIVOT("search.aspDepthPivot", 3, 0.0, 16.0),
    SEARCH_ASP_FLOOR_BASE_CP("search.aspFloorBaseCp", 8, 0.0, 512.0),
    SEARCH_ASP_FLOOR_VOL_WEIGHT("search.aspFloorVolWeight", 0.5, 0.0, 4.0),
    SEARCH_ASP_FLOOR_STREAK_STEP_CP("search.aspFloorStreakStepCp", 4, 0.0, 128.0),
    SEARCH_ASP_BUMP_BASE_CP("search.aspBumpBaseCp", 12, 0.0, 512.0),
    SEARCH_ASP_BUMP_STREAK_CP("search.aspBumpStreakCp", 6, 0.0, 256.0),
    SEARCH_ASP_BUMP_DEPTH_CP("search.aspBumpDepthCp", 2, 0.0, 128.0),
    SEARCH_ASP_FULL_WINDOW_SCALE("search.aspFullWindowScale", 1.15, 1.0, 4.0),
    SEARCH_ASP_LAST_SPAN_SCALE("search.aspLastSpanScale", 1.10, 1.0, 4.0),
    SEARCH_ASP_FULL_WINDOW_MIN_MULTIPLIER("search.aspFullWindowMinMultiplier", 2.0, 1.0, 8.0),
    SEARCH_ASP_BLEND_BASELINE_WEIGHT("search.aspBlendBaselineWeight", 0.60, 0.0, 1.0),
    SEARCH_ASP_BLEND_CANDIDATE_WEIGHT("search.aspBlendCandidateWeight", 0.40, 0.0, 1.0),
    SEARCH_ASP_MAX_RETRIES_BASE("search.aspMaxRetriesBase", 3, 0.0, 16.0),
    SEARCH_ASP_MAX_RETRIES_VOL_THRESHOLD_HIGH("search.aspMaxRetriesVolThresholdHigh", 120, 0.0, 4096.0),
    SEARCH_ASP_MAX_RETRIES_VOL_BONUS_HIGH("search.aspMaxRetriesVolBonusHigh", 2, 0.0, 16.0),
    SEARCH_ASP_MAX_RETRIES_VOL_THRESHOLD_MED("search.aspMaxRetriesVolThresholdMed", 60, 0.0, 4096.0),
    SEARCH_ASP_MAX_RETRIES_VOL_BONUS_MED("search.aspMaxRetriesVolBonusMed", 1, 0.0, 16.0),
    SEARCH_ASP_MAX_RETRIES_DEPTH_OFFSET("search.aspMaxRetriesDepthOffset", 4, 0.0, 32.0),
    SEARCH_ASP_MAX_RETRIES_DEPTH_DIVISOR("search.aspMaxRetriesDepthDivisor", 2, 1.0, 16.0),
    SEARCH_ASP_MAX_RETRIES_MOMENTUM_DIVISOR("search.aspMaxRetriesMomentumDivisor", 3, 1.0, 16.0),
    SEARCH_ASP_MAX_RETRIES_MIN("search.aspMaxRetriesMin", 3, 0.0, 32.0),
    SEARCH_ASP_MAX_RETRIES_MAX("search.aspMaxRetriesMax", 6, 0.0, 64.0),

    SEARCH_NULL_BASE_REDUCTION("search.nullBaseReduction", 1.25, 0.0, 8.0),
    SEARCH_NULL_DEPTH_WEIGHT("search.nullDepthWeight", 1.5, 0.0, 8.0),
    SEARCH_NULL_MATERIAL_WEIGHT("search.nullMaterialWeight", 0.75, 0.0, 8.0),
    SEARCH_NULL_MOBILITY_WEIGHT("search.nullMobilityWeight", 0.5, 0.0, 8.0),
    SEARCH_NULL_DEPTH_CAP("search.nullDepthCap", 10, 1.0, 64.0),
    SEARCH_NULL_MATERIAL_CAP("search.nullMaterialCap", 12, 1.0, 64.0),
    SEARCH_NULL_MOBILITY_CAP("search.nullMobilityCap", 30, 1.0, 128.0),
    SEARCH_NULL_LOW_MATERIAL_THRESHOLD("search.nullLowMaterialThreshold", 2, 0.0, 16.0),
    SEARCH_NULL_LOW_MOBILITY_THRESHOLD("search.nullLowMobilityThreshold", 4, 0.0, 32.0),
    SEARCH_NULL_VERY_LOW_MOBILITY_THRESHOLD("search.nullVeryLowMobilityThreshold", 2, 0.0, 32.0),
    SEARCH_NULL_LOW_MATERIAL_PENALTY("search.nullLowMaterialPenalty", 0.75, 0.0, 4.0),
    SEARCH_NULL_VERY_LOW_MOBILITY_PENALTY("search.nullVeryLowMobilityPenalty", 0.5, 0.0, 4.0),
    SEARCH_NULL_SWING_GUARD_MIN_CP("search.nullSwingGuardMinCp", 600, 0.0, 10000.0),
    SEARCH_NULL_SWING_GUARD_DIVISOR("search.nullSwingGuardDivisor", 64, 1.0, 512.0),

    SEARCH_TT_MAIN_WEIGHT("search.ttMainWeight", 2.0, 0.1, 16.0),
    SEARCH_TT_CAPTURE_WEIGHT("search.ttCaptureWeight", 1.0, 0.1, 16.0),

    SEARCH_QS_MAX_DELTA_PAWN("search.qsMaxDeltaPawn", 9.0, 0.0, 64.0),
    SEARCH_DRAW_BIAS("search.drawBias", 0.20, 0.0, 2.0),
    SEARCH_ROOT_STATIC_BLEND("search.rootStaticBlend", 0.12, 0.0, 1.0),
    SEARCH_ROOT_STATIC_OVERRIDE_CP("search.rootStaticOverrideCp", 160, 0.0, 4096.0),
    SEARCH_ROOT_QUEEN_ATTACK_BONUS_CP("search.rootQueenAttackBonusCp", 120, 0.0, 800.0),
    SEARCH_ROOT_EARLY_STOP_MARGIN_CP("search.rootEarlyStopMarginCp", 400, 0.0, 2000.0),
    SEARCH_ROOT_FUTILITY_MARGIN_CP("search.rootFutilityMarginCp", 120, 0.0, 2000.0),
    SEARCH_ROOT_FUTILITY_LEAD_CP("search.rootFutilityLeadCp", 300, 0.0, 4000.0),
    SEARCH_ROOT_CAPTURE_VALUE_THRESHOLD_CP("search.rootCaptureValueThresholdCp", 400, 0.0, 8000.0),
    SEARCH_ROOT_CAPTURE_GAIN_THRESHOLD_CP("search.rootCaptureGainThresholdCp", 200, 0.0, 8000.0),
    SEARCH_ROOT_RUNNER_UP_MARGIN_CP("search.rootRunnerUpMarginCp", 50, 0.0, 4000.0),
    SEARCH_ROOT_DESPAIR_MARGIN_CP("search.rootDespairMarginCp", 400, 0.0, 8000.0),
    SEARCH_ROOT_DESPAIR_MIN_EVALS("search.rootDespairMinEvals", 12, 1.0, 128.0),
    SEARCH_ROOT_DESPAIR_RUNNER_RATIO("search.rootDespairRunnerRatio", 0.6, 0.0, 2.0),
    SEARCH_ROOT_DESPAIR_ABS_THRESHOLD_CP("search.rootDespairAbsThresholdCp", 500, 0.0, 8000.0),
    SEARCH_ROOT_DESPAIR_MIN_EVALS_ABS("search.rootDespairMinEvalsAbs", 12, 1.0, 256.0),
    SEARCH_ROOT_HOPELESS_MARGIN_CP("search.rootHopelessMarginCp", 75, 0.0, 4000.0),
    SEARCH_ABS_PLY_LIMIT_MARGIN("search.absPlyLimitMargin", 32, 1.0, 256.0),
    SEARCH_PREFER_FAST_MATE("search.preferFastMate", 1.0, 0.0, 1.0),
    SEARCH_TB_TIE_BREAK("search.tbTieBreak", 1.0, 0.0, 1.0),
    SEARCH_TB_DTZ_PENALTY("search.tbDtzPenalty", 12.0, 1.0, 100.0);

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
