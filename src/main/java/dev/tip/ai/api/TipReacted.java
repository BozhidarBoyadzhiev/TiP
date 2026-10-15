package dev.tip.ai.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published exactly once per captured tip, when its reaction is final: classified, skipped (no message
 * or AI disabled) or failed, both PLAIN. {@code repeats} (1 to 5) scales the pattern with the amount
 * charged and is always 1 for a flagged message. {@code playOnDevice} is false for a tip whose capture landed
 * more than the playback window ({@code tip.ai.playback.window}, 10 minutes) after TiP first tried to capture it, or
 * that TiP never tried to capture: it is recorded, but no device or observer plays it. Listeners
 * should use {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so they only see stored reactions.
 * Carries no message text.
 */
public record TipReacted(
        UUID tipId, UUID deviceId, Reaction reaction, int repeats, Instant reactedAt, boolean playOnDevice) {

    public static final int MIN_REPEATS = 1;
    public static final int MAX_REPEATS = 5;

    public TipReacted {
        if (repeats < MIN_REPEATS || repeats > MAX_REPEATS) {
            throw new IllegalArgumentException("repeats must be 1 to 5, was " + repeats);
        }
    }

    /** A reaction the device plays. */
    public TipReacted(UUID tipId, UUID deviceId, Reaction reaction, int repeats, Instant reactedAt) {
        this(tipId, deviceId, reaction, repeats, reactedAt, true);
    }
}
