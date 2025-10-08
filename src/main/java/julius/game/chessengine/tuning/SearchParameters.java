package julius.game.chessengine.tuning;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot of search-related numeric tuning parameters. Values are captured once per search so
 * hot-reload updates do not interfere with in-flight iterations.
 */
public final class SearchParameters {

    private SearchParameters() {
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                Tuning.searchFutilityMarginDepth1(),
                Tuning.searchFutilityMarginDepth2(),
                Tuning.searchLateMovePruningBase(),
                Tuning.searchLateMovePruningPerDepth(),
                Tuning.searchHistoryPruneMinIndex(),
                Tuning.searchHistoryPruneMax(),
                Tuning.searchIidReduceDepth(),
                Tuning.searchLmrProtectPlyMax(),
                Tuning.searchLmrProtectIndexMax(),
                Tuning.searchLmrCapGoodQuiet()
        );
    }

    public record Snapshot(
            int futilityMarginDepth1,
            int futilityMarginDepth2,
            int lateMovePruningBase,
            int lateMovePruningPerDepth,
            int historyPruneMinIndex,
            int historyPruneMax,
            int iidReduceDepth,
            int lmrProtectPlyMax,
            int lmrProtectIndexMax,
            int lmrCapGoodQuiet
    ) {
        public Map<String, Integer> asMap() {
            Map<String, Integer> values = new LinkedHashMap<>();
            values.put("search.futilityMarginDepth1", futilityMarginDepth1);
            values.put("search.futilityMarginDepth2", futilityMarginDepth2);
            values.put("search.lmpBase", lateMovePruningBase);
            values.put("search.lmpPerDepth", lateMovePruningPerDepth);
            values.put("search.hmpMinIndex", historyPruneMinIndex);
            values.put("search.hmpHistoryMax", historyPruneMax);
            values.put("search.iidReduceDepth", iidReduceDepth);
            values.put("search.lmrProtectPlyMax", lmrProtectPlyMax);
            values.put("search.lmrProtectIndexMax", lmrProtectIndexMax);
            values.put("search.lmrCapGoodQuiet", lmrCapGoodQuiet);
            return Collections.unmodifiableMap(values);
        }
    }
}
