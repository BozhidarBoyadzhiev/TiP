package dev.tip.devices.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a device's reported presence changes: online on connect when it was reported offline or
 * nothing was reported since the server started, offline once it has been gone {@link
 * DevicePresence#OFFLINE_AFTER}. Published outside any transaction. {@code lastSeenAt} is the connect, or
 * the moment it went.
 */
public record DeviceStatusChanged(UUID deviceId, UUID ownerId, String slug, boolean online, Instant lastSeenAt) {}
