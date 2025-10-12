package julius.game.chessengine.syzygy;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
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
    public Optional<SyzygyProbeResult> probe(String fen) {
        try {
            BitBoard board = FEN.translateFENtoBitBoard(fen);
            return tables.probe(board);
        } catch (RuntimeException ex) {
            log.warn("Failed to translate FEN '{}' for Syzygy probe", fen, ex);
            return Optional.empty();
        }
    }
}
