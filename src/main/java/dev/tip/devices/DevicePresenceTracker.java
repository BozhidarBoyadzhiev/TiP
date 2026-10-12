package dev.tip.devices;

import dev.tip.devices.api.DevicePresence;
import dev.tip.devices.api.DeviceStatus;
import dev.tip.devices.api.DeviceStatusChanged;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tracks which devices are connected, for their owners. A device that disconnects stays reported online
 * for {@link DevicePresence#OFFLINE_AFTER}: a replaced session never disconnects the device at all, because
 * the new one is registered before the old one closes, and a device that is back within that time never
 * shows as offline. Each connect and disconnect is stored in {@code device.last_seen_at}, so the time
 * survives a restart. In memory like the session registry, so one instance only.
 */
@Component
class DevicePresenceTracker implements DevicePresence {

    private final DeviceSessions sessions;
    private final DeviceRepository devices;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** What owners were last told, by device. Absent means nothing was said since the server started. */
    private final Map<UUID, Boolean> reportedOnline = new ConcurrentHashMap<>();

    /** Devices that disconnected and are not reported offline yet, with the moment they went. */
    private final Map<UUID, Instant> goneSince = new ConcurrentHashMap<>();

    DevicePresenceTracker(
            DeviceSessions sessions, DeviceRepository devices, ApplicationEventPublisher events, Clock clock) {
        this.sessions = sessions;
        this.devices = devices;
        this.events = events;
        this.clock = clock;
    }

    /** Called once the device's new session is registered. */
    void connected(UUID deviceId) {
        Instant now = clock.instant();
        goneSince.remove(deviceId);
        devices.markSeen(deviceId, now);
        if (!Boolean.TRUE.equals(reportedOnline.put(deviceId, true))) {
            publish(deviceId, true, now);
        }
    }

    /** Called when a session closes. Nothing happens while the device has another session. */
    void disconnected(UUID deviceId) {
        if (sessions.find(deviceId).isPresent()) {
            return;
        }
        Instant now = clock.instant();
        devices.markSeen(deviceId, now);
        goneSince.put(deviceId, now);
    }

    /** Reports the devices offline that have been gone long enough and did not come back. */
    @Scheduled(
            fixedDelayString = "${tip.devices.presence-interval}",
            initialDelayString = "${tip.devices.presence-interval}")
    void reportOffline() {
        Instant deadline = clock.instant().minus(OFFLINE_AFTER);
        goneSince.forEach((deviceId, since) -> {
            if (!since.isAfter(deadline) && sessions.find(deviceId).isEmpty() && goneSince.remove(deviceId, since)) {
                reportedOnline.put(deviceId, false);
                publish(deviceId, false, since);
            }
        });
    }

    @Override
    public List<DeviceStatus> ownedBy(UUID ownerId) {
        return devices.findByOwnerIdOrderBySlug(ownerId).stream()
                .map(device -> {
                    WebSocketSession session = sessions.find(device.id()).orElse(null);
                    Instant lastPong = session == null ? null : SessionKeepalive.lastPong(session);
                    boolean online = session != null || goneSince.containsKey(device.id());
                    return new DeviceStatus(
                            device.id(),
                            device.slug(),
                            device.displayName(),
                            online,
                            lastPong != null ? lastPong : device.lastSeenAt());
                })
                .toList();
    }

    private void publish(UUID deviceId, boolean online, Instant at) {
        devices.findById(deviceId)
                .ifPresent(device -> events.publishEvent(
                        new DeviceStatusChanged(deviceId, device.ownerId(), device.slug(), online, at)));
    }
}
