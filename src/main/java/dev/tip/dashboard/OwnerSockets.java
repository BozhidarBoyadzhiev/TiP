package dev.tip.dashboard;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * The owner channel's open sockets, by owner, and the messages of {@code docs/OWNER_CHANNEL.md}. Messages
 * carry ids and states only, never tip content: the page fetches a tip through the owner API, so
 * authorisation stays in one place. At most {@link #MAX_PER_OWNER} sockets per owner; a new one closes the
 * oldest. {@code today} is sent at most once per {@code tip.dashboard.today-interval} (1 s) per owner: a
 * change marks the owner, and {@link #flushToday} sends one {@code today} to each marked owner, so a burst
 * of changes in between rides along. Sessions are stored wrapped in a {@link
 * org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator}. Changes to an owner's
 * sockets go through {@code compute}, so adding, evicting and removing are atomic per owner.
 */
@Component
class OwnerSockets {

    static final int MAX_PER_OWNER = 5;

    static final CloseStatus REPLACED = new CloseStatus(4000, "replaced");
    static final CloseStatus SIGNED_OUT = new CloseStatus(4001, "signed out");
    static final CloseStatus SESSION_EXPIRED = new CloseStatus(4002, "session expired");
    static final CloseStatus SANDBOX_EXPIRED = new CloseStatus(4003, "sandbox expired");
    static final CloseStatus READ_ONLY = new CloseStatus(4004, "read only");

    private static final Logger log = LoggerFactory.getLogger(OwnerSockets.class);

    private final ConcurrentHashMap<UUID, Deque<WebSocketSession>> byOwner = new ConcurrentHashMap<>();
    private final Set<UUID> todayPending = ConcurrentHashMap.newKeySet();
    private final JsonMapper json;

    OwnerSockets(JsonMapper json) {
        this.json = json;
    }

    /** Adds the socket and closes the owner's oldest when there are more than five. */
    void add(UUID ownerId, WebSocketSession session) {
        List<WebSocketSession> evicted = new ArrayList<>();
        byOwner.compute(ownerId, (key, sockets) -> {
            Deque<WebSocketSession> open = sockets != null ? sockets : new ConcurrentLinkedDeque<>();
            open.addLast(session);
            while (open.size() > MAX_PER_OWNER) {
                evicted.add(open.pollFirst());
            }
            return open;
        });
        evicted.forEach(oldest -> closeWithReason(oldest, "replaced", REPLACED));
    }

    void remove(UUID ownerId, WebSocketSession session) {
        byOwner.computeIfPresent(ownerId, (key, sockets) -> {
            sockets.remove(session);
            return sockets.isEmpty() ? null : sockets;
        });
    }

    int count(UUID ownerId) {
        Deque<WebSocketSession> sockets = byOwner.get(ownerId);
        return sockets == null ? 0 : sockets.size();
    }

    boolean hasSockets(UUID ownerId) {
        return byOwner.containsKey(ownerId);
    }

    /** Every open socket with its owner. */
    List<Owned> all() {
        List<Owned> all = new ArrayList<>();
        byOwner.forEach((ownerId, sockets) -> sockets.forEach(session -> all.add(new Owned(ownerId, session))));
        return all;
    }

    void tipChanged(UUID ownerId, UUID tipId) {
        send(ownerId, new TipChanged("tip", tipId.toString()));
    }

    void deviceChanged(UUID ownerId, String slug, boolean online, Instant lastSeenAt) {
        send(
                ownerId,
                new DeviceChanged(
                        "device",
                        slug,
                        online,
                        lastSeenAt == null
                                ? null
                                : lastSeenAt.truncatedTo(ChronoUnit.SECONDS).toString()));
    }

    void digestChanged(UUID ownerId) {
        send(ownerId, new Signal("digest"));
    }

    /** Marks the owner for the next {@code today}, if they have a socket to send it to. */
    void todayChanged(UUID ownerId) {
        if (hasSockets(ownerId)) {
            todayPending.add(ownerId);
        }
    }

    /** Sends one {@code today} to each owner marked since the last run. */
    @Scheduled(
            fixedDelayString = "${tip.dashboard.today-interval}",
            initialDelayString = "${tip.dashboard.today-interval}")
    void flushToday() {
        for (UUID ownerId : List.copyOf(todayPending)) {
            if (todayPending.remove(ownerId)) {
                send(ownerId, new Signal("today"));
            }
        }
    }

    /** Closes every socket of the owner, each told why first. */
    void closeAll(UUID ownerId, String reason, CloseStatus status) {
        Deque<WebSocketSession> sockets = byOwner.remove(ownerId);
        if (sockets != null) {
            List.copyOf(sockets).forEach(session -> closeWithReason(session, reason, status));
        }
    }

    /**
     * Tells the page why before closing, because Render's proxy delivers every close as 1005 and the code
     * never reaches the browser. A failed send does not stop the close.
     */
    void closeWithReason(WebSocketSession session, String reason, CloseStatus status) {
        try {
            session.sendMessage(text(new Closing("closing", reason)));
        } catch (IOException | RuntimeException e) {
            log.debug("Sending closing to owner socket {} failed", session.getId(), e);
        }
        close(session, status);
    }

    static void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("Closing owner socket {} failed", session.getId(), e);
        }
    }

    private void send(UUID ownerId, Object message) {
        Deque<WebSocketSession> sockets = byOwner.get(ownerId);
        if (sockets == null) {
            return;
        }
        TextMessage text = text(message);
        for (WebSocketSession session : List.copyOf(sockets)) {
            try {
                session.sendMessage(text);
            } catch (IOException | RuntimeException e) {
                log.debug("Sending to owner socket {} failed", session.getId(), e);
            }
        }
    }

    private TextMessage text(Object message) {
        return new TextMessage(json.writeValueAsString(message));
    }

    record Owned(UUID ownerId, WebSocketSession session) {}

    record TipChanged(String type, String id) {}

    record DeviceChanged(String type, String slug, boolean online, String lastSeenAt) {}

    record Signal(String type) {}

    record Closing(String type, String reason) {}
}
