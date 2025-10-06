package julius.game.chessengine.uci;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import julius.game.chessengine.uci.websocket.UciWebSocketHandler;
import julius.game.chessengine.utils.VersionInfo;
import testsupport.TestLoggingExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(TestLoggingExtension.class)
class UciWebSocketHandlerTest {

    private TestableUciWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TestableUciWebSocketHandler();
    }

    @Test
    void afterConnectionEstablishedBindsUciHandlerAndRelaysOutput() throws Exception {
        StubWebSocketSession session = new StubWebSocketSession("session-1");

        handler.afterConnectionEstablished(session);

        UciHandler boundHandler = session.findAttributeOfType(UciHandler.class);
        assertThat(boundHandler).isNotNull();

        handler.handleText(session, new TextMessage("uci"));

        assertThat(session.sentPayloads())
                .as("UCI handshake should be routed through the WebSocket session")
                .contains("id name Alieknek " + VersionInfo.getVersion(), "uciok");
    }

    @Test
    void handleTextMessageClosesSessionWhenNoHandlerIsBound() throws Exception {
        StubWebSocketSession session = new StubWebSocketSession("session-2");

        handler.handleText(session, new TextMessage("isready"));

        assertThat(session.isOpen()).isFalse();
        assertThat(session.lastCloseStatus()).isEqualTo(CloseStatus.SERVER_ERROR);
    }

    @Test
    void handleTextMessageStopsHandlerAndClosesNormallyOnQuit() throws Exception {
        StubWebSocketSession session = new StubWebSocketSession("session-3");
        handler.afterConnectionEstablished(session);

        handler.handleText(session, new TextMessage("quit"));

        assertThat(session.isOpen()).isFalse();
        assertThat(session.lastCloseStatus()).isEqualTo(CloseStatus.NORMAL);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(session.getAttributes()).isEmpty();
    }

    @Test
    void sendSafelyClosesSessionWhenSendFails() throws Exception {
        StubWebSocketSession session = new StubWebSocketSession("session-4");
        handler.afterConnectionEstablished(session);
        session.failNextSend(new IOException("boom"));

        handler.handleText(session, new TextMessage("uci"));

        assertThat(session.sentPayloads()).isEmpty();
        assertThat(session.isOpen()).isFalse();
        assertThat(session.lastCloseStatus()).isEqualTo(CloseStatus.SERVER_ERROR);
    }

    private static final class TestableUciWebSocketHandler extends UciWebSocketHandler {
        void handleText(WebSocketSession session, TextMessage message) throws Exception {
            super.handleTextMessage(session, message);
        }
    }

    private static final class StubWebSocketSession implements WebSocketSession {

        private final String id;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();
        private final List<TextMessage> sentMessages = Collections.synchronizedList(new ArrayList<>());
        private final List<CloseStatus> closeStatuses = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean open = true;
        private volatile IOException nextFailure;
        private volatile int textMessageSizeLimit = Integer.MAX_VALUE;
        private volatile int binaryMessageSizeLimit = Integer.MAX_VALUE;

        StubWebSocketSession(String id) {
            this.id = id;
        }

        void failNextSend(IOException failure) {
            this.nextFailure = Objects.requireNonNull(failure);
        }

        <T> T findAttributeOfType(Class<T> type) {
            for (Object value : attributes.values()) {
                if (type.isInstance(value)) {
                    return type.cast(value);
                }
            }
            return null;
        }

        List<String> sentPayloads() {
            synchronized (sentMessages) {
                return sentMessages.stream().map(TextMessage::getPayload).toList();
            }
        }

        CloseStatus lastCloseStatus() {
            synchronized (closeStatuses) {
                return closeStatuses.isEmpty() ? null : closeStatuses.get(closeStatuses.size() - 1);
            }
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public URI getUri() {
            return null;
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return HttpHeaders.EMPTY;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
            this.textMessageSizeLimit = messageSizeLimit;
        }

        @Override
        public int getTextMessageSizeLimit() {
            return textMessageSizeLimit;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
            this.binaryMessageSizeLimit = messageSizeLimit;
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return binaryMessageSizeLimit;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            IOException failure = nextFailure;
            nextFailure = null;
            if (failure != null) {
                throw failure;
            }
            if (message instanceof TextMessage text) {
                sentMessages.add(text);
            } else {
                throw new IllegalArgumentException("Unexpected message type: " + message);
            }
        }

        @Override
        public void close() throws IOException {
            close(CloseStatus.NORMAL);
        }

        @Override
        public void close(CloseStatus status) {
            open = false;
            closeStatuses.add(status);
        }
    }
}
