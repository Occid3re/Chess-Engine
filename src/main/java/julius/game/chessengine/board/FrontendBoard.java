package julius.game.chessengine.board;

import lombok.Data;
import java.util.Map;

@Data
public class FrontendBoard {
    private Map<String, String> renderBoard;  // { "e2":"wP", ... }
    private String fen;                       // convenient if you need it
    private String enPassantTarget;           // e.g. "e3" or null
}