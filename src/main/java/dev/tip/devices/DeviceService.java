package dev.tip.devices;

import dev.tip.devices.api.DeviceDirectory;
import dev.tip.devices.api.DeviceSummary;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeviceService implements DeviceDirectory {

    private final DeviceRepository devices;

    DeviceService(DeviceRepository devices) {
        this.devices = devices;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeviceSummary> findBySlug(String slug) {
        return devices.findBySlug(slug).map(DeviceService::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOwnedBy(UUID deviceId, UUID ownerId) {
        return devices.existsByIdAndOwnerId(deviceId, ownerId);
    }

    static DeviceSummary summary(Device device) {
        return new DeviceSummary(device.id(), device.slug(), device.displayName());
    }
}
