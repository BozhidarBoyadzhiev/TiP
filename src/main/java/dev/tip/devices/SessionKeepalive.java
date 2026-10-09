package dev.tip.devices;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Keeps device and observer sessions honest. Every 20 seconds each session gets a ping; a session whose
 * last pong (or connect) is 45 seconds old or more, two missed pings, is closed with 1001. On shutdown
 * every session is closed with 1001, so devices reconnect cleanly to the next instance after a deploy.
 */
@Component
class SessionKeepalive {

    static final Duration PONG_TIMEOUT = Duration.ofSeconds(45);

    private static final String LAST_PONG = SessionKeepalive.class.getName() + ".lastPong";

    private static final Logger log = LoggerFactory.getLogger(SessionKeepalive.class);

    private final DeviceSessions devices;
    private final ObserverSessions observers;
    private final Clock clock;

    SessionKeepalive(DeviceSessions devices, ObserverSessions observers, Clock clock) {
        this.devices = devices;
        this.observers = observers;
        this.clock = clock;
    }

    /** Starts tracking a new session. Its connect counts as the first pong. */
    void connected(WebSocketSession session) {
        session.getAttributes().put(LAST_PONG, new AtomicReference<>(clock.instant()));
    }

    void ponged(WebSocketSession session) {
        AtomicReference<Instant> lastPong = lastPongOf(session);
        if (lastPong != null) {
            lastPong.set(clock.instant());
        }
    }

    /** The time of the session's last pong, or null when it is not tracked. */
    static Instant lastPong(WebSocketSession session) {
        AtomicReference<Instant> lastPong = lastPongOf(session);
        return lastPong == null ? null : lastPong.get();
    }

    @Scheduled(fixedRateString = "${tip.devices.ping-interval}", initialDelayString = "${tip.devices.ping-interval}")
    void pingOrClose() {
        Instant deadline = clock.instant().minus(PONG_TIMEOUT);
        for (WebSocketSession session : allSessions()) {
            Instant lastPong = lastPong(session);
            if (lastPong != null && !lastPong.isAfter(deadline)) {
                log.debug("Session {} missed two pongs, closing it", session.getId());
                DeviceSessions.close(session, CloseStatus.GOING_AWAY);
            } else {
                ping(session);
            }
        }
    }

    @EventListener(ContextClosedEvent.class)
    void closeAllOnShutdown() {
        List<WebSocketSession> sessions = allSessions();
        log.info("Shutting down, closing {} WebSocket sessions", sessions.size());
        sessions.forEach(session -> DeviceSessions.close(session, CloseStatus.GOING_AWAY));
    }

    private List<WebSocketSession> allSessions() {
        List<WebSocketSession> sessions = new ArrayList<>(devices.all());
        sessions.addAll(observers.all());
        return sessions;
    }

    private static void ping(WebSocketSession session) {
        try {
            session.sendMessage(new PingMessage(ByteBuffer.allocate(0)));
        } catch (IOException | RuntimeException e) {
            log.debug("Pinging session {} failed", session.getId(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Instant> lastPongOf(WebSocketSession session) {
        return (AtomicReference<Instant>) session.getAttributes().get(LAST_PONG);
    }
}
