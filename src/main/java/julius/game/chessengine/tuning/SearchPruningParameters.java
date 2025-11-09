package julius.game.chessengine.tuning;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot of pruning- and reduction-related search parameters.  The values are cached from
 * {@link Tuning} so callers can read plain primitives during the hot search path without consulting
 * the global registry.
 */
public final class SearchPruningParameters {

    private SearchPruningParameters() {
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                Tuning.searchFpMarginDepth1(),
                Tuning.searchFpMarginDepth2(),
                Tuning.searchSnmpMarginDepth1(),
                Tuning.searchSnmpMarginDepth2(),
                Tuning.searchSnmpMarginDepth3(),
                Tuning.searchRazorMarginDepth1(),
                Tuning.searchRazorMarginDepth2(),
                Tuning.searchProbCutEnableDepth(),
                Tuning.searchProbCutMargin(),
                Tuning.searchProbCutSeeMin(),
                Tuning.searchEfpMarginDepth1(),
                Tuning.searchEfpMarginDepth2(),
                Tuning.searchEfpMarginDepth3(),
                Tuning.searchLqpHistoryCutoff(),
                Tuning.searchLqpButterflyCutoff(),
                Tuning.searchLqpStartIndex(),
                Tuning.searchLqpMaxDepth(),
                Tuning.searchLmpBase(),
                Tuning.searchLmpPerDepth(),
                Tuning.searchHmpMinIndex(),
                Tuning.searchHmpHistoryMax(),
                Tuning.searchIidReduceDepth(),
                Tuning.searchLmrProtectPlyMax(),
                Tuning.searchLmrProtectIndexMax(),
                Tuning.searchLmrCapGoodQuiet(),
                Tuning.searchMaxCheckExtensionStreak(),
                Tuning.searchSeePruneNearRootPly(),
                Tuning.searchHistoryReductionMax()
        );
    }

    public static Map<String, Double> defaults() {
        Map<String, Double> defaults = new LinkedHashMap<>();
        defaults.put(ParamId.SEARCH_FP_MARGIN_DEPTH1.key(), ParamId.SEARCH_FP_MARGIN_DEPTH1.defaultValue());
        defaults.put(ParamId.SEARCH_FP_MARGIN_DEPTH2.key(), ParamId.SEARCH_FP_MARGIN_DEPTH2.defaultValue());
        defaults.put(ParamId.SEARCH_SNMP_MARGIN_DEPTH1.key(), ParamId.SEARCH_SNMP_MARGIN_DEPTH1.defaultValue());
        defaults.put(ParamId.SEARCH_SNMP_MARGIN_DEPTH2.key(), ParamId.SEARCH_SNMP_MARGIN_DEPTH2.defaultValue());
        defaults.put(ParamId.SEARCH_SNMP_MARGIN_DEPTH3.key(), ParamId.SEARCH_SNMP_MARGIN_DEPTH3.defaultValue());
        defaults.put(ParamId.SEARCH_RAZOR_MARGIN_DEPTH1.key(), ParamId.SEARCH_RAZOR_MARGIN_DEPTH1.defaultValue());
        defaults.put(ParamId.SEARCH_RAZOR_MARGIN_DEPTH2.key(), ParamId.SEARCH_RAZOR_MARGIN_DEPTH2.defaultValue());
        defaults.put(ParamId.SEARCH_PROBCUT_ENABLE_DEPTH.key(), ParamId.SEARCH_PROBCUT_ENABLE_DEPTH.defaultValue());
        defaults.put(ParamId.SEARCH_PROBCUT_MARGIN.key(), ParamId.SEARCH_PROBCUT_MARGIN.defaultValue());
        defaults.put(ParamId.SEARCH_PROBCUT_SEE_MIN.key(), ParamId.SEARCH_PROBCUT_SEE_MIN.defaultValue());
        defaults.put(ParamId.SEARCH_EFP_MARGIN_DEPTH1.key(), ParamId.SEARCH_EFP_MARGIN_DEPTH1.defaultValue());
        defaults.put(ParamId.SEARCH_EFP_MARGIN_DEPTH2.key(), ParamId.SEARCH_EFP_MARGIN_DEPTH2.defaultValue());
        defaults.put(ParamId.SEARCH_EFP_MARGIN_DEPTH3.key(), ParamId.SEARCH_EFP_MARGIN_DEPTH3.defaultValue());
        defaults.put(ParamId.SEARCH_LQP_HISTORY_CUTOFF.key(), ParamId.SEARCH_LQP_HISTORY_CUTOFF.defaultValue());
        defaults.put(ParamId.SEARCH_LQP_BUTTERFLY_CUTOFF.key(), ParamId.SEARCH_LQP_BUTTERFLY_CUTOFF.defaultValue());
        defaults.put(ParamId.SEARCH_LQP_START_INDEX.key(), ParamId.SEARCH_LQP_START_INDEX.defaultValue());
        defaults.put(ParamId.SEARCH_LQP_MAX_DEPTH.key(), ParamId.SEARCH_LQP_MAX_DEPTH.defaultValue());
        defaults.put(ParamId.SEARCH_LMP_BASE.key(), ParamId.SEARCH_LMP_BASE.defaultValue());
        defaults.put(ParamId.SEARCH_LMP_PER_DEPTH.key(), ParamId.SEARCH_LMP_PER_DEPTH.defaultValue());
        defaults.put(ParamId.SEARCH_HMP_MIN_INDEX.key(), ParamId.SEARCH_HMP_MIN_INDEX.defaultValue());
        defaults.put(ParamId.SEARCH_HMP_HISTORY_MAX.key(), ParamId.SEARCH_HMP_HISTORY_MAX.defaultValue());
        defaults.put(ParamId.SEARCH_IID_REDUCE_DEPTH.key(), ParamId.SEARCH_IID_REDUCE_DEPTH.defaultValue());
        defaults.put(ParamId.SEARCH_LMR_PROTECT_PLY_MAX.key(), ParamId.SEARCH_LMR_PROTECT_PLY_MAX.defaultValue());
        defaults.put(ParamId.SEARCH_LMR_PROTECT_INDEX_MAX.key(), ParamId.SEARCH_LMR_PROTECT_INDEX_MAX.defaultValue());
        defaults.put(ParamId.SEARCH_LMR_CAP_GOOD_QUIET.key(), ParamId.SEARCH_LMR_CAP_GOOD_QUIET.defaultValue());
        defaults.put(ParamId.SEARCH_MAX_CHECK_EXTENSION_STREAK.key(), ParamId.SEARCH_MAX_CHECK_EXTENSION_STREAK.defaultValue());
        defaults.put(ParamId.SEARCH_SEE_PRUNE_NEAR_ROOT_PLY.key(), ParamId.SEARCH_SEE_PRUNE_NEAR_ROOT_PLY.defaultValue());
        defaults.put(ParamId.SEARCH_HISTORY_REDUCTION_MAX.key(), ParamId.SEARCH_HISTORY_REDUCTION_MAX.defaultValue());
        return Collections.unmodifiableMap(defaults);
    }

    public record Snapshot(
            int fpMarginDepth1,
            int fpMarginDepth2,
            int snmpMarginDepth1,
            int snmpMarginDepth2,
            int snmpMarginDepth3,
            int razorMarginDepth1,
            int razorMarginDepth2,
            int probCutEnableDepth,
            int probCutMargin,
            int probCutSeeMin,
            int efpMarginDepth1,
            int efpMarginDepth2,
            int efpMarginDepth3,
            int lqpHistoryCutoff,
            int lqpButterflyCutoff,
            int lqpStartIndex,
            int lqpMaxDepth,
            int lmpBase,
            int lmpPerDepth,
            int hmpMinIndex,
            int hmpHistoryMax,
            int iidReduceDepth,
            int lmrProtectPlyMax,
            int lmrProtectIndexMax,
            int lmrCapForGoodQuiet,
            int maxCheckExtensionStreak,
            int seePruneNearRootPly,
            int historyReductionMax
    ) {
    }
}
