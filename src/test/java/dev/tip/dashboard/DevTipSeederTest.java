package dev.tip.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.owners.api.OwnerPrincipal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Test
    void startupAddedEverySampleToTheDemoDeviceOnce() {
        List<Map<String, Object>> rows = seededRows();

        assertThat(rows).hasSize(DevTipSeeder.SAMPLES.size()).hasSizeBetween(35, 45);
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
        Instant now = Instant.now();

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
        clock.pin(Instant.now().plus(Duration.ofDays(3)));

        seeder.seed();

        assertThat(seededRows()).usingRecursiveComparison().isEqualTo(before);
    }

    @Test
    void demoOwnerSeesTheSamplesThroughTheApi() throws Exception {
        Map<String, Object> demoOwner = data.ownerRow("demo-owner@example.com");
        OwnerPrincipal principal =
                new OwnerPrincipal((UUID) demoOwner.get("id"), "demo-owner@example.com", "Demo Owner", null);

        mvc.perform(get("/api/owner/tips?size=50").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(DevTipSeeder.SAMPLES.size()))
                .andExpect(jsonPath("$.items[?(@.flagged == true)].message").value(everyItem(nullValue())));
    }

    private List<Map<String, Object>> seededRows() {
        return jdbc.sql("SELECT * FROM tip WHERE paypal_order_id LIKE ? ORDER BY paypal_order_id")
                .param(DevTipSeeder.ORDER_PREFIX + "%")
                .query()
                .listOfRows();
    }
}
