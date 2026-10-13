package dev.tip.owners.api;

import java.time.Instant;
import java.time.ZoneId;

/**
 * What other features need to know about an owner. No email: nothing outside owners needs it. {@code expiresAt}
 * is set for a sandbox only.
 */
public record OwnerProfile(String displayName, ZoneId timeZone, OwnerKind kind, Instant expiresAt) {

    public static final ZoneId DEFAULT_TIME_ZONE = ZoneId.of("Europe/London");

    /** False once a sandbox has expired; its pages and devices then answer like unknown ones. */
    public boolean activeAt(Instant now) {
        return expiresAt == null || now.isBefore(expiresAt);
    }
}
