package dev.tip.devices;

import dev.tip.shared.ClientKeys;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Open observer connections per client IP (an IPv6 client by its /64, {@link ClientKeys}), at most 5. A handshake reserves a place before the upgrade;
 * the place is released when the upgrade fails or the session closes, exactly once.
 */
@Component
class ObserverConnectionLimiter {

    static final int MAX_PER_IP = 5;

    private final ConcurrentHashMap<String, Integer> open = new ConcurrentHashMap<>();

    /** A reservation for one more connection from the IP, or null when it already has the maximum. */
    Reservation tryReserve(String clientIp) {
        String key = ClientKeys.of(clientIp);
        boolean[] reserved = {false};
        open.compute(key, (ip, count) -> {
            int current = count == null ? 0 : count;
            if (current >= MAX_PER_IP) {
                return count;
            }
            reserved[0] = true;
            return current + 1;
        });
        return reserved[0] ? new Reservation(key) : null;
    }

    final class Reservation {

        private final String key;
        private final AtomicBoolean released = new AtomicBoolean();

        private Reservation(String key) {
            this.key = key;
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                open.computeIfPresent(key, (ip, count) -> count <= 1 ? null : count - 1);
            }
        }
    }
}
