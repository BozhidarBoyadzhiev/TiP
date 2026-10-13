package dev.tip.owners;

import dev.tip.dashboard.api.SampleTips;
import dev.tip.devices.api.DeviceSummary;
import dev.tip.devices.api.VirtualDevices;
import dev.tip.owners.api.OwnerKind;
import dev.tip.owners.api.OwnerPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sandbox owners. One transaction makes the owner, its virtual panda and the sample tips, so a failure leaves
 * nothing behind. A sandbox lives 72 hours, never past its code's expiry.
 */
@Service
class Sandboxes {

    static final Duration LIFETIME = Duration.ofHours(72);
    static final String DISPLAY_NAME = "Judge";
    static final String SLUG_PREFIX = "judge-";
    static final String DEVICE_NAME = "TiP";

    private final OwnerRepository owners;
    private final VirtualDevices devices;
    private final SampleTips samples;
    private final Clock clock;

    Sandboxes(OwnerRepository owners, VirtualDevices devices, SampleTips samples, Clock clock) {
        this.owners = owners;
        this.devices = devices;
        this.samples = samples;
        this.clock = clock;
    }

    @Transactional
    OwnerPrincipal create(AccessCodes.Entry code, ZoneId zone) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant limit = now.plus(LIFETIME);
        Instant expiresAt = code.expiresAt().isBefore(limit) ? code.expiresAt() : limit;
        Owner owner =
                owners.saveAndFlush(Owner.sandbox(UUID.randomUUID(), DISPLAY_NAME, zone, code.label(), now, expiresAt));
        DeviceSummary device = devices.create(owner.id(), SLUG_PREFIX, DEVICE_NAME);
        samples.seed(owner.id(), device.id(), zone);
        return principal(owner);
    }

    /** The sandbox owner, while it has not expired. */
    @Transactional(readOnly = true)
    Optional<OwnerPrincipal> active(UUID ownerId) {
        Instant now = clock.instant();
        return owners.findById(ownerId)
                .filter(o -> o.kind() == OwnerKind.SANDBOX && now.isBefore(o.expiresAt()))
                .map(Sandboxes::principal);
    }

    static OwnerPrincipal principal(Owner owner) {
        return new OwnerPrincipal(owner.id(), null, owner.displayName(), null, owner.kind(), owner.expiresAt());
    }
}
