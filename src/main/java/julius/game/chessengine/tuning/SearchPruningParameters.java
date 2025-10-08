package julius.game.chessengine.tuning;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot of search-related pruning parameters. Mirrors {@link MoveOrderingParameters} but focuses on
 * forward-pruning heuristics so hot paths can dereference plain primitives without repeatedly querying
 * the {@link ParameterRegistry}.
 */
public final class SearchPruningParameters {

    private SearchPruningParameters() {
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                Tuning.searchFutilityMarginDepth1(),
                Tuning.searchFutilityMarginDepth2(),
                Tuning.searchLmpBase(),
                Tuning.searchLmpPerDepth(),
                Tuning.searchHmpMinIndex(),
                Tuning.searchHmpHistoryMax(),
                Tuning.searchIidReduceDepth(),
                Tuning.searchLmrProtectPlyMax(),
                Tuning.searchLmrProtectIndexMax(),
                Tuning.searchLmrCapForGoodQuiet()
        );
    }

    public static Map<String, Double> defaults() {
        Map<String, Double> defaults = new LinkedHashMap<>();
        defaults.put(ParamId.SEARCH_FUTILITY_MARGIN_DEPTH1.key(), ParamId.SEARCH_FUTILITY_MARGIN_DEPTH1.defaultValue());
        defaults.put(ParamId.SEARCH_FUTILITY_MARGIN_DEPTH2.key(), ParamId.SEARCH_FUTILITY_MARGIN_DEPTH2.defaultValue());
        defaults.put(ParamId.SEARCH_LMP_BASE.key(), ParamId.SEARCH_LMP_BASE.defaultValue());
        defaults.put(ParamId.SEARCH_LMP_PER_DEPTH.key(), ParamId.SEARCH_LMP_PER_DEPTH.defaultValue());
        defaults.put(ParamId.SEARCH_HMP_MIN_INDEX.key(), ParamId.SEARCH_HMP_MIN_INDEX.defaultValue());
        defaults.put(ParamId.SEARCH_HMP_HISTORY_MAX.key(), ParamId.SEARCH_HMP_HISTORY_MAX.defaultValue());
        defaults.put(ParamId.SEARCH_IID_REDUCE_DEPTH.key(), ParamId.SEARCH_IID_REDUCE_DEPTH.defaultValue());
        defaults.put(ParamId.SEARCH_LMR_PROTECT_PLY_MAX.key(), ParamId.SEARCH_LMR_PROTECT_PLY_MAX.defaultValue());
        defaults.put(ParamId.SEARCH_LMR_PROTECT_INDEX_MAX.key(), ParamId.SEARCH_LMR_PROTECT_INDEX_MAX.defaultValue());
        defaults.put(ParamId.SEARCH_LMR_CAP_FOR_GOOD_QUIET.key(), ParamId.SEARCH_LMR_CAP_FOR_GOOD_QUIET.defaultValue());
        return Collections.unmodifiableMap(defaults);
    }

    public record Snapshot(
            int fpMarginDepth1,
            int fpMarginDepth2,
            int lmpBase,
            int lmpPerDepth,
            int hmpMinIndex,
            int hmpHistoryMax,
            int iidReduceDepth,
            int lmrProtectPlyMax,
            int lmrProtectIndexMax,
            int lmrCapForGoodQuiet
    ) {
    }
}

