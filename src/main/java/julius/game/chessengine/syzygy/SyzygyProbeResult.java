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
        dtz = dtz == null ? OptionalInt.empty() : dtz;
        dtm = dtm == null ? OptionalInt.empty() : dtm;
        recommendedMove = recommendedMove == null ? Optional.empty() : recommendedMove;
    }

    public static SyzygyProbeResult unavailable() {
        return new SyzygyProbeResult(SyzygyWdl.UNKNOWN, OptionalInt.empty(), OptionalInt.empty(), Optional.empty());
    }
}
