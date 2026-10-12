package dev.tip.owners;

import dev.tip.shared.DomainException;
import org.springframework.http.HttpStatus;

/** A session whose owner no longer exists. */
final class OwnerNotFound extends DomainException {

    OwnerNotFound() {
        super(HttpStatus.NOT_FOUND, "OWNER_NOT_FOUND", "No owner with this id exists.");
    }
}
