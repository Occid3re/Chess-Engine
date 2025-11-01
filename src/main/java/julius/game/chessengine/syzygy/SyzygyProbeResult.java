package julius.game.chessengine.syzygy;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Immutable payload returned by the {@link SyzygyTablebaseService}.
 */
public record SyzygyProbeResult(SyzygyWdl wdl, OptionalInt dtz, OptionalInt dtm, Optional<SyzygyMove> recommendedMove) {

    public SyzygyProbeResult {
        Objects.requireNonNull(wdl, "wdl");
        dtz = dtz.isEmpty() ? OptionalInt.empty() : dtz;
        dtm = dtm.isEmpty() ? OptionalInt.empty() : dtm;
    }

    public static SyzygyProbeResult unavailable() {
        return new SyzygyProbeResult(SyzygyWdl.UNKNOWN, OptionalInt.empty(), OptionalInt.empty(), Optional.empty());
    }
}
