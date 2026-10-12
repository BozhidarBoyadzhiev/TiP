package dev.tip.devices;

import dev.tip.devices.ChannelMessages.ClosingReason;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * The one live session per device. Sessions are stored wrapped in {@link
 * ConcurrentWebSocketSessionDecorator}, because a raw session must not be sent to from two threads and
 * the dispatcher, the resend job, the keepalive and the connect replay all send. Every send to a device
 * goes through that one decorator, which sends in the order the sends were made.
 *
 * <p>A session is registered pending and becomes ready once the handler has sent {@code hello}. Live
 * sends ({@link #find}, {@link #connectedDevices}) see only ready sessions, so {@code hello} is always
 * the first message a device gets; a reaction stored while its session is pending reaches it through the
 * connect replay, which runs after the session is ready.
 *
 * <p>A new connection replaces the old one, which gets {@code closing} with reason {@code replaced} and is
 * closed with 4000. The old session's close callback runs later and removes only its own entry ({@code
 * remove(key, value)}), so it can never evict the new session.
 */
@Component
class DeviceSessions {

    static final CloseStatus REPLACED = new CloseStatus(4000, "replaced");

    static final int SEND_TIME_LIMIT_MILLIS = 5_000;
    static final int BUFFER_SIZE_LIMIT_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(DeviceSessions.class);

    private final ConcurrentHashMap<UUID, Entry> sessions = new ConcurrentHashMap<>();
    private final ChannelMessages messages;

    DeviceSessions(ChannelMessages messages) {
        this.messages = messages;
    }

    static WebSocketSession concurrent(WebSocketSession session) {
        return new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MILLIS, BUFFER_SIZE_LIMIT_BYTES);
    }

    /** Registers the device's session as pending and closes the one it replaces. */
    void register(UUID deviceId, WebSocketSession session) {
        Entry replaced = sessions.put(deviceId, new Entry(session));
        if (replaced != null && replaced.session() != session) {
            log.info("Device {} connected again, closing its previous session", deviceId);
            closeWithReason(replaced.session(), ClosingReason.REPLACED, REPLACED);
        }
    }

    /** Opens the session to live sends, once {@code hello} has gone out. */
    void markReady(UUID deviceId, WebSocketSession session) {
        Entry entry = sessions.get(deviceId);
        if (entry != null && entry.session() == session) {
            entry.ready().set(true);
        }
    }

    /** Removes the session only if it is still the device's current one. */
    void unregister(UUID deviceId, WebSocketSession session) {
        sessions.computeIfPresent(deviceId, (id, entry) -> entry.session() == session ? null : entry);
    }

    /** The device's session if it is ready for live sends. */
    Optional<WebSocketSession> find(UUID deviceId) {
        return Optional.ofNullable(sessions.get(deviceId))
                .filter(entry -> entry.ready().get())
                .map(Entry::session);
    }

    /** The device's session, pending or ready: whether the device is connected at all. */
    Optional<WebSocketSession> registered(UUID deviceId) {
        return Optional.ofNullable(sessions.get(deviceId)).map(Entry::session);
    }

    /** Devices whose session is ready for live sends. */
    List<UUID> connectedDevices() {
        return sessions.entrySet().stream()
                .filter(e -> e.getValue().ready().get())
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Every session, pending or ready, for the keepalive. */
    Collection<WebSocketSession> all() {
        return sessions.values().stream().map(Entry::session).toList();
    }

    /**
     * Tells the device why with a {@code closing} message, then closes with the status. The close code
     * alone does not reach the device through Render's proxy. A failed send does not stop the close.
     */
    void closeWithReason(WebSocketSession session, ClosingReason reason, CloseStatus status) {
        try {
            session.sendMessage(messages.closing(reason));
        } catch (IOException | RuntimeException e) {
            log.debug("Sending closing to session {} failed", session.getId(), e);
        }
        close(session, status);
    }

    private record Entry(WebSocketSession session, AtomicBoolean ready) {

        Entry(WebSocketSession session) {
            this(session, new AtomicBoolean());
        }
    }

    static void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("Closing session {} failed", session.getId(), e);
        }
    }
}
