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
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * On startup, creates the demo owner when missing and the demo device when missing, owned by that owner.
 * An existing owner is never changed: not their password hash, display name or time zone. An existing
 * device keeps its name.
 *
 * <p>An existing device that belongs to another owner stays with that owner, with a warning, in every
 * profile but {@code dev}; only under {@code dev} does it move to the demo owner, so a developer can point
 * the demo owner at another account. The demo owner's own device gets the hash of {@code
 * TIP_DEMO_DEVICE_SECRET}, so changing the variable rotates the secret. Running it again with the same
 * settings changes nothing.
 */
@Component
class DemoDeviceSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDeviceSeeder.class);

    /** The local development profile, the only one under which the seeder moves an existing device. */
    static final String DEV_PROFILE = "dev";

    static final String GENERATE_HINT = "Generate 32 random bytes as base64url with"
            + " `openssl rand -base64 32 | tr '+/' '-_' | tr -d '='` or the PowerShell command in docs/SETUP.md.";

    private final DemoOwnerProperties ownerProperties;
    private final DemoDeviceProperties deviceProperties;
    private final OwnerProvisioning owners;
    private final DeviceRepository devices;
    private final Clock clock;
    private final boolean dev;

    DemoDeviceSeeder(
            DemoOwnerProperties ownerProperties,
            DemoDeviceProperties deviceProperties,
            OwnerProvisioning owners,
            DeviceRepository devices,
            Clock clock,
            Environment environment) {
        this.ownerProperties = ownerProperties;
        this.deviceProperties = deviceProperties;
        this.owners = owners;
        this.devices = devices;
        this.clock = clock;
        this.dev = environment.matchesProfiles(DEV_PROFILE);
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
            if (!dev) {
                log.warn(
                        "Demo device {} belongs to owner {}, not to the demo owner {}; it stays where it is",
                        device.slug(),
                        device.ownerId(),
                        owner);
                return;
            }
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
