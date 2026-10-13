package dev.tip.dashboard;

import dev.tip.dashboard.api.SampleTips;
import dev.tip.devices.api.DeviceDirectory;
import dev.tip.devices.api.DeviceSummary;
import dev.tip.owners.api.OwnerDirectory;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local development only: gives the demo owner's device the sample tips of {@link SampleTipSet}, the same ones every
 * sandbox and the public demo get, so the dashboard has data without real payments. The bean exists only with the
 * {@code dev} profile, which Render never sets.
 *
 * <p>Runs on {@link ApplicationReadyEvent}, which comes after every {@code ApplicationRunner}, so the demo device
 * seeder has already created the device. A start on a day that already has samples changes nothing; a start on a
 * later day replaces the samples and the demo owner's notes, so "today" has content again. Real tips stay.
 */
@Component
@Profile("dev")
class DevTipSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevTipSeeder.class);

    private final SampleTips samples;
    private final JdbcClient jdbc;
    private final OwnerDirectory owners;
    private final DeviceDirectory devices;
    private final String demoSlug;

    DevTipSeeder(
            SampleTips samples,
            JdbcClient jdbc,
            OwnerDirectory owners,
            DeviceDirectory devices,
            @Value("${tip.demo-device.slug:}") String demoSlug) {
        this.samples = samples;
        this.jdbc = jdbc;
        this.owners = owners;
        this.devices = devices;
        this.demoSlug = demoSlug.strip();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    void seed() {
        Optional<DeviceSummary> device = demoSlug.isEmpty() ? Optional.empty() : devices.findBySlug(demoSlug);
        if (device.isEmpty()) {
            log.warn("No demo device, so no sample tips were added. Set TIP_DEMO_DEVICE_SLUG to get them.");
            return;
        }
        UUID ownerId = jdbc.sql("SELECT owner_id FROM device WHERE id = :id")
                .param("id", device.get().id())
                .query(UUID.class)
                .single();
        if (samples.refresh(ownerId, device.get().id(), owners.timeZone(ownerId))) {
            log.info(
                    "Added {} sample tips and today's panda's notes to demo device {}",
                    SampleTipSet.SAMPLES.size(),
                    demoSlug);
        }
    }
}
