package dev.tip.owners.api;

import java.util.UUID;

/** Published when an owner signs out, on the request thread, outside any transaction. */
public record OwnerSignedOut(UUID ownerId) {}
