package julius.game.chessengine.uci.websocket;

import julius.game.chessengine.uci.UciHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

@Component
public class UciWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(UciWebSocketHandler.class);
    private static final String HANDLER_ATTRIBUTE = UciWebSocketHandler.class.getName() + ".handler";

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UciHandler handler = new UciHandler(message -> sendSafely(session, message), session::isOpen);
        session.getAttributes().put(HANDLER_ATTRIBUTE, handler);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        UciHandler handler = (UciHandler) session.getAttributes().get(HANDLER_ATTRIBUTE);
        if (handler == null) {
            log.warn("No UciHandler bound to session {}", session.getId());
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }
        boolean keepRunning = handler.handle(message.getPayload());
        if (!keepRunning) {
            handler.stop();
            session.close(CloseStatus.NORMAL);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UciHandler handler = (UciHandler) session.getAttributes().remove(HANDLER_ATTRIBUTE);
        if (handler != null) {
            handler.stop();
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
