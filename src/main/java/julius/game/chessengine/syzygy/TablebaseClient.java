package julius.game.chessengine.syzygy;

import java.util.Optional;

interface TablebaseClient {

    Optional<SyzygyProbeResult> probe(String fen);

}
