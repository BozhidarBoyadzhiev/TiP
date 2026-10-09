package dev.tip.ai.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published exactly once per captured tip, when its reaction is final: classified, skipped (no message
 * or AI disabled) or failed, both PLAIN. Listeners should use {@code
 * @TransactionalEventListener(phase = AFTER_COMMIT)} so they only see stored reactions. Carries no
 * message text.
 */
public record TipReacted(UUID tipId, UUID deviceId, Reaction reaction, Instant reactedAt) {}
