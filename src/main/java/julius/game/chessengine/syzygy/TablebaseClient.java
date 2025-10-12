package julius.game.chessengine.syzygy;

import julius.game.chessengine.board.BitBoard;

import java.util.Optional;

interface TablebaseClient {

    Optional<SyzygyProbeResult> probe(BitBoard board);

}
