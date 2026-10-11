package dev.tip.dashboard;

import dev.tip.devices.api.DeviceDirectory;
import dev.tip.devices.api.DeviceSummary;
import dev.tip.shared.TipProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * Local development only: gives the demo owner's device sample tips, so the dashboard has data without
 * real payments. The bean exists only with the {@code dev} profile, which Render never sets.
 *
 * <p>Runs on {@link ApplicationReadyEvent}, which comes after every {@code ApplicationRunner}, so the
 * demo device seeder has already created the device. Each sample has a fixed PayPal order id ({@code
 * DEV-SEED-01} and so on) and is inserted with {@code ON CONFLICT (paypal_order_id) DO NOTHING}: the
 * unique constraint on that column is what makes a second run change nothing. Captured samples are
 * stored as already classified, so the AI recovery job never picks them up, and no reaction is sent.
 */
@Component
@Profile("dev")
class DevTipSeeder {

    static final String ORDER_PREFIX = "DEV-SEED-";

    private static final Logger log = LoggerFactory.getLogger(DevTipSeeder.class);

    /** Newest first, eight hours apart, so the 40 samples cover the last 14 days. */
    static final List<Sample> SAMPLES = List.of(
            Sample.warm("CAPTURED", 500, "Lovely music, made my morning"),
            Sample.request("CAPTURED", 200, "Could you play some Oasis?", "Request: something by Oasis"),
            Sample.silent("CAPTURED", 100),
            Sample.playful("CAPTURED", 300, "Bet you cannot play that one backwards"),
            Sample.pending(500, "Thanks for the tunes"),
            Sample.warm("CAPTURED", 1000, "You brighten up this street every week"),
            Sample.request("CAPTURED", 200, "A bit too loud near the cafe tables", "Feedback: too loud near the cafe"),
            Sample.flagged("CAPTURED", 100, "Your music is rubbish, give up", "PLAIN", "insult aimed at the performer"),
            Sample.plain("CAPTURED", 200, "thanks"),
            Sample.silent("REFUNDED", 500),
            Sample.playful("CAPTURED", 200, "My dog danced, so you earned this"),
            Sample.request("CAPTURED", 500, "Play something by ABBA please", "Request: something by ABBA"),
            Sample.warm("REVERSED", 2000, "Beautiful playing, thank you"),
            Sample.silent("CAPTURED", 200),
            Sample.request("CAPTURED", 300, "Do you take song requests for weddings?", "Request: wedding booking"),
            Sample.flagged(
                    "REFUNDED",
                    5000,
                    "Nobody wants you here, go away",
                    "REQUEST",
                    "hostile, tells the performer to leave"),
            Sample.warm("CAPTURED", 300, "My kids loved it"),
            Sample.plain("CAPTURED", 100, "Keep it up"),
            Sample.playful("CAPTURED", 500, "Encore! Or I keep clapping forever"),
            Sample.pending(200, null),
            Sample.request("CAPTURED", 200, "More jazz on Sundays would be great", "Feedback: more jazz on Sundays"),
            Sample.warm("CAPTURED", 700, "Made my commute so much better"),
            Sample.silent("CAPTURED", 1000),
            Sample.request("REFUNDED", 300, "Can you play Wonderwall?", "Request: Wonderwall"),
            Sample.playful("CAPTURED", 100, "Here is a coin for the panda's bamboo fund"),
            Sample.flagged("CAPTURED", 200, "What a clown, total loser", "PLAYFUL", "name calling"),
            Sample.warm("CAPTURED", 500, "Thank you for playing in the rain"),
            Sample.plain("REVERSED", 300, "cheers"),
            Sample.request("CAPTURED", 500, "Hard to hear you over the traffic", "Feedback: hard to hear over traffic"),
            Sample.silent("CAPTURED", 300),
            Sample.playful("CAPTURED", 200, "The panda winked at me, I swear"),
            Sample.warm("CAPTURED", 10000, "Best busker in town, buy yourself dinner"),
            Sample.pending(1000, "For the guitar strings"),
            Sample.request(
                    "CAPTURED", 200, "Play happy birthday for my friend Sam?", "Request: happy birthday for Sam"),
            Sample.plain("CAPTURED", 500, "Nice"),
            Sample.warm("REFUNDED", 200, "So relaxing, thank you"),
            Sample.playful("CAPTURED", 300, "Tipping the panda, not you"),
            Sample.flagged("CAPTURED", 100, "Shut up and get a real job", "PLAIN", "insult aimed at the performer"),
            Sample.warm("CAPTURED", 500, "Wonderful as always"),
            Sample.request("CAPTURED", 200, "Any chance of some Beatles?", "Request: something by the Beatles"));

    private final JdbcClient jdbc;
    private final DeviceDirectory devices;
    private final TipProperties tipProperties;
    private final Clock clock;
    private final String demoSlug;

    DevTipSeeder(
            JdbcClient jdbc,
            DeviceDirectory devices,
            TipProperties tipProperties,
            Clock clock,
            @Value("${tip.demo-device.slug:}") String demoSlug) {
        this.jdbc = jdbc;
        this.devices = devices;
        this.tipProperties = tipProperties;
        this.clock = clock;
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
        Instant newest = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        int added = 0;
        for (int i = 0; i < SAMPLES.size(); i++) {
            Instant createdAt = newest.minus(Duration.ofHours(8L * i + 1)).minus(Duration.ofMinutes(i * 7L % 60));
            added += insert(device.get().id(), orderId(i), SAMPLES.get(i), createdAt);
        }
        log.info(
                "Added {} sample tips to demo device {} ({} were already there)",
                added,
                demoSlug,
                SAMPLES.size() - added);
    }

    static String orderId(int index) {
        return ORDER_PREFIX + String.format("%02d", index + 1);
    }

    private int insert(UUID deviceId, String orderId, Sample sample, Instant createdAt) {
        boolean captured = !sample.status().equals("PENDING");
        return jdbc.sql("""
                        INSERT INTO tip (id, device_id, amount_minor, currency, message, status, paypal_order_id,
                                         paypal_capture_id, message_kind, reaction, flagged, flag_reason, owner_note,
                                         ai_status, created_at, captured_at, reaction_at)
                        VALUES (:id, :deviceId, :amountMinor, :currency, :message, :status, :orderId,
                                :captureId, :kind, :reaction, :flagged, :flagReason, :ownerNote,
                                :aiStatus, :createdAt, :capturedAt, :capturedAt)
                        ON CONFLICT (paypal_order_id) DO NOTHING""")
                .param("id", UUID.randomUUID())
                .param("deviceId", deviceId)
                .param("amountMinor", sample.amountMinor())
                .param("currency", tipProperties.currency())
                .param("message", sample.message())
                .param("status", sample.status())
                .param("orderId", orderId)
                .param("captureId", captured ? orderId + "-CAPTURE" : null)
                .param("kind", sample.kind())
                .param("reaction", captured ? sample.reaction() : null)
                .param("flagged", sample.flagged())
                .param("flagReason", sample.flagReason())
                .param("ownerNote", sample.ownerNote())
                .param("aiStatus", sample.aiStatus())
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .param("capturedAt", captured ? createdAt.plusSeconds(20).atOffset(ZoneOffset.UTC) : null)
                .update();
    }

    /**
     * One invented tip, stored as the AI would have left it. A captured tip without a message was skipped
     * (reaction {@code PLAIN}, no kind); a pending payment has not been read yet.
     */
    record Sample(
            String status,
            long amountMinor,
            String message,
            String kind,
            boolean flagged,
            String flagReason,
            String ownerNote,
            String aiStatus) {

        static Sample warm(String status, long amountMinor, String message) {
            return new Sample(status, amountMinor, message, "WARM", false, null, null, "DONE");
        }

        static Sample playful(String status, long amountMinor, String message) {
            return new Sample(status, amountMinor, message, "PLAYFUL", false, null, null, "DONE");
        }

        static Sample plain(String status, long amountMinor, String message) {
            return new Sample(status, amountMinor, message, "PLAIN", false, null, null, "DONE");
        }

        /** A request or feedback, with the short note the AI writes for the owner. */
        static Sample request(String status, long amountMinor, String message, String ownerNote) {
            return new Sample(status, amountMinor, message, "REQUEST", false, null, ownerNote, "DONE");
        }

        static Sample flagged(String status, long amountMinor, String message, String kind, String flagReason) {
            return new Sample(status, amountMinor, message, kind, true, flagReason, null, "DONE");
        }

        static Sample silent(String status, long amountMinor) {
            return new Sample(status, amountMinor, null, null, false, null, null, "SKIPPED");
        }

        static Sample pending(long amountMinor, String message) {
            return new Sample("PENDING", amountMinor, message, null, false, null, null, "PENDING");
        }

        /** What the panda played: {@code PLAIN} when flagged or when there was nothing to read. */
        String reaction() {
            return flagged || kind == null ? "PLAIN" : kind;
        }
    }
}
