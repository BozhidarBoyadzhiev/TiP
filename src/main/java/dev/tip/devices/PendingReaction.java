package dev.tip.devices;

import dev.tip.ai.api.Reaction;
import java.time.Instant;
import java.util.UUID;

/** A reaction to deliver to a device. {@code repeats} is stored with it, so a replay sends the same value. */
record PendingReaction(UUID tipId, UUID deviceId, Reaction reaction, int repeats, Instant reactedAt) {}
