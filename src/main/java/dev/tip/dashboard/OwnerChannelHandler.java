package dev.tip.dashboard;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The owner's live channel. The server only sends; whatever the page sends is ignored, except that a
 * message over 1 KB closes the socket with 1009, as on the other channels.
 */
@Component
class OwnerChannelHandler extends TextWebSocketHandler {

    static final int MAX_INBOUND_BYTES = 1024;
    static final int SEND_TIME_LIMIT_MILLIS = 5_000;
    static final int BUFFER_SIZE_LIMIT_BYTES = 64 * 1024;

    private static final String CONCURRENT = OwnerChannelHandler.class.getName() + ".concurrent";

    private final OwnerSockets sockets;
    private final OwnerKeepalive keepalive;

    OwnerChannelHandler(OwnerSockets sockets, OwnerKeepalive keepalive) {
        this.sockets = sockets;
        this.keepalive = keepalive;
    }

    /**
     * A read only session (the public demo) gets {@code closing} {@code read_only} and is closed at once, before it
     * is registered, so it never counts towards an owner's limit and hears nothing.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        if (Boolean.TRUE.equals(raw.getAttributes().get(OwnerHandshakeInterceptor.READ_ONLY))) {
            sockets.closeWithReason(raw, "read_only", OwnerSockets.READ_ONLY);
            return;
        }
        WebSocketSession session =
                new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MILLIS, BUFFER_SIZE_LIMIT_BYTES);
        raw.getAttributes().put(CONCURRENT, session);
        keepalive.connected(raw);
        sockets.add(ownerId(raw), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        closeIfTooBig(raw, message);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession raw, BinaryMessage message) {
        closeIfTooBig(raw, message);
    }

    @Override
    protected void handlePongMessage(WebSocketSession raw, PongMessage message) {
        keepalive.ponged(raw);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        WebSocketSession session = concurrent(raw);
        if (session != null) {
            sockets.remove(ownerId(raw), session);
        }
    }

    private static void closeIfTooBig(WebSocketSession raw, WebSocketMessage<?> message) {
        if (message.getPayloadLength() > MAX_INBOUND_BYTES) {
            OwnerSockets.close(concurrent(raw), CloseStatus.TOO_BIG_TO_PROCESS);
        }
    }

    static UUID ownerId(WebSocketSession session) {
        return (UUID) session.getAttributes().get(OwnerHandshakeInterceptor.OWNER_ID);
    }

    private static WebSocketSession concurrent(WebSocketSession raw) {
        return (WebSocketSession) raw.getAttributes().get(CONCURRENT);
    }
}
