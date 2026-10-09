package dev.tip.tipping.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published once per tip, by whichever path moved it to {@code CAPTURED}. Listeners should use
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so they only see committed captures.
 */
public record TipCaptured(UUID tipId, UUID deviceId, Instant capturedAt) {}
