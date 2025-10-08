package julius.game.chessengine.tuning;

/**
 * Caches all numeric tuning parameters so evaluation and search code can read plain primitives
 * without consulting {@link ParameterRegistry} during hot paths. Values are loaded once at class
 * initialization time and can be refreshed explicitly when hot reload is triggered.
 */
public final class Tuning {

    private static final Object LOCK = new Object();

    private static int pawnValue;
    private static int knightValue;
    private static int bishopValue;
    private static int rookValue;
    private static int queenValue;
    private static int bishopPairBonus;

    private static int centerPawnBonus;
    private static int passedPawnBonus;
    private static int connectedPawnBonus;
    private static int pawnIslandPenalty;
    private static int doubledPawnPenalty;
    private static int isolatedPawnPenalty;
    private static int advancedPawnBonus;
    private static int blockedPawnPenalty;
    private static int backwardPawnPenalty;
    private static int ownKingBlocksPassedPawnPenalty;
    private static int passedPawnFreePathBonusPerRank;
    private static int rookHalfOpenFileBonus;
    private static int rookOpenFileBonus;

    private static int activityMidgameKnightMobility;
    private static int activityMidgameBishopMobility;
    private static int activityMidgameRookMobility;
    private static int activityMidgameQueenMobility;
    private static int activityMidgameKingMobility;
    private static int activityEndgameKnightMobility;
    private static int activityEndgameBishopMobility;
    private static int activityEndgameRookMobility;
    private static int activityEndgameQueenMobility;
    private static int activityEndgameKingMobility;
    private static int activityMidgameKnightCenter;
    private static int activityMidgameBishopCenter;
    private static int activityMidgameRookCenter;
    private static int activityMidgameQueenCenter;
    private static int activityMidgameKingCenter;
    private static int activityEndgameKnightCenter;
    private static int activityEndgameBishopCenter;
    private static int activityEndgameRookCenter;
    private static int activityEndgameQueenCenter;
    private static int activityEndgameKingCenter;

    private static int missingPawnShieldPenalty;
    private static int halfOpenFilePenalty;
    private static int openFilePenalty;
    private static int defenderBonus;
    private static int queenAttackedPenalty;
    private static int backrankWeaknessMidgamePenalty;
    private static int backrankWeaknessEndgamePenalty;
    private static int kingSafetyPawnAttackWeight;
    private static int kingSafetyKnightAttackWeight;
    private static int kingSafetyBishopAttackWeight;
    private static int kingSafetyRookAttackWeight;
    private static int kingSafetyQueenAttackWeight;

    private static int hangingPawnPenalty;
    private static int hangingKnightPenalty;
    private static int hangingBishopPenalty;
    private static int hangingRookPenalty;
    private static int hangingQueenPenalty;
    private static int pawnThreatKnightPenalty;
    private static int pawnThreatBishopPenalty;
    private static int pawnThreatRookPenalty;
    private static int pawnThreatQueenPenalty;

    private static int developmentPhaseThreshold;
    private static int queenDevelopmentPhaseThreshold;
    private static int undevelopedMinorPenalty;
    private static int earlyQueenDevelopmentPenaltyPerMinor;
    private static int minUndevelopedMinorsForQueenPenalty;
    private static int startPositionPenalty;
    private static int castlingBonus;
    private static int notCastledRookMovePenalty;

    private static int evaluationBlendScale;

    private static int moveOrderingKillerMoveScore;
    private static int moveOrderingPromotionBonus;
    private static int moveOrderingKiller0Bonus;
    private static int moveOrderingKiller1Bonus;
    private static int moveOrderingCounterMoveBonus;
    private static int moveOrderingCaptureMvvMultiplier;
    private static int moveOrderingCaptureSeeMultiplier;
    private static int moveOrderingPromotionSeeMultiplier;
    private static int moveOrderingCastlingBonus;

    private static int searchFutilityMarginDepth1;
    private static int searchFutilityMarginDepth2;
    private static int searchLmpBase;
    private static int searchLmpPerDepth;
    private static int searchHmpMinIndex;
    private static int searchHmpHistoryMax;
    private static int searchIidReduceDepth;
    private static int searchLmrProtectPlyMax;
    private static int searchLmrProtectIndexMax;
    private static int searchLmrCapForGoodQuiet;

    static {
        refresh();
    }

    private Tuning() {
    }

    public static int pawnValue() {
        return pawnValue;
    }

    public static int knightValue() {
        return knightValue;
    }

    public static int bishopValue() {
        return bishopValue;
    }

    public static int rookValue() {
        return rookValue;
    }

    public static int queenValue() {
        return queenValue;
    }

    public static int bishopPairBonus() {
        return bishopPairBonus;
    }

    public static int centerPawnBonus() {
        return centerPawnBonus;
    }

    public static int passedPawnBonus() {
        return passedPawnBonus;
    }

    public static int connectedPawnBonus() {
        return connectedPawnBonus;
    }

    public static int pawnIslandPenalty() {
        return pawnIslandPenalty;
    }

    public static int doubledPawnPenalty() {
        return doubledPawnPenalty;
    }

    public static int isolatedPawnPenalty() {
        return isolatedPawnPenalty;
    }

    public static int advancedPawnBonus() {
        return advancedPawnBonus;
    }

    public static int blockedPawnPenalty() {
        return blockedPawnPenalty;
    }

    public static int backwardPawnPenalty() {
        return backwardPawnPenalty;
    }

    public static int ownKingBlocksPassedPawnPenalty() {
        return ownKingBlocksPassedPawnPenalty;
    }

    public static int passedPawnFreePathBonusPerRank() {
        return passedPawnFreePathBonusPerRank;
    }

    public static int rookHalfOpenFileBonus() {
        return rookHalfOpenFileBonus;
    }

    public static int rookOpenFileBonus() {
        return rookOpenFileBonus;
    }

    public static int activityMidgameKnightMobility() {
        return activityMidgameKnightMobility;
    }

    public static int activityMidgameBishopMobility() {
        return activityMidgameBishopMobility;
    }

    public static int activityMidgameRookMobility() {
        return activityMidgameRookMobility;
    }

    public static int activityMidgameQueenMobility() {
        return activityMidgameQueenMobility;
    }

    public static int activityMidgameKingMobility() {
        return activityMidgameKingMobility;
    }

    public static int activityEndgameKnightMobility() {
        return activityEndgameKnightMobility;
    }

    public static int activityEndgameBishopMobility() {
        return activityEndgameBishopMobility;
    }

    public static int activityEndgameRookMobility() {
        return activityEndgameRookMobility;
    }

    public static int activityEndgameQueenMobility() {
        return activityEndgameQueenMobility;
    }

    public static int activityEndgameKingMobility() {
        return activityEndgameKingMobility;
    }

    public static int activityMidgameKnightCenter() {
        return activityMidgameKnightCenter;
    }

    public static int activityMidgameBishopCenter() {
        return activityMidgameBishopCenter;
    }

    public static int activityMidgameRookCenter() {
        return activityMidgameRookCenter;
    }

    public static int activityMidgameQueenCenter() {
        return activityMidgameQueenCenter;
    }

    public static int activityMidgameKingCenter() {
        return activityMidgameKingCenter;
    }

    public static int activityEndgameKnightCenter() {
        return activityEndgameKnightCenter;
    }

    public static int activityEndgameBishopCenter() {
        return activityEndgameBishopCenter;
    }

    public static int activityEndgameRookCenter() {
        return activityEndgameRookCenter;
    }

    public static int activityEndgameQueenCenter() {
        return activityEndgameQueenCenter;
    }

    public static int activityEndgameKingCenter() {
        return activityEndgameKingCenter;
    }

    public static int missingPawnShieldPenalty() {
        return missingPawnShieldPenalty;
    }

    public static int halfOpenFilePenalty() {
        return halfOpenFilePenalty;
    }

    public static int openFilePenalty() {
        return openFilePenalty;
    }

    public static int defenderBonus() {
        return defenderBonus;
    }

    public static int queenAttackedPenalty() {
        return queenAttackedPenalty;
    }

    public static int backrankWeaknessMidgamePenalty() {
        return backrankWeaknessMidgamePenalty;
    }

    public static int backrankWeaknessEndgamePenalty() {
        return backrankWeaknessEndgamePenalty;
    }

    public static int kingSafetyPawnAttackWeight() {
        return kingSafetyPawnAttackWeight;
    }

    public static int kingSafetyKnightAttackWeight() {
        return kingSafetyKnightAttackWeight;
    }

    public static int kingSafetyBishopAttackWeight() {
        return kingSafetyBishopAttackWeight;
    }

    public static int kingSafetyRookAttackWeight() {
        return kingSafetyRookAttackWeight;
    }

    public static int kingSafetyQueenAttackWeight() {
        return kingSafetyQueenAttackWeight;
    }

    public static int hangingPawnPenalty() {
        return hangingPawnPenalty;
    }

    public static int hangingKnightPenalty() {
        return hangingKnightPenalty;
    }

    public static int hangingBishopPenalty() {
        return hangingBishopPenalty;
    }

    public static int hangingRookPenalty() {
        return hangingRookPenalty;
    }

    public static int hangingQueenPenalty() {
        return hangingQueenPenalty;
    }

    public static int pawnThreatKnightPenalty() {
        return pawnThreatKnightPenalty;
    }

    public static int pawnThreatBishopPenalty() {
        return pawnThreatBishopPenalty;
    }

    public static int pawnThreatRookPenalty() {
        return pawnThreatRookPenalty;
    }

    public static int pawnThreatQueenPenalty() {
        return pawnThreatQueenPenalty;
    }

    public static int developmentPhaseThreshold() {
        return developmentPhaseThreshold;
    }

    public static int queenDevelopmentPhaseThreshold() {
        return queenDevelopmentPhaseThreshold;
    }

    public static int undevelopedMinorPenalty() {
        return undevelopedMinorPenalty;
    }

    public static int earlyQueenDevelopmentPenaltyPerMinor() {
        return earlyQueenDevelopmentPenaltyPerMinor;
    }

    public static int minUndevelopedMinorsForQueenPenalty() {
        return minUndevelopedMinorsForQueenPenalty;
    }

    public static int startPositionPenalty() {
        return startPositionPenalty;
    }

    public static int castlingBonus() {
        return castlingBonus;
    }

    public static int notCastledRookMovePenalty() {
        return notCastledRookMovePenalty;
    }

    public static int evaluationBlendScale() {
        return evaluationBlendScale;
    }

    public static int moveOrderingKillerMoveScore() {
        return moveOrderingKillerMoveScore;
    }

    public static int moveOrderingPromotionBonus() {
        return moveOrderingPromotionBonus;
    }

    public static int moveOrderingKiller0Bonus() {
        return moveOrderingKiller0Bonus;
    }

    public static int moveOrderingKiller1Bonus() {
        return moveOrderingKiller1Bonus;
    }

    public static int moveOrderingCounterMoveBonus() {
        return moveOrderingCounterMoveBonus;
    }

    public static int moveOrderingCaptureMvvMultiplier() {
        return moveOrderingCaptureMvvMultiplier;
    }

    public static int moveOrderingCaptureSeeMultiplier() {
        return moveOrderingCaptureSeeMultiplier;
    }

    public static int moveOrderingPromotionSeeMultiplier() {
        return moveOrderingPromotionSeeMultiplier;
    }

    public static int moveOrderingCastlingBonus() {
        return moveOrderingCastlingBonus;
    }

    public static int searchFutilityMarginDepth1() {
        return searchFutilityMarginDepth1;
    }

    public static int searchFutilityMarginDepth2() {
        return searchFutilityMarginDepth2;
    }

    public static int searchLmpBase() {
        return searchLmpBase;
    }

    public static int searchLmpPerDepth() {
        return searchLmpPerDepth;
    }

    public static int searchHmpMinIndex() {
        return searchHmpMinIndex;
    }

    public static int searchHmpHistoryMax() {
        return searchHmpHistoryMax;
    }

    public static int searchIidReduceDepth() {
        return searchIidReduceDepth;
    }

    public static int searchLmrProtectPlyMax() {
        return searchLmrProtectPlyMax;
    }

    public static int searchLmrProtectIndexMax() {
        return searchLmrProtectIndexMax;
    }

    public static int searchLmrCapForGoodQuiet() {
        return searchLmrCapForGoodQuiet;
    }

    /**
     * Reloads all cached tuning values from the current {@link ParameterRegistry} snapshot.
     * Callers should invoke this once after applying a new parameter set (e.g. via hot reload).
     */
    public static void refresh() {
        synchronized (LOCK) {
            pawnValue = loadInt(ParamId.MATERIAL_PAWN_VALUE);
            knightValue = loadInt(ParamId.MATERIAL_KNIGHT_VALUE);
            bishopValue = loadInt(ParamId.MATERIAL_BISHOP_VALUE);
            rookValue = loadInt(ParamId.MATERIAL_ROOK_VALUE);
            queenValue = loadInt(ParamId.MATERIAL_QUEEN_VALUE);
            bishopPairBonus = loadInt(ParamId.MATERIAL_BISHOP_PAIR_BONUS);

            centerPawnBonus = loadInt(ParamId.PAWN_STRUCTURE_CENTER_PAWN_BONUS);
            passedPawnBonus = loadInt(ParamId.PAWN_STRUCTURE_PASSED_PAWN_BONUS);
            connectedPawnBonus = loadInt(ParamId.PAWN_STRUCTURE_CONNECTED_PAWN_BONUS);
            pawnIslandPenalty = loadInt(ParamId.PAWN_STRUCTURE_ISLAND_PENALTY);
            doubledPawnPenalty = loadInt(ParamId.PAWN_STRUCTURE_DOUBLED_PAWN_PENALTY);
            isolatedPawnPenalty = loadInt(ParamId.PAWN_STRUCTURE_ISOLATED_PAWN_PENALTY);
            advancedPawnBonus = loadInt(ParamId.PAWN_STRUCTURE_ADVANCED_PAWN_BONUS);
            blockedPawnPenalty = loadInt(ParamId.PAWN_STRUCTURE_BLOCKED_PAWN_PENALTY);
            backwardPawnPenalty = loadInt(ParamId.PAWN_STRUCTURE_BACKWARD_PAWN_PENALTY);
            ownKingBlocksPassedPawnPenalty = loadInt(ParamId.PAWN_STRUCTURE_OWN_KING_BLOCKS_PASSED_PAWN_PENALTY);
            passedPawnFreePathBonusPerRank = loadInt(ParamId.PAWN_STRUCTURE_PASSED_PAWN_FREE_PATH_BONUS_PER_RANK);
            rookHalfOpenFileBonus = loadInt(ParamId.PAWN_STRUCTURE_ROOK_HALF_OPEN_FILE_BONUS);
            rookOpenFileBonus = loadInt(ParamId.PAWN_STRUCTURE_ROOK_OPEN_FILE_BONUS);

            activityMidgameKnightMobility = loadInt(ParamId.ACTIVITY_MIDGAME_MOBILITY_KNIGHT);
            activityMidgameBishopMobility = loadInt(ParamId.ACTIVITY_MIDGAME_MOBILITY_BISHOP);
            activityMidgameRookMobility = loadInt(ParamId.ACTIVITY_MIDGAME_MOBILITY_ROOK);
            activityMidgameQueenMobility = loadInt(ParamId.ACTIVITY_MIDGAME_MOBILITY_QUEEN);
            activityMidgameKingMobility = loadInt(ParamId.ACTIVITY_MIDGAME_MOBILITY_KING);
            activityEndgameKnightMobility = loadInt(ParamId.ACTIVITY_ENDGAME_MOBILITY_KNIGHT);
            activityEndgameBishopMobility = loadInt(ParamId.ACTIVITY_ENDGAME_MOBILITY_BISHOP);
            activityEndgameRookMobility = loadInt(ParamId.ACTIVITY_ENDGAME_MOBILITY_ROOK);
            activityEndgameQueenMobility = loadInt(ParamId.ACTIVITY_ENDGAME_MOBILITY_QUEEN);
            activityEndgameKingMobility = loadInt(ParamId.ACTIVITY_ENDGAME_MOBILITY_KING);
            activityMidgameKnightCenter = loadInt(ParamId.ACTIVITY_MIDGAME_CENTER_KNIGHT);
            activityMidgameBishopCenter = loadInt(ParamId.ACTIVITY_MIDGAME_CENTER_BISHOP);
            activityMidgameRookCenter = loadInt(ParamId.ACTIVITY_MIDGAME_CENTER_ROOK);
            activityMidgameQueenCenter = loadInt(ParamId.ACTIVITY_MIDGAME_CENTER_QUEEN);
            activityMidgameKingCenter = loadInt(ParamId.ACTIVITY_MIDGAME_CENTER_KING);
            activityEndgameKnightCenter = loadInt(ParamId.ACTIVITY_ENDGAME_CENTER_KNIGHT);
            activityEndgameBishopCenter = loadInt(ParamId.ACTIVITY_ENDGAME_CENTER_BISHOP);
            activityEndgameRookCenter = loadInt(ParamId.ACTIVITY_ENDGAME_CENTER_ROOK);
            activityEndgameQueenCenter = loadInt(ParamId.ACTIVITY_ENDGAME_CENTER_QUEEN);
            activityEndgameKingCenter = loadInt(ParamId.ACTIVITY_ENDGAME_CENTER_KING);

            missingPawnShieldPenalty = loadInt(ParamId.KING_SAFETY_MISSING_PAWN_SHIELD_PENALTY);
            halfOpenFilePenalty = loadInt(ParamId.KING_SAFETY_HALF_OPEN_FILE_PENALTY);
            openFilePenalty = loadInt(ParamId.KING_SAFETY_OPEN_FILE_PENALTY);
            defenderBonus = loadInt(ParamId.KING_SAFETY_DEFENDER_BONUS);
            queenAttackedPenalty = loadInt(ParamId.KING_SAFETY_QUEEN_ATTACKED_PENALTY);
            backrankWeaknessMidgamePenalty = loadInt(ParamId.KING_SAFETY_BACKRANK_WEAKNESS_MIDGAME_PENALTY);
            backrankWeaknessEndgamePenalty = loadInt(ParamId.KING_SAFETY_BACKRANK_WEAKNESS_ENDGAME_PENALTY);
            kingSafetyPawnAttackWeight = loadInt(ParamId.KING_SAFETY_ATTACK_WEIGHT_PAWN);
            kingSafetyKnightAttackWeight = loadInt(ParamId.KING_SAFETY_ATTACK_WEIGHT_KNIGHT);
            kingSafetyBishopAttackWeight = loadInt(ParamId.KING_SAFETY_ATTACK_WEIGHT_BISHOP);
            kingSafetyRookAttackWeight = loadInt(ParamId.KING_SAFETY_ATTACK_WEIGHT_ROOK);
            kingSafetyQueenAttackWeight = loadInt(ParamId.KING_SAFETY_ATTACK_WEIGHT_QUEEN);

            hangingPawnPenalty = loadInt(ParamId.THREAT_HANGING_PAWN_PENALTY);
            hangingKnightPenalty = loadInt(ParamId.THREAT_HANGING_KNIGHT_PENALTY);
            hangingBishopPenalty = loadInt(ParamId.THREAT_HANGING_BISHOP_PENALTY);
            hangingRookPenalty = loadInt(ParamId.THREAT_HANGING_ROOK_PENALTY);
            hangingQueenPenalty = loadInt(ParamId.THREAT_HANGING_QUEEN_PENALTY);
            pawnThreatKnightPenalty = loadInt(ParamId.THREAT_PAWN_THREAT_KNIGHT_PENALTY);
            pawnThreatBishopPenalty = loadInt(ParamId.THREAT_PAWN_THREAT_BISHOP_PENALTY);
            pawnThreatRookPenalty = loadInt(ParamId.THREAT_PAWN_THREAT_ROOK_PENALTY);
            pawnThreatQueenPenalty = loadInt(ParamId.THREAT_PAWN_THREAT_QUEEN_PENALTY);

            evaluationBlendScale = loadInt(ParamId.EVALUATION_BLEND_SCALE);

            moveOrderingKillerMoveScore = loadInt(ParamId.MOVE_ORDERING_KILLER_MOVE_SCORE);
            moveOrderingPromotionBonus = loadInt(ParamId.MOVE_ORDERING_PROMOTION_BONUS);
            moveOrderingKiller0Bonus = loadInt(ParamId.MOVE_ORDERING_KILLER0_BONUS);
            moveOrderingKiller1Bonus = loadInt(ParamId.MOVE_ORDERING_KILLER1_BONUS);
            moveOrderingCounterMoveBonus = loadInt(ParamId.MOVE_ORDERING_COUNTER_MOVE_BONUS);
            moveOrderingCaptureMvvMultiplier = loadInt(ParamId.MOVE_ORDERING_CAPTURE_MVV_MULTIPLIER);
            moveOrderingCaptureSeeMultiplier = loadInt(ParamId.MOVE_ORDERING_CAPTURE_SEE_MULTIPLIER);
            moveOrderingPromotionSeeMultiplier = loadInt(ParamId.MOVE_ORDERING_PROMOTION_SEE_MULTIPLIER);
            moveOrderingCastlingBonus = loadInt(ParamId.MOVE_ORDERING_CASTLING_BONUS);

            searchFutilityMarginDepth1 = loadInt(ParamId.SEARCH_FUTILITY_MARGIN_DEPTH1);
            searchFutilityMarginDepth2 = loadInt(ParamId.SEARCH_FUTILITY_MARGIN_DEPTH2);
            searchLmpBase = loadInt(ParamId.SEARCH_LMP_BASE);
            searchLmpPerDepth = loadInt(ParamId.SEARCH_LMP_PER_DEPTH);
            searchHmpMinIndex = loadInt(ParamId.SEARCH_HMP_MIN_INDEX);
            searchHmpHistoryMax = loadInt(ParamId.SEARCH_HMP_HISTORY_MAX);
            searchIidReduceDepth = loadInt(ParamId.SEARCH_IID_REDUCE_DEPTH);
            searchLmrProtectPlyMax = loadInt(ParamId.SEARCH_LMR_PROTECT_PLY_MAX);
            searchLmrProtectIndexMax = loadInt(ParamId.SEARCH_LMR_PROTECT_INDEX_MAX);
            searchLmrCapForGoodQuiet = loadInt(ParamId.SEARCH_LMR_CAP_FOR_GOOD_QUIET);
        }
    }

    private static int loadInt(ParamId id) {
        return (int) Math.round(applyBounds(ParameterRegistry.get(id), id));
    }

    private static double applyBounds(double value, ParamId id) {
        if (!Double.isFinite(value)) {
            value = id.defaultValue();
        }
        Double min = id.minValue();
        if (min != null && value < min) {
            value = min;
        }
        Double max = id.maxValue();
        if (max != null && value > max) {
            value = max;
        }
        return value;
    }
}

