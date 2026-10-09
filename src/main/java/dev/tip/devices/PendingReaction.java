package dev.tip.devices;

import dev.tip.ai.api.Reaction;
import java.time.Instant;
import java.util.UUID;

/** A reaction to deliver to a device. */
record PendingReaction(UUID tipId, UUID deviceId, Reaction reaction, Instant reactedAt) {}
