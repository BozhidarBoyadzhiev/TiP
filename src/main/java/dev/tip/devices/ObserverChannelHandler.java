package dev.tip.devices;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The public, read only observer channel. Observers receive live reactions only, no replay. Whatever
 * they send is ignored, except that a message over 1 KB closes the session with 1009.
 */
@Component
class ObserverChannelHandler extends TextWebSocketHandler {

    private static final String CONCURRENT = ObserverChannelHandler.class.getName() + ".concurrent";

    private final ObserverSessions observers;
    private final SessionKeepalive keepalive;

    ObserverChannelHandler(ObserverSessions observers, SessionKeepalive keepalive) {
        this.observers = observers;
        this.keepalive = keepalive;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        WebSocketSession session = DeviceSessions.concurrent(raw);
        raw.getAttributes().put(CONCURRENT, session);
        keepalive.connected(raw);
        observers.add(slug(raw), session);
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
            observers.remove(slug(raw), session);
        }
        ((ObserverConnectionLimiter.Reservation) raw.getAttributes().get(ObserverHandshakeInterceptor.RESERVATION))
                .release();
    }

    private static void closeIfTooBig(WebSocketSession raw, WebSocketMessage<?> message) {
        if (message.getPayloadLength() > DeviceChannelHandler.MAX_INBOUND_BYTES) {
            DeviceSessions.close(concurrent(raw), CloseStatus.TOO_BIG_TO_PROCESS);
        }
    }

    private static String slug(WebSocketSession raw) {
        return (String) raw.getAttributes().get(ObserverHandshakeInterceptor.SLUG);
    }

    private static WebSocketSession concurrent(WebSocketSession raw) {
        return (WebSocketSession) raw.getAttributes().get(CONCURRENT);
    }
}
