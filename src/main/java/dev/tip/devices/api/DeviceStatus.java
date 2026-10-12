package dev.tip.devices.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A device as its owner's dashboard shows it. {@code online} turns false only once the device has been
 * gone {@link DevicePresence#OFFLINE_AFTER}, so a replacement or a quick reconnect never shows as offline.
 * {@code lastSeenAt} is its last pong or connect while connected, the moment it went once it is gone, and
 * null when it never connected.
 */
public record DeviceStatus(UUID deviceId, String slug, String name, boolean online, Instant lastSeenAt) {}
