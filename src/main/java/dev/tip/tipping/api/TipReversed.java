package dev.tip.tipping.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published once per tip when a chargeback moved it from {@code CAPTURED} to {@code REVERSED}. Listeners
 * should use {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 */
public record TipReversed(UUID tipId, UUID deviceId, Instant reversedAt) {}
