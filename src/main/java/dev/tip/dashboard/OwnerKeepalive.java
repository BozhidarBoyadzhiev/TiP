package dev.tip.dashboard;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Keeps owner sockets honest, like the device and observer keepalive: every 20 seconds each socket is
 * checked and pinged. When the HTTP session behind a socket ends (it expires, or is invalidated), Tomcat
 * closes the socket at once with 1008, since the handshake was authenticated. This check backs that up: a
 * socket whose HTTP session is gone or no longer holds a signed in owner gets {@code closing} {@code
 * session_expired} and is closed with 4002. A socket whose last pong is 45 seconds old is closed with
 * 1001. On shutdown every socket is closed with 1001.
 */
@Component
class OwnerKeepalive {

    static final Duration PONG_TIMEOUT = Duration.ofSeconds(45);

    private static final String LAST_PONG = OwnerKeepalive.class.getName() + ".lastPong";

    private static final Logger log = LoggerFactory.getLogger(OwnerKeepalive.class);

    private final OwnerSockets sockets;
    private final Clock clock;

    OwnerKeepalive(OwnerSockets sockets, Clock clock) {
        this.sockets = sockets;
        this.clock = clock;
    }

    void connected(WebSocketSession raw) {
        raw.getAttributes().put(LAST_PONG, new AtomicReference<>(clock.instant()));
    }

    void ponged(WebSocketSession raw) {
        AtomicReference<Instant> lastPong = lastPongOf(raw);
        if (lastPong != null) {
            lastPong.set(clock.instant());
        }
    }

    @Scheduled(
            fixedRateString = "${tip.dashboard.ping-interval}",
            initialDelayString = "${tip.dashboard.ping-interval}")
    void pingOrClose() {
        Instant deadline = clock.instant().minus(PONG_TIMEOUT);
        for (OwnerSockets.Owned owned : sockets.all()) {
            WebSocketSession session = owned.session();
            AtomicReference<Instant> lastPong = lastPongOf(session);
            if (!signedIn((HttpSession) session.getAttributes().get(OwnerHandshakeInterceptor.HTTP_SESSION))) {
                log.debug("Owner socket {} outlived its HTTP session, closing it", session.getId());
                sockets.closeWithReason(session, "session_expired", OwnerSockets.SESSION_EXPIRED);
            } else if (lastPong != null && !lastPong.get().isAfter(deadline)) {
                log.debug("Owner socket {} missed two pongs, closing it", session.getId());
                OwnerSockets.close(session, CloseStatus.GOING_AWAY);
            } else {
                ping(session);
            }
        }
    }

    @EventListener(ContextClosedEvent.class)
    void closeAllOnShutdown() {
        sockets.all().forEach(owned -> OwnerSockets.close(owned.session(), CloseStatus.GOING_AWAY));
    }

    /** True while the HTTP session is valid and still holds a signed in owner. */
    static boolean signedIn(HttpSession httpSession) {
        if (httpSession == null) {
            return false;
        }
        try {
            return httpSession.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null;
        } catch (IllegalStateException invalidated) {
            return false;
        }
    }

    private static void ping(WebSocketSession session) {
        try {
            session.sendMessage(new PingMessage(ByteBuffer.allocate(0)));
        } catch (IOException | RuntimeException e) {
            log.debug("Pinging owner socket {} failed", session.getId(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Instant> lastPongOf(WebSocketSession session) {
        return (AtomicReference<Instant>) session.getAttributes().get(LAST_PONG);
    }
}
