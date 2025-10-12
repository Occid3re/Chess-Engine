package julius.game.chessengine.syzygy;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Immutable representation of a Syzygy probe tailored for engine consumers.
 * The record exposes the WDL and optional DTZ/DTM distances so higher layers
 * can surface them without re-interpreting the raw service payload.
 */
public record TablebaseResult(SyzygyWdl wdl, OptionalInt dtz, OptionalInt dtm) {

    public TablebaseResult {
        Objects.requireNonNull(wdl, "wdl");
        dtz = dtz == null ? OptionalInt.empty() : dtz;
        dtm = dtm == null ? OptionalInt.empty() : dtm;
    }

    public static TablebaseResult from(SyzygyProbeResult result) {
        Objects.requireNonNull(result, "result");
        return new TablebaseResult(result.wdl(), result.dtz(), result.dtm());
    }

    public boolean isWin() {
        return wdl.score() > 0;
    }

    public boolean isLoss() {
        return wdl.score() < 0;
    }

    public boolean isDraw() {
        return wdl.score() == 0;
    }

    public boolean isFiftyMoveSensitive() {
        return wdl == SyzygyWdl.CURSED_WIN || wdl == SyzygyWdl.BLESSED_LOSS;
    }
}
