package dev.tip.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.ai.api.ReactionRepeats;
import dev.tip.devices.ResendJobRunner;
import dev.tip.devices.TestSocket;
import dev.tip.owners.api.OwnerPrincipal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Runs the app with the {@code dev} profile on top of {@code test}, so the seeder ran at startup. */
@ActiveProfiles("dev")
class DevTipSeederTest extends IntegrationTest {

    @Autowired
    DevTipSeeder seeder;

    @Autowired
    TestData data;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc mvc;

    @Autowired
    ResendJobRunner resendJob;

    @LocalServerPort
    int port;

    @Test
    void startupAddedEverySampleToTheDemoDeviceOnce() {
        List<Map<String, Object>> rows = seededRows();

        assertThat(rows).hasSize(DevTipSeeder.sampleCount()).hasSizeBetween(45, 55);
        assertThat(rows)
                .extracting(r -> r.get("device_id"))
                .containsOnly(data.deviceRow("demo-panda").get("id"));
        assertThat(rows).extracting(r -> r.get("message_kind")).contains("WARM", "PLAYFUL", "REQUEST", "PLAIN");
        assertThat(rows).extracting(r -> r.get("status")).contains("CAPTURED", "PENDING", "REFUNDED", "REVERSED");
        assertThat(rows).filteredOn(r -> r.get("message") == null).hasSizeGreaterThanOrEqualTo(3);
        assertThat(rows.stream()
                        .map(r -> (Long) r.get("amount_minor"))
                        .distinct()
                        .count())
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    void flaggedSamplesHaveAReasonAndRequestsHaveNotes() {
        List<Map<String, Object>> rows = seededRows();

        List<Map<String, Object>> flagged =
                rows.stream().filter(r -> (Boolean) r.get("flagged")).toList();
        assertThat(flagged).hasSizeGreaterThanOrEqualTo(3).allSatisfy(r -> {
            assertThat(r.get("flag_reason")).isNotNull();
            assertThat(r.get("owner_note")).isNull();
            assertThat(r.get("reaction")).isEqualTo("PLAIN");
        });
        assertThat(rows)
                .filteredOn(r -> "REQUEST".equals(r.get("message_kind")) && !(Boolean) r.get("flagged"))
                .hasSizeGreaterThanOrEqualTo(5)
                .allSatisfy(r -> assertThat((String) r.get("owner_note"))
                        .matches("(Request|Feedback): .+")
                        .hasSizeLessThanOrEqualTo(100));
        assertThat(rows)
                .filteredOn(r -> r.get("owner_note") != null)
                .allSatisfy(r -> assertThat(r.get("message_kind")).isEqualTo("REQUEST"));
    }

    @Test
    void samplesSpreadOverTheLastFourteenDays() {
        List<Instant> times = seededRows().stream()
                .map(r -> ((Timestamp) r.get("created_at")).toInstant())
                .toList();
        Instant now = clock.instant();

        assertThat(times).allSatisfy(t -> assertThat(t).isBetween(now.minus(Duration.ofDays(14)), now));
        Instant oldest = times.stream().min(Instant::compareTo).orElseThrow();
        Instant newest = times.stream().max(Instant::compareTo).orElseThrow();
        assertThat(Duration.between(oldest, newest)).isGreaterThan(Duration.ofDays(12));
    }

    @Test
    void capturedSamplesAreAlreadyClassifiedSoTheAiNeverPicksThemUp() {
        assertThat(seededRows())
                .filteredOn(r -> "CAPTURED".equals(r.get("status")))
                .allSatisfy(r -> {
                    assertThat(r.get("ai_status")).isIn("DONE", "SKIPPED");
                    assertThat(r.get("reaction")).isNotNull();
                    assertThat(r.get("paypal_capture_id")).isNotNull();
                });
    }

    @Test
    void secondRunOnALaterDayChangesNothing() {
        List<Map<String, Object>> before = seededRows();
        clock.pin(clock.instant().plus(Duration.ofDays(3)));

        List<Map<String, Object>> deliveriesBefore = seededDeliveries();
        seeder.seed();

        assertThat(seededRows()).usingRecursiveComparison().isEqualTo(before);
        assertThat(seededDeliveries()).usingRecursiveComparison().isEqualTo(deliveriesBefore);
    }

    @Test
    void reactedSamplesHaveAnAckedOldDeliveryAtTheSendLimitWithRepeatsFromTheAmountTable() {
        List<Map<String, Object>> tips = seededRows();
        List<Map<String, Object>> deliveries = seededDeliveries();
        List<String> withoutDelivery = DevTipSeeder.WITHOUT_DELIVERY.stream()
                .map(DevTipSeeder::orderId)
                .toList();

        long reacted = tips.stream().filter(r -> r.get("reaction") != null).count();
        assertThat(deliveries).hasSize((int) reacted - withoutDelivery.size());
        assertThat(deliveries).extracting(d -> d.get("paypal_order_id")).doesNotContainAnyElementsOf(withoutDelivery);
        assertThat(tips)
                .filteredOn(r -> withoutDelivery.contains(r.get("paypal_order_id")))
                .hasSize(3)
                .allSatisfy(r -> assertThat(r.get("reaction")).isNotNull());
        Instant replayWindow = clock.instant().minus(Duration.ofSeconds(60));
        assertThat(deliveries).allSatisfy(d -> {
            assertThat(d.get("acked_at")).isNotNull();
            assertThat((Integer) d.get("sent_count")).isGreaterThanOrEqualTo(DevTipSeeder.SEEDED_SEND_COUNT);
            assertThat(((Timestamp) d.get("reacted_at")).toInstant()).isBefore(replayWindow);
            assertThat(d.get("reaction")).isEqualTo(d.get("tip_reaction"));
            assertThat(((Number) d.get("repeats")).intValue())
                    .isEqualTo(ReactionRepeats.of((Long) d.get("amount_minor"), "GBP", (Boolean) d.get("flagged")));
        });
        assertThat(deliveries)
                .filteredOn(d -> (Boolean) d.get("flagged"))
                .isNotEmpty()
                .allSatisfy(
                        d -> assertThat(((Number) d.get("repeats")).intValue()).isOne());
        assertThat(deliveries)
                .extracting(d -> ((Number) d.get("repeats")).intValue())
                .contains(1, 2, 3, 4, 5);
    }

    @Test
    void theDemoDeviceConnectingAndTheResendJobSendNothingForSeededDeliveries() throws Exception {
        UUID demoDevice = (UUID) data.deviceRow("demo-panda").get("id");

        try (TestSocket device = TestSocket.connectDevice(port, demoDevice, DEMO_DEVICE_SECRET)) {
            assertThat(device.nextJson()).containsEntry("type", "hello");
            resendJob.resend();
            device.sync();

            assertThat(device.pending()).isZero();
        }
    }

    @Test
    void theDashboardShowsSeededRepeatsAndNullWhereNoDeliveryWasStored() throws Exception {
        mvc.perform(get("/api/owner/tips?size=50").with(user(demoOwner())))
                .andExpect(jsonPath("$.items[?(@.flagged == true && @.status != 'PENDING')].repeats")
                        .value(everyItem(is(1))))
                .andExpect(jsonPath("$.items[?(@.status == 'PENDING')].repeats").value(everyItem(nullValue())))
                .andExpect(jsonPath("$.items[?(@.repeats == null && @.status != 'PENDING')]", hasSize(3)));
    }

    @Test
    void demoOwnerSeesTheSamplesThroughTheApi() throws Exception {
        Map<String, Object> demoOwner = data.ownerRow("demo-owner@example.com");
        OwnerPrincipal principal =
                new OwnerPrincipal((UUID) demoOwner.get("id"), "demo-owner@example.com", "Demo Owner", null);

        mvc.perform(get("/api/owner/tips?size=50").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(DevTipSeeder.sampleCount()))
                .andExpect(jsonPath("$.items[?(@.flagged == true)].message").value(everyItem(nullValue())));
    }

    @Test
    void languageSamplesCarryTranslationsAndTheFlaggedFrenchOneKeepsItsReadForAnOverrule() {
        List<Map<String, Object>> rows = seededRows();

        assertThat(rows).extracting(r -> r.get("message_language")).contains("fr", "es", "de", "en");
        assertThat(rows)
                .filteredOn(r -> r.get("message_language") != null && !"en".equals(r.get("message_language")))
                .hasSizeGreaterThanOrEqualTo(5)
                .allSatisfy(r -> assertThat(r.get("message_english")).isNotNull());
        assertThat(rows)
                .filteredOn(r -> "en".equals(r.get("message_language")))
                .allSatisfy(r -> assertThat(r.get("message_english")).isNull());
        assertThat(rows)
                .filteredOn(r -> (Boolean) r.get("flagged") && r.get("message_language") != null)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.get("message_language")).isEqualTo("fr");
                    assertThat(r.get("message_english")).isNotNull();
                    assertThat(r.get("owner_verdict")).isNull();
                });
    }

    @Test
    void wonderwallIsTheTopSongInThreeSpellingsAndTodaysNotesAreStored() throws Exception {
        List<Map<String, Object>> wonderwall = seededRows().stream()
                .filter(r -> "wonderwall".equals(r.get("requested_song_key")))
                .filter(r -> ((Timestamp) r.get("created_at"))
                        .toInstant()
                        .isAfter(clock.instant().minus(Duration.ofDays(6))))
                .toList();
        assertThat(wonderwall)
                .extracting(r -> r.get("requested_song"))
                .hasSize(3)
                .doesNotHaveDuplicates();

        mvc.perform(get("/api/owner/requests").with(user(demoOwner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topSong.title").value("Wonderwall"))
                .andExpect(jsonPath("$.topSong.count").value(3))
                .andExpect(jsonPath("$.grouped").value("ai"))
                .andExpect(jsonPath("$.groups[?(@.label == 'Play Wonderwall')].count")
                        .value(3))
                // Like a real digest, which sees every note of the week: no group falls back to a raw note.
                .andExpect(jsonPath("$.groups[?(@.label =~ /^(Request|Feedback):.*/)]")
                        .isEmpty())
                .andExpect(jsonPath("$.groups[?(@.label == 'Too loud near the café')].count")
                        .value(1));
        mvc.perform(get("/api/owner/digest").with(user(demoOwner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bullets.length()").value(DevTipSeeder.DIGEST_BULLETS.size()))
                .andExpect(jsonPath("$.messageCount").value(DevTipSeeder.LANGUAGE_SAMPLES.size() - 1));
    }

    @Test
    void secondRunOnTheSameDayAddsNoDigestAndALaterDayGetsItsOwn() {
        long before = digestRows();

        seeder.seed();
        assertThat(digestRows()).isEqualTo(before);

        clock.pin(clock.instant().plus(Duration.ofDays(2)));
        seeder.seed();
        assertThat(digestRows()).isEqualTo(before + 1);
    }

    private OwnerPrincipal demoOwner() {
        Map<String, Object> row = data.ownerRow("demo-owner@example.com");
        return new OwnerPrincipal((UUID) row.get("id"), "demo-owner@example.com", "Demo Owner", null);
    }

    private long digestRows() {
        return jdbc.sql("SELECT count(*) FROM owner_digest WHERE owner_id = ?")
                .param(demoOwner().id())
                .query(Long.class)
                .single();
    }

    /** The seeded tips' deliveries with the tip's order id, amount, flag and reaction. */
    private List<Map<String, Object>> seededDeliveries() {
        return jdbc.sql("""
                        SELECT rd.*, t.paypal_order_id, t.amount_minor, t.flagged, t.reaction AS tip_reaction
                        FROM reaction_delivery rd JOIN tip t ON t.id = rd.tip_id
                        WHERE t.paypal_order_id LIKE ? ORDER BY t.paypal_order_id""").param(DevTipSeeder.ORDER_PREFIX + "%").query().listOfRows();
    }

    private List<Map<String, Object>> seededRows() {
        return jdbc.sql("SELECT * FROM tip WHERE paypal_order_id LIKE ? ORDER BY paypal_order_id")
                .param(DevTipSeeder.ORDER_PREFIX + "%")
                .query()
                .listOfRows();
    }
}
