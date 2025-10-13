package julius.game.chessengine.syzygy;

import julius.game.chessengine.board.BitBoard;

import java.util.Optional;

interface TablebaseClient {

    Optional<SyzygyProbeResult> probe(BitBoard board);

    /**
     * Largest number of pieces the client can probe. Return {@code 0} when the limit is
     * unknown so callers can fall back to their configured maximums.
     */
    default int supportedMaxPieces() {
        return 0;
    }
}
