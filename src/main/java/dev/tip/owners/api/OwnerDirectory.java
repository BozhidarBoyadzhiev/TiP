package dev.tip.owners.api;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/** Read access to owners for other features. */
public interface OwnerDirectory {

    /** The owner's profile, empty for an unknown id. */
    Optional<OwnerProfile> find(UUID ownerId);

    /** The owner's time zone, or the default for an unknown id. */
    default ZoneId timeZone(UUID ownerId) {
        return find(ownerId).map(OwnerProfile::timeZone).orElse(OwnerProfile.DEFAULT_TIME_ZONE);
    }
}
