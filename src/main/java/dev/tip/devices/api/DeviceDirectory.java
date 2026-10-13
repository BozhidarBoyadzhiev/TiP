package dev.tip.devices.api;

import java.util.Optional;
import java.util.UUID;

/** Read access to devices for other features. */
public interface DeviceDirectory {

    /**
     * The device with this slug. Empty for an unknown slug and for a device whose sandbox owner has expired, so its
     * tip and watch pages answer like an unknown device's.
     */
    Optional<DeviceSummary> findBySlug(String slug);

    /** Returns the device or throws {@link DeviceNotFoundException}. */
    default DeviceSummary getBySlug(String slug) {
        return findBySlug(slug).orElseThrow(DeviceNotFoundException::new);
    }

    /**
     * A device that takes tips: {@link #findBySlug}, except the public demo's panda, which only shows sample tips,
     * so nobody can put real messages on the shared demo dashboard.
     */
    Optional<DeviceSummary> findTippableBySlug(String slug);

    /** Returns the tippable device or throws {@link DeviceNotFoundException}. */
    default DeviceSummary getTippableBySlug(String slug) {
        return findTippableBySlug(slug).orElseThrow(DeviceNotFoundException::new);
    }

    /** True when the device exists and belongs to the owner. */
    boolean isOwnedBy(UUID deviceId, UUID ownerId);
}
