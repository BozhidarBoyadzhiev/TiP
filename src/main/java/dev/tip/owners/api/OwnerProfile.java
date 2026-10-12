package dev.tip.owners.api;

import java.time.ZoneId;

/** What the owner's pages show about the owner. No email: the pages do not need it. */
public record OwnerProfile(String displayName, ZoneId timeZone) {

    public static final ZoneId DEFAULT_TIME_ZONE = ZoneId.of("Europe/London");
}
