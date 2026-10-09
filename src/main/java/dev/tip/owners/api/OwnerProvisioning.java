package dev.tip.owners.api;

import java.util.UUID;

/** Creates owner accounts. There is no public registration; seeders and admin tools use this. */
public interface OwnerProvisioning {

    /**
     * Returns the id of the owner with this email, creating the owner first when there is none. The
     * password is only hashed and stored on creation; an existing owner's password is never changed.
     */
    UUID ensureOwner(String email, String rawPassword, String displayName);
}
