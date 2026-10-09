package dev.tip.devices;

import dev.tip.owners.api.OwnerProvisioning;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * On startup, creates the demo owner when missing and makes sure the demo device exists, belongs to
 * that owner and has the hash of {@code TIP_DEMO_DEVICE_SECRET}, so changing the variable rotates the
 * secret. Running it again with the same settings changes nothing.
 */
@Component
class DemoDeviceSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDeviceSeeder.class);

    static final String GENERATE_HINT = "Generate 32 random bytes as base64url with"
            + " `openssl rand -base64 32 | tr '+/' '-_' | tr -d '='` or the PowerShell command in README.md.";

    private final DemoOwnerProperties ownerProperties;
    private final DemoDeviceProperties deviceProperties;
    private final OwnerProvisioning owners;
    private final DeviceRepository devices;
    private final Clock clock;

    DemoDeviceSeeder(
            DemoOwnerProperties ownerProperties,
            DemoDeviceProperties deviceProperties,
            OwnerProvisioning owners,
            DeviceRepository devices,
            Clock clock) {
        this.ownerProperties = ownerProperties;
        this.deviceProperties = deviceProperties;
        this.owners = owners;
        this.devices = devices;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Optional<UUID> ownerId = ownerProperties.enabled()
                ? Optional.of(owners.ensureOwner(
                        ownerProperties.email(), ownerProperties.password(), ownerProperties.displayName()))
                : Optional.empty();
        if (!deviceProperties.enabled()) {
            return;
        }
        UUID owner = ownerId.orElseThrow(() -> new IllegalStateException(
                "TIP_DEMO_DEVICE_SLUG is set, so TIP_DEMO_OWNER_EMAIL must be set too: every device needs an owner"));
        byte[] secret = DeviceSecrets.decode(deviceProperties.secret()).orElseThrow();
        if (DeviceSecrets.allBytesIdentical(secret)) {
            throw new IllegalStateException(
                    "TIP_DEMO_DEVICE_SECRET decodes to 32 identical bytes, so it is not random. " + GENERATE_HINT);
        }
        if (!DeviceSecrets.looksRandom(secret)) {
            throw new IllegalStateException("TIP_DEMO_DEVICE_SECRET has fewer than " + DeviceSecrets.MIN_DISTINCT_BYTES
                    + " distinct byte values among its 32, so it is not random. " + GENERATE_HINT);
        }
        byte[] secretHash = DeviceSecrets.hash(secret);

        Optional<Device> existing = devices.findBySlug(deviceProperties.slug());
        if (existing.isEmpty()) {
            UUID id = UUID.randomUUID();
            devices.save(new Device(
                    id, deviceProperties.slug(), deviceProperties.displayName(), owner, secretHash, clock.instant()));
            log.info("Created demo device {} with id {}", deviceProperties.slug(), id);
            return;
        }
        Device device = existing.get();
        if (!device.ownerId().equals(owner)) {
            device.assignTo(owner);
            log.info("Assigned demo device {} to owner {}", device.slug(), owner);
        }
        if (!MessageDigest.isEqual(device.secretHash(), secretHash)) {
            device.replaceSecretHash(secretHash);
            log.info("Rotated the secret of demo device {}", device.slug());
        }
        log.info("Demo device {} has id {}", device.slug(), device.id());
    }
}
