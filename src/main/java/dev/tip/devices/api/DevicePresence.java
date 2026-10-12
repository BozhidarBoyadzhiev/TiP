package dev.tip.devices.api;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Whether an owner's devices are connected. Changes arrive as {@link DeviceStatusChanged} events. */
public interface DevicePresence {

    /** How long a device must be gone before it is reported offline. */
    Duration OFFLINE_AFTER = Duration.ofSeconds(10);

    /** The owner's devices, by slug. Scoped by owner in the query. */
    List<DeviceStatus> ownedBy(UUID ownerId);
}
