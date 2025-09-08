package julius.game.chessengine.config;

import julius.game.chessengine.board.BitBoard;
import org.springframework.context.annotation.Bean;
import org.springframework.web.context.annotation.SessionScope;

public class BoardBean {

    @Bean
    @SessionScope
    public BitBoard sessionScopedBoard() {
        return new BitBoard();
    }

}
