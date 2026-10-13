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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Runs the app with the {@code dev} profile on top of {@code test}, so the seeder ran at startup. It checks the
 * shared sample set ({@link SampleTipSet}) through the demo device, the same set sandboxes and the public demo get.
 */
@ActiveProfiles("dev")
class DevTipSeederTest extends IntegrationTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

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
    void startupAddedEverySampleToTheDemoDeviceOnceWithoutPayPalIds() {
        List<Map<String, Object>> rows = seededRows();

        assertThat(rows).hasSize(SampleTipSet.SAMPLES.size()).hasSizeBetween(20, 40);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("demo")).isEqualTo(true);
            assertThat(r.get("paypal_order_id")).isNull();
            assertThat(r.get("paypal_capture_id")).isNull();
        });
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

        assertThat(rows)
                .filteredOn(r -> (Boolean) r.get("flagged"))
                .hasSizeGreaterThanOrEqualTo(3)
                .allSatisfy(r -> {
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
    void samplesSpreadOverThreeLocalDaysWithSeveralToday() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, LONDON);
        Map<LocalDate, Long> perDay = seededRows().stream()
                .map(r -> ((Timestamp) r.get("created_at")).toInstant())
                .peek(t -> assertThat(t).isBeforeOrEqualTo(now))
                .collect(Collectors.groupingBy(t -> LocalDate.ofInstant(t, LONDON), Collectors.counting()));

        assertThat(perDay).containsOnlyKeys(today, today.minusDays(1), today.minusDays(2));
        assertThat(perDay.get(today)).isGreaterThanOrEqualTo(5);
    }

    @Test
    void capturedSamplesAreAlreadyClassifiedSoTheAiNeverPicksThemUp() {
        assertThat(seededRows())
                .filteredOn(r -> "CAPTURED".equals(r.get("status")))
                .allSatisfy(r -> {
                    assertThat(r.get("ai_status")).isIn("DONE", "SKIPPED");
                    assertThat(r.get("reaction")).isNotNull();
                    assertThat(r.get("captured_at")).isNotNull();
                });
    }

    @Test
    void songsAndBandsAreInventedEverywhere() {
        Set<String> real =
                Set.of("wonderwall", "oasis", "abba", "beatles", "despacito", "la vie en rose", "happy birthday");

        assertThat(seededRows()).allSatisfy(r -> {
            String texts = (r.get("message") + " " + r.get("owner_note") + " " + r.get("requested_song") + " "
                            + r.get("message_english"))
                    .toLowerCase();
            assertThat(real).noneMatch(texts::contains);
        });
        assertThat(String.join(" ", SampleTipSet.DIGEST_BULLETS) + SampleTipSet.DIGEST_GROUPS.keySet())
                .doesNotContainIgnoringCase("wonderwall")
                .doesNotContainIgnoringCase("oasis");
    }

    @Test
    void secondRunOnTheSameDayChangesNothing() {
        List<Map<String, Object>> before = seededRows();
        List<Map<String, Object>> deliveriesBefore = seededDeliveries();
        long digestsBefore = digestRows();

        seeder.seed();

        assertThat(seededRows()).usingRecursiveComparison().isEqualTo(before);
        assertThat(seededDeliveries()).usingRecursiveComparison().isEqualTo(deliveriesBefore);
        assertThat(digestRows()).isEqualTo(digestsBefore);
    }

    @Test
    void aLaterDayReplacesTheSamplesAndKeepsRealTips() {
        UUID demoDevice = demoDevice();
        UUID realTip = data.tip(demoDevice, "CAPTURED", TestData.uniqueId("ORDER"), clock.instant());
        Set<Object> before = seededRows().stream().map(r -> r.get("id")).collect(Collectors.toSet());
        try {
            clock.pin(clock.instant().plus(Duration.ofDays(2)));
            seeder.seed();

            List<Map<String, Object>> after = seededRows();
            assertThat(after).hasSize(SampleTipSet.SAMPLES.size());
            assertThat(after).extracting(r -> r.get("id")).doesNotContainAnyElementsOf(before);
            assertThat(data.tipRow(realTip)).containsEntry("status", "CAPTURED");
            assertThat(digestRows()).isOne();
        } finally {
            jdbc.sql("DELETE FROM tip WHERE id = ?").param(realTip).update();
            reseedNow();
        }
    }

    @Test
    void reactedSamplesHaveAnAckedOldDeliveryAtTheSendLimitWithRepeatsFromTheAmountTable() {
        List<Map<String, Object>> tips = seededRows();
        List<Map<String, Object>> deliveries = seededDeliveries();
        Set<Object> withoutDelivery = SampleTipSet.WITHOUT_DELIVERY.stream()
                .map(i -> tips.get(i).get("id"))
                .collect(Collectors.toSet());

        long reacted = tips.stream().filter(r -> r.get("reaction") != null).count();
        assertThat(deliveries).hasSize((int) reacted - withoutDelivery.size());
        assertThat(deliveries).extracting(d -> d.get("tip_id")).doesNotContainAnyElementsOf(withoutDelivery);
        assertThat(deliveries).allSatisfy(d -> {
            assertThat(d.get("acked_at")).isNotNull();
            assertThat((Integer) d.get("sent_count")).isGreaterThanOrEqualTo(SampleTipSet.SEEDED_SEND_COUNT);
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
        try (TestSocket device = TestSocket.connectDevice(port, demoDevice(), DEMO_DEVICE_SECRET)) {
            assertThat(device.nextJson()).containsEntry("type", "hello");
            resendJob.resend();
            device.sync();

            assertThat(device.pending()).isZero();
        }
    }

    @Test
    void theDashboardShowsSampleTipsAsDemoWithRepeatsAndNullWhereNoDeliveryWasStored() throws Exception {
        mvc.perform(get("/api/owner/tips?size=50").with(user(demoOwner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.demo == true)]", hasSize(SampleTipSet.SAMPLES.size())))
                .andExpect(jsonPath("$.items[?(@.flagged == true)].message").value(everyItem(nullValue())))
                .andExpect(jsonPath("$.items[?(@.flagged == true && @.status != 'PENDING')].repeats")
                        .value(everyItem(is(1))))
                .andExpect(jsonPath("$.items[?(@.status == 'PENDING')].repeats").value(everyItem(nullValue())))
                .andExpect(jsonPath("$.items[?(@.repeats == null && @.status != 'PENDING')]", hasSize(2)));
    }

    @Test
    void languageSamplesCarryTranslationsAndHiddenBulgarianAndEnglishMessagesKeepTheirRead() {
        List<Map<String, Object>> rows = seededRows();

        assertThat(rows).extracting(r -> r.get("message_language")).contains("fr", "es", "de", "en", "bg");
        assertThat(rows)
                .filteredOn(r -> r.get("message_language") != null && !"en".equals(r.get("message_language")))
                .hasSizeGreaterThanOrEqualTo(5)
                .allSatisfy(r -> assertThat(r.get("message_english")).isNotNull());
        assertThat(rows)
                .filteredOn(r -> "en".equals(r.get("message_language")))
                .allSatisfy(r -> assertThat(r.get("message_english")).isNull());
        List<Map<String, Object>> hidden =
                rows.stream().filter(r -> (Boolean) r.get("flagged")).toList();
        assertThat(hidden)
                .filteredOn(r -> "bg".equals(r.get("message_language")))
                .singleElement()
                .satisfies(r -> assertThat(r.get("message_english")).isNotNull());
        assertThat(hidden)
                .filteredOn(r -> "en".equals(r.get("message_language")))
                .isNotEmpty();
        assertThat(hidden).allSatisfy(r -> assertThat(r.get("owner_verdict")).isNull());
    }

    @Test
    void theTopSongIsAskedForInThreeSpellingsAndTodaysNotesAreStored() throws Exception {
        assertThat(seededRows())
                .filteredOn(r -> "marmalade moonwalk".equals(r.get("requested_song_key")))
                .extracting(r -> r.get("requested_song"))
                .hasSize(3)
                .doesNotHaveDuplicates();

        mvc.perform(get("/api/owner/requests").with(user(demoOwner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topSong.title").value("Marmalade Moonwalk"))
                .andExpect(jsonPath("$.topSong.count").value(3))
                .andExpect(jsonPath("$.grouped").value("ai"))
                .andExpect(jsonPath("$.groups[?(@.label == 'Play Marmalade Moonwalk')].count")
                        .value(3))
                // Like a real digest, which sees every note of the week: no group falls back to a raw note.
                .andExpect(jsonPath("$.groups[?(@.label =~ /^(Request|Feedback):.*/)]")
                        .isEmpty());
        mvc.perform(get("/api/owner/digest").with(user(demoOwner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bullets.length()").value(SampleTipSet.DIGEST_BULLETS.size()))
                .andExpect(jsonPath("$.messageCount").value(SampleTipSet.todaysShownMessages()));
        mvc.perform(get("/api/owner/today").with(user(demoOwner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(5)));
    }

    private void reseedNow() {
        clock.release();
        jdbc.sql("DELETE FROM reaction_delivery WHERE tip_id IN (SELECT id FROM tip WHERE device_id = ? AND demo)")
                .param(demoDevice())
                .update();
        jdbc.sql("DELETE FROM tip WHERE device_id = ? AND demo")
                .param(demoDevice())
                .update();
        seeder.seed();
    }

    private UUID demoDevice() {
        return (UUID) data.deviceRow("demo-panda").get("id");
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

    /** The samples' deliveries with the tip's amount, flag and reaction. */
    private List<Map<String, Object>> seededDeliveries() {
        return jdbc.sql("""
                        SELECT rd.*, t.amount_minor, t.flagged, t.reaction AS tip_reaction
                        FROM reaction_delivery rd JOIN tip t ON t.id = rd.tip_id
                        WHERE t.device_id = ? AND t.demo ORDER BY t.created_at DESC""").param(demoDevice()).query().listOfRows();
    }

    /** Newest first, which is the order of {@link SampleTipSet#SAMPLES}. */
    private List<Map<String, Object>> seededRows() {
        return jdbc.sql("SELECT * FROM tip WHERE device_id = ? AND demo ORDER BY created_at DESC")
                .param(demoDevice())
                .query()
                .listOfRows();
    }
}
