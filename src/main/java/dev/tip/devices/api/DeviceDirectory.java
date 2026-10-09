package dev.tip.devices.api;

import java.util.Optional;

/** Read access to devices for other features. */
public interface DeviceDirectory {

    Optional<DeviceSummary> findBySlug(String slug);

    /** Returns the device or throws {@link DeviceNotFoundException}. */
    default DeviceSummary getBySlug(String slug) {
        return findBySlug(slug).orElseThrow(DeviceNotFoundException::new);
    }
}
