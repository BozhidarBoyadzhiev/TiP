package dev.tip.owners;

import dev.tip.dashboard.api.SampleTips;
import dev.tip.devices.api.DeviceSummary;
import dev.tip.devices.api.VirtualDevices;
import dev.tip.owners.api.OwnerKind;
import dev.tip.owners.api.OwnerPrincipal;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The shared read only demo dashboard: one {@code PUBLIC_DEMO} owner with a virtual panda, made at startup when
 * missing, and its sample tips, replaced every night at 00:05 London time so "today" stays current. Startup also
 * replaces samples that are not from today, so a missed night (the app was down) heals on the next start. Both run
 * under one PostgreSQL advisory lock, so two instances overlapping during a deploy seed once.
 */
@Component
class PublicDemo implements ApplicationRunner {

    static final String DISPLAY_NAME = "TiP demo";
    static final String DEVICE_SLUG = "public-demo";
    static final String DEVICE_NAME = "TiP";
    static final ZoneId ZONE = ZoneId.of("Europe/London");

    /** Any constant; it only has to be the same for every instance. */
    private static final long LOCK = 0x7469_7064_656d_6fL;

    private static final Logger log = LoggerFactory.getLogger(PublicDemo.class);

    private final OwnerRepository owners;
    private final VirtualDevices devices;
    private final SampleTips samples;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    PublicDemo(
            OwnerRepository owners,
            VirtualDevices devices,
            SampleTips samples,
            JdbcClient jdbc,
            TransactionTemplate transactions,
            Clock clock) {
        this.owners = owners;
        this.devices = devices;
        this.samples = samples;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        refresh();
    }

    @Scheduled(cron = "${tip.owners.demo-reseed-cron}", zone = "Europe/London")
    void nightly() {
        refresh();
    }

    /** Makes the owner and device when missing, then replaces the samples unless they are from today. */
    void refresh() {
        boolean seeded = Boolean.TRUE.equals(transactions.execute(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:lock)")
                    .param("lock", LOCK)
                    .query()
                    .listOfRows();
            UUID ownerId = ensureOwner();
            DeviceSummary device = devices.ensure(ownerId, DEVICE_SLUG, DEVICE_NAME);
            return samples.refresh(ownerId, device.id(), ZONE);
        }));
        if (seeded) {
            log.info("Seeded the public demo's sample tips");
        }
    }

    /** The demo owner, read only. Empty only before the first startup run. */
    Optional<OwnerPrincipal> principal() {
        return owners.findByKind(OwnerKind.PUBLIC_DEMO)
                .map(o -> new OwnerPrincipal(o.id(), null, o.displayName(), null, OwnerKind.PUBLIC_DEMO, null));
    }

    private UUID ensureOwner() {
        jdbc.sql("""
                        INSERT INTO owner (id, display_name, created_at, kind, time_zone)
                        VALUES (:id, :name, :now, 'PUBLIC_DEMO', :zone)
                        ON CONFLICT (kind) WHERE kind = 'PUBLIC_DEMO' DO NOTHING""")
                .param("id", UUID.randomUUID())
                .param("name", DISPLAY_NAME)
                .param("now", clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                .param("zone", ZONE.getId())
                .update();
        return jdbc.sql("SELECT id FROM owner WHERE kind = 'PUBLIC_DEMO'")
                .query(UUID.class)
                .single();
    }
}
