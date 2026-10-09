package dev.tip.devices;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Observer sessions by device slug, each wrapped in {@link
 * org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator}. Adding and removing go
 * through {@code compute}, so a slug's set is created and dropped atomically and no empty set stays
 * behind.
 */
@Component
class ObserverSessions {

    private static final Logger log = LoggerFactory.getLogger(ObserverSessions.class);

    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    void add(String slug, WebSocketSession session) {
        sessions.compute(slug, (key, set) -> {
            Set<WebSocketSession> observers = set != null ? set : ConcurrentHashMap.newKeySet();
            observers.add(session);
            return observers;
        });
    }

    void remove(String slug, WebSocketSession session) {
        sessions.computeIfPresent(slug, (key, set) -> {
            set.remove(session);
            return set.isEmpty() ? null : set;
        });
    }

    boolean isWatched(String slug) {
        return sessions.containsKey(slug);
    }

    boolean isEmpty() {
        return sessions.isEmpty();
    }

    void broadcast(String slug, TextMessage message) {
        Set<WebSocketSession> observers = sessions.get(slug);
        if (observers == null) {
            return;
        }
        for (WebSocketSession session : observers) {
            try {
                session.sendMessage(message);
            } catch (IOException | RuntimeException e) {
                log.debug("Sending to observer {} failed", session.getId(), e);
            }
        }
    }

    Collection<WebSocketSession> all() {
        return sessions.values().stream().flatMap(Set::stream).toList();
    }
}
