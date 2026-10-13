package dev.tip.devices;

import dev.tip.devices.api.DeviceDirectory;
import dev.tip.devices.api.DeviceSummary;
import dev.tip.devices.api.VirtualDevices;
import dev.tip.owners.api.OwnerDirectory;
import dev.tip.owners.api.OwnerKind;
import dev.tip.owners.api.OwnerProfile;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeviceService implements DeviceDirectory, VirtualDevices {

    /** Crockford base32 in lowercase, so a generated slug matches the slug pattern and reads unambiguously. */
    static final String SLUG_ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";

    static final int SLUG_SUFFIX_LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceRepository devices;
    private final OwnerDirectory owners;
    private final Clock clock;

    DeviceService(DeviceRepository devices, OwnerDirectory owners, Clock clock) {
        this.devices = devices;
        this.owners = owners;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeviceSummary> findBySlug(String slug) {
        return devices.findBySlug(slug)
                .filter(d -> owners.isActive(d.ownerId()))
                .map(DeviceService::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeviceSummary> findTippableBySlug(String slug) {
        return devices.findBySlug(slug)
                .filter(d -> owners.find(d.ownerId())
                        .filter(o -> o.kind() != OwnerKind.PUBLIC_DEMO && o.activeAt(clock.instant()))
                        .isPresent())
                .map(DeviceService::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOwnedBy(UUID deviceId, UUID ownerId) {
        return devices.existsByIdAndOwnerId(deviceId, ownerId);
    }

    @Override
    @Transactional
    public DeviceSummary create(UUID ownerId, String slugPrefix, String displayName) {
        StringBuilder slug = new StringBuilder(slugPrefix);
        for (int i = 0; i < SLUG_SUFFIX_LENGTH; i++) {
            slug.append(SLUG_ALPHABET.charAt(RANDOM.nextInt(SLUG_ALPHABET.length())));
        }
        return summary(devices.saveAndFlush(virtual(ownerId, slug.toString(), displayName)));
    }

    @Override
    @Transactional
    public DeviceSummary ensure(UUID ownerId, String slug, String displayName) {
        Device device =
                devices.findBySlug(slug).orElseGet(() -> devices.saveAndFlush(virtual(ownerId, slug, displayName)));
        if (!device.ownerId().equals(ownerId)) {
            throw new IllegalStateException("Device " + slug + " belongs to another owner");
        }
        return summary(device);
    }

    /** True when the owner may connect hardware: a real owner that still exists. */
    boolean acceptsHardware(UUID ownerId) {
        return owners.find(ownerId)
                .map(OwnerProfile::kind)
                .filter(kind -> !kind.virtualOnly())
                .isPresent();
    }

    /** A random secret hash whose secret was never known, so nothing can authenticate as this device. */
    private Device virtual(UUID ownerId, String slug, String displayName) {
        byte[] unknownSecret = new byte[DeviceSecrets.SECRET_BYTES];
        RANDOM.nextBytes(unknownSecret);
        return new Device(
                UUID.randomUUID(), slug, displayName, ownerId, DeviceSecrets.hash(unknownSecret), clock.instant());
    }

    static DeviceSummary summary(Device device) {
        return new DeviceSummary(device.id(), device.slug(), device.displayName());
    }
}
