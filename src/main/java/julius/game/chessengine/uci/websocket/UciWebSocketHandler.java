package julius.game.chessengine.uci.websocket;

import julius.game.chessengine.core.Uci;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

/**
 * The browser UI (static/index.html) talks UCI over {@code /ws/uci}: every WebSocket session gets its own
 * core engine, whose output lines go back to that session.
 */
@Component
public class UciWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(UciWebSocketHandler.class);
    private static final String HANDLER_ATTRIBUTE = UciWebSocketHandler.class.getName() + ".handler";

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Uci engine = new Uci(message -> sendSafely(session, message));
        session.getAttributes().put(HANDLER_ATTRIBUTE, engine);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Uci engine = (Uci) session.getAttributes().get(HANDLER_ATTRIBUTE);
        if (engine == null) {
            log.warn("No engine bound to session {}", session.getId());
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }
        for (String line : message.getPayload().split("\\R")) {
            if (!engine.handle(line.trim())) {
                engine.close();
                session.close(CloseStatus.NORMAL);
                return;
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Uci engine = (Uci) session.getAttributes().remove(HANDLER_ATTRIBUTE);
        if (engine != null) {
            engine.close();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("Transport error on session {}", session.getId(), exception);
        super.handleTransportError(session, exception);
    }

    private void sendSafely(WebSocketSession session, String payload) {
        if (!session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException e) {
            log.warn("Failed to send WebSocket message for session {}", session.getId(), e);
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException closeException) {
                log.debug("Failed to close WebSocket session {} after send error", session.getId(), closeException);
            }
        }
    }
}
