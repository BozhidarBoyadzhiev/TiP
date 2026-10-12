package dev.tip.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.owners.api.OwnerPrincipal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The owner's profile, today, requests, digest and devices endpoints, and the new fields of a tip. */
class DashboardTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T15:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestData data;

    @Autowired
    JdbcClient jdbc;

    OwnerPrincipal owner;
    UUID deviceId;
    String deviceSlug;
    OwnerPrincipal otherOwner;
    UUID otherDeviceId;

    @BeforeEach
    void twoOwners() {
        clock.pin(NOW);
        owner = principal(data.owner(TestData.uniqueEmail(), "Alex", TestData.OWNER_PASSWORD));
        deviceSlug = TestData.uniqueSlug();
        deviceId = data.device(owner.id(), deviceSlug, "Busker Panda");
        otherOwner = principal(data.owner());
        otherDeviceId = data.device(otherOwner.id(), TestData.uniqueSlug(), "Other Panda");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/owner/me",
                "/api/owner/today",
                "/api/owner/requests",
                "/api/owner/digest",
                "/api/owner/devices"
            })
    void everyNewEndpointIs401SignedOut(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void meIsTheDisplayNameAndTimeZoneWithoutTheEmail() throws Exception {
        as(owner, "/api/owner/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alex"))
                .andExpect(jsonPath("$.timeZone").value("Europe/London"))
                .andExpect(jsonPath("$.email").doesNotExist());
        data.setTimeZone(owner.id(), "America/New_York");
        as(owner, "/api/owner/me").andExpect(jsonPath("$.timeZone").value("America/New_York"));
    }

    @Test
    void todayCountsOnlyTheOwnersTipsSinceLocalMidnightInTheirZone() throws Exception {
        data.setTimeZone(owner.id(), "America/New_York");
        // 23:30 the evening before in New York, then 00:30 today.
        captured(deviceId, Instant.parse("2026-10-12T03:30:00Z"), 500, Map.of());
        captured(deviceId, Instant.parse("2026-10-12T04:30:00Z"), 200, Map.of());
        captured(otherDeviceId, Instant.parse("2026-10-12T14:00:00Z"), 1000, Map.of());

        as(owner, "/api/owner/today")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-10-12"))
                .andExpect(jsonPath("$.timeZone").value("America/New_York"))
                .andExpect(jsonPath("$.totals[0].currency").value("GBP"))
                .andExpect(jsonPath("$.totals[0].amountMinor").value(200))
                .andExpect(jsonPath("$.tipCount").value(1))
                .andExpect(jsonPath("$.hours[0].start").value("2026-10-12T00:00:00-04:00"))
                .andExpect(jsonPath("$.hours", hasSize(12)))
                .andExpect(jsonPath("$.busiestHour.hour").value(0));

        data.setTimeZone(owner.id(), "Europe/London");
        as(owner, "/api/owner/today").andExpect(jsonPath("$.tipCount").value(2));
    }

    @Test
    void todayCountsFlaggedTipsAsMoneyButNeverShowsTheirText() throws Exception {
        String abuse = TestData.uniqueMessage("You are a talentless clown");
        captured(deviceId, NOW.minusSeconds(600), 500, flagged(abuse, "REQUEST"));
        captured(deviceId, NOW.minusSeconds(300), 200, request("Request: Wonderwall", "Wonderwall", "wonderwall"));

        String body = as(owner, "/api/owner/today")
                .andExpect(jsonPath("$.tipCount").value(2))
                .andExpect(jsonPath("$.requestCount").value(1))
                .andExpect(jsonPath("$.totals[0].amountMinor").value(700))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain(abuse);
    }

    @Test
    void aRefundedRequestShowsInTheRequestsPanelButIsNotOneOfTodaysRequests() throws Exception {
        UUID kept = captured(deviceId, NOW.minusSeconds(900), 200, request("Request: Wonderwall", null, null));
        Map<String, Object> refundedColumns = new LinkedHashMap<>(request("Request: Wonderwall", null, null));
        refundedColumns.put("amount_minor", 500L);
        refundedColumns.put("paypal_capture_id", TestData.uniqueId("CAPTURE"));
        UUID refunded = data.tip(deviceId, "REFUNDED", NOW.minusSeconds(600), refundedColumns);

        as(owner, "/api/owner/today")
                .andExpect(jsonPath("$.tipCount").value(1))
                .andExpect(jsonPath("$.requestCount").value(1));
        as(owner, "/api/owner/requests")
                .andExpect(jsonPath("$.groups[0].count").value(2))
                .andExpect(jsonPath("$.groups[0].tipIds").value(contains(refunded.toString(), kept.toString())));
    }

    @Test
    void requestsCoverSevenLocalDaysAndLeaveFlaggedAndOtherOwnersOut() throws Exception {
        // The window opens at local midnight six days back: 2026-10-06 00:00 BST, 2026-10-05T23:00Z.
        UUID outside = captured(
                deviceId,
                Instant.parse("2026-10-05T22:59:00Z"),
                200,
                request("Request: Wonderwall", "Wonderwall", "wonderwall"));
        UUID first = captured(
                deviceId,
                Instant.parse("2026-10-05T23:01:00Z"),
                200,
                request("Request: Wonderwall", "wonderwall", "wonderwall"));
        UUID second = captured(
                deviceId,
                Instant.parse("2026-10-12T10:00:00Z"),
                200,
                request("request:  WONDERWALL", "Wonderwall!", "wonderwall"));
        UUID hidden = captured(deviceId, NOW.minusSeconds(60), 200, flaggedRequest("Wonderwall", "wonderwall"));
        UUID others = captured(
                otherDeviceId, NOW.minusSeconds(60), 200, request("Request: Wonderwall", "Wonderwall", "wonderwall"));

        String body = as(owner, "/api/owner/requests")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-06"))
                .andExpect(jsonPath("$.to").value("2026-10-12"))
                .andExpect(jsonPath("$.topSong.title").value("Wonderwall!"))
                .andExpect(jsonPath("$.topSong.count").value(2))
                .andExpect(jsonPath("$.topSong.latestAt").value("2026-10-12T10:00:00Z"))
                .andExpect(jsonPath("$.grouped").value("exact"))
                .andExpect(jsonPath("$.groups", hasSize(1)))
                .andExpect(jsonPath("$.groups[0].label").value("request:  WONDERWALL"))
                .andExpect(jsonPath("$.groups[0].count").value(2))
                .andExpect(jsonPath("$.groups[0].tipIds").value(contains(second.toString(), first.toString())))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain(outside.toString(), hidden.toString(), others.toString());
    }

    @Test
    void requestsUseTodaysDigestGroupsWhenThereAreSome() throws Exception {
        UUID a = captured(deviceId, NOW.minusSeconds(900), 200, request("Request: Oasis", null, null));
        UUID b = captured(deviceId, NOW.minusSeconds(600), 200, request("Request: more Oasis", null, null));
        UUID c = captured(deviceId, NOW.minusSeconds(300), 200, request("Feedback: too loud", null, null));
        storeDigest(owner.id(), "2026-10-12", "[\"Oasis fans out tonight.\"]", """
                [{"label":"More Oasis","tipIds":["%s","%s"]}]""".formatted(a, b));

        as(owner, "/api/owner/requests")
                .andExpect(jsonPath("$.grouped").value("ai"))
                .andExpect(jsonPath("$.groups[*].label", containsInAnyOrder("More Oasis", "Feedback: too loud")))
                .andExpect(
                        jsonPath("$.groups[?(@.label == 'More Oasis')].count").value(2))
                .andExpect(jsonPath("$.groups[0].tipIds[0]").value(c.toString()));
    }

    @Test
    void digestIsNullUntilOneIsStoredAndOnlyTheOwnersOwn() throws Exception {
        as(owner, "/api/owner/digest")
                .andExpect(status().isOk())
                .andExpect(content().string("null"));
        storeDigest(otherOwner.id(), "2026-10-12", "[\"Not yours.\"]", "[]");
        as(owner, "/api/owner/digest").andExpect(content().string("null"));
        storeDigest(owner.id(), "2026-10-11", "[\"Yesterday.\"]", "[]");
        as(owner, "/api/owner/digest").andExpect(content().string("null"));

        storeDigest(owner.id(), "2026-10-12", "[\"Warm thanks all day.\",\"Two asked for Oasis.\"]", "[]");

        as(owner, "/api/owner/digest")
                .andExpect(jsonPath("$.bullets", contains("Warm thanks all day.", "Two asked for Oasis.")))
                .andExpect(jsonPath("$.messageCount").value(5))
                .andExpect(jsonPath("$.generatedAt").value("2026-10-12T14:00:00Z"));
    }

    @Test
    void devicesListsOnlyTheOwnersDevices() throws Exception {
        String second = "aaa-" + TestData.uniqueSlug();
        data.device(owner.id(), second, "Second Panda");

        as(owner, "/api/owner/devices")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].slug").value(second))
                .andExpect(jsonPath("$[1].slug").value(deviceSlug))
                .andExpect(jsonPath("$[1].name").value("Busker Panda"))
                .andExpect(jsonPath("$[1].online").value(false))
                .andExpect(jsonPath("$[1].lastSeenAt").value(nullValue()))
                .andExpect(jsonPath("$[1].id").doesNotExist());
    }

    @Test
    void aTipShowsItsLanguageTranslationAndSongUnlessItIsFlagged() throws Exception {
        Map<String, Object> spanish = new LinkedHashMap<>(request("Request: Despacito", "Despacito", "despacito"));
        spanish.put("message", "¿Puedes tocar Despacito?");
        spanish.put("message_language", "es");
        spanish.put("message_english", "Can you play Despacito?");
        UUID tip = captured(deviceId, NOW.minusSeconds(60), 200, spanish);
        Map<String, Object> flaggedSpanish = new LinkedHashMap<>(flagged("Eres un payaso", "PLAIN"));
        flaggedSpanish.put("message_language", "es");
        flaggedSpanish.put("message_english", "You are a clown");
        flaggedSpanish.put("requested_song", "Despacito");
        UUID hidden = captured(deviceId, NOW.minusSeconds(30), 200, flaggedSpanish);

        as(owner, "/api/owner/tips/" + tip)
                .andExpect(jsonPath("$.language").value("es"))
                .andExpect(jsonPath("$.languageName").value("Spanish"))
                .andExpect(jsonPath("$.englishText").value("Can you play Despacito?"))
                .andExpect(jsonPath("$.requestedSong").value("Despacito"));
        as(owner, "/api/owner/tips/" + hidden)
                .andExpect(jsonPath("$.message").value(nullValue()))
                .andExpect(jsonPath("$.language").value(nullValue()))
                .andExpect(jsonPath("$.languageName").value(nullValue()))
                .andExpect(jsonPath("$.englishText").value(nullValue()))
                .andExpect(jsonPath("$.requestedSong").value(nullValue()));
        String list = as(owner, "/api/owner/tips").andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("You are a clown", "Eres un payaso");
    }

    @Test
    void aTipShowsTheRepeatsStoredWithItsDeliveryOrNullWithoutOne() throws Exception {
        UUID delivered =
                captured(deviceId, NOW.minusSeconds(600), 2000, Map.of("reaction", "WARM", "ai_status", "DONE"));
        UUID flagged = captured(deviceId, NOW.minusSeconds(500), 5000, flagged("Clown", "PLAIN"));
        UUID notYet = captured(deviceId, NOW.minusSeconds(400), 1000, Map.of());
        jdbc.sql(
                        "INSERT INTO reaction_delivery (tip_id, device_id, reaction, repeats, reacted_at) VALUES (?, ?, ?, ?, now())")
                .params(delivered, deviceId, "WARM", 5)
                .update();
        jdbc.sql(
                        "INSERT INTO reaction_delivery (tip_id, device_id, reaction, repeats, reacted_at) VALUES (?, ?, ?, ?, now())")
                .params(flagged, deviceId, "PLAIN", 1)
                .update();

        as(owner, "/api/owner/tips/" + delivered)
                .andExpect(jsonPath("$.repeats").value(5));
        as(owner, "/api/owner/tips/" + flagged).andExpect(jsonPath("$.repeats").value(1));
        as(owner, "/api/owner/tips/" + notYet).andExpect(jsonPath("$.repeats").value(nullValue()));
        as(owner, "/api/owner/tips")
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[?(@.id == '" + delivered + "')].repeats")
                        .value(contains(5)));
    }

    private ResultActions as(OwnerPrincipal principal, String path) throws Exception {
        return mvc.perform(get(path).with(user(principal)));
    }

    private UUID captured(UUID device, Instant createdAt, long amountMinor, Map<String, Object> columns) {
        Map<String, Object> all = new LinkedHashMap<>(columns);
        all.put("amount_minor", amountMinor);
        all.put("paypal_capture_id", TestData.uniqueId("CAPTURE"));
        all.put("captured_at", createdAt);
        return data.tip(device, "CAPTURED", createdAt, all);
    }

    private static Map<String, Object> request(String note, String song, String songKey) {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("message", "Play it please");
        columns.put("message_kind", "REQUEST");
        columns.put("reaction", "REQUEST");
        columns.put("ai_status", "DONE");
        columns.put("owner_note", note);
        columns.put("requested_song", song);
        columns.put("requested_song_key", songKey);
        return columns;
    }

    private static Map<String, Object> flagged(String message, String kind) {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("message", message);
        columns.put("message_kind", kind);
        columns.put("reaction", "PLAIN");
        columns.put("flagged", true);
        columns.put("flag_reason", "insult");
        columns.put("ai_status", "DONE");
        return columns;
    }

    /** A flagged request whose song columns are set anyway, to prove the queries leave it out. */
    private static Map<String, Object> flaggedRequest(String song, String songKey) {
        Map<String, Object> columns = flagged("Play " + song + " or else", "REQUEST");
        columns.put("owner_note", "Request: " + song);
        columns.put("requested_song", song);
        columns.put("requested_song_key", songKey);
        return columns;
    }

    private void storeDigest(UUID ownerId, String day, String bullets, String groups) {
        jdbc.sql("""
                        INSERT INTO owner_digest (owner_id, local_day, bullets, request_groups, message_count,
                                                  source_watermark, generated_at)
                        VALUES (?, CAST(? AS date), CAST(? AS jsonb), CAST(? AS jsonb), 5, ?, ?)
                        ON CONFLICT (owner_id, local_day) DO UPDATE SET bullets = EXCLUDED.bullets,
                            request_groups = EXCLUDED.request_groups""")
                .params(
                        ownerId,
                        day,
                        bullets,
                        groups,
                        java.sql.Timestamp.from(NOW.minusSeconds(3600)),
                        java.sql.Timestamp.from(NOW.minusSeconds(3600)))
                .update();
    }

    private static OwnerPrincipal principal(UUID ownerId) {
        return new OwnerPrincipal(ownerId, "owner@example.com", "Owner", null);
    }
}
