package julius.game.chessengine.syzygy;

import julius.game.chessengine.board.BitBoard;
import lombok.extern.log4j.Log4j2;

import java.util.Optional;

@Log4j2
final class FathomTablebaseClient implements TablebaseClient {

    private final Tables tables;

    private FathomTablebaseClient(Tables tables) {
        this.tables = tables;
    }

    static Optional<TablebaseClient> create(String directories, int maxPieces) {
        return Tables.load(directories, maxPieces).map(FathomTablebaseClient::new).map(client -> (TablebaseClient) client);
    }

    @Override
    public Optional<SyzygyProbeResult> probe(BitBoard board) {
        try {
            return tables.probe(board);
        } catch (RuntimeException ex) {
            log.warn("Failed to probe Syzygy tables for board {}", board, ex);
            return Optional.empty();
        }
    }
}
