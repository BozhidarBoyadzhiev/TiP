package dev.tip.tipping.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published once per tip, by whichever path moved it from {@code CAPTURED} to {@code REFUNDED}. Listeners
 * should use {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 */
public record TipRefunded(UUID tipId, UUID deviceId, Instant refundedAt) {}
