package julius.game.chessengine.ai;

import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.engine.search.config.SearchConfig;
import julius.game.chessengine.engine.search.engine.SearchEngine;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

/**
 * Retained legacy entrypoint that delegates to the new {@link SearchEngine} facade.
 */
@Log4j2
@Component
public class AI extends SearchEngine {

    public AI(Engine mainEngine) {
        super(mainEngine);
    }

    public AI(Engine mainEngine, SearchConfig config) {
        super(mainEngine, config);
    }
}
