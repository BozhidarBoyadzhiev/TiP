package dev.tip.devices;

import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the device named by {@code TIP_DEMO_DEVICE_SLUG} on startup when it does not exist yet. */
@Component
class DemoDeviceSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDeviceSeeder.class);

    private final DemoDeviceProperties properties;
    private final DeviceRepository devices;
    private final Clock clock;

    DemoDeviceSeeder(DemoDeviceProperties properties, DeviceRepository devices, Clock clock) {
        this.properties = properties;
        this.devices = devices;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.enabled() || devices.existsBySlug(properties.slug())) {
            return;
        }
        devices.save(new Device(UUID.randomUUID(), properties.slug(), properties.displayName(), clock.instant()));
        log.info("Created demo device {}", properties.slug());
    }
}
