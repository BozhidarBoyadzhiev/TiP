package dev.tip.devices.api;

import java.util.UUID;

/**
 * Devices without hardware, for sandbox owners and the public demo. Each gets a random secret hash nobody knows,
 * and the device channel turns them away anyway because their owner's kind is virtual only. Their tip and watch
 * pages work like any other device's. Joins the caller's transaction.
 */
public interface VirtualDevices {

    /** A new device of the owner whose slug is {@code slugPrefix} and 8 random lowercase base32 characters. */
    DeviceSummary create(UUID ownerId, String slugPrefix, String displayName);

    /**
     * The device with {@code slug}, created for the owner when there is none. Fails when the slug belongs to
     * another owner.
     */
    DeviceSummary ensure(UUID ownerId, String slug, String displayName);
}
