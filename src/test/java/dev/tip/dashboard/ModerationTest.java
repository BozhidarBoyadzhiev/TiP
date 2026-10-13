package dev.tip.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.owners.api.OwnerPrincipal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Reading a hidden message and overruling the AI's flag, and what that changes on every owner endpoint. */
class ModerationTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T15:00:00Z");
    private static final int THREADS = 8;

    @Autowired
    MockMvc mvc;

    @Autowired
    TestData data;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ModerationRepository moderation;

    @Autowired
    TipModeration tipModeration;

    OwnerPrincipal owner;
    UUID deviceId;
    OwnerPrincipal otherOwner;
    UUID otherDeviceId;

    @BeforeEach
    void twoOwners() {
        clock.pin(NOW);
        owner = principal(data.owner());
        deviceId = data.device(owner.id(), TestData.uniqueSlug(), "Busker Panda");
        otherOwner = principal(data.owner());
        otherDeviceId = data.device(otherOwner.id(), TestData.uniqueSlug(), "Other Panda");
    }

    @Test
    void bothEndpointsAre401SignedOut() throws Exception {
        UUID tip = captured(deviceId, NOW.minusSeconds(60), flagged("Clown", "PLAIN"));

        mvc.perform(get("/api/owner/tips/{id}/message", tip))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(put("/api/owner/tips/{id}/verdict", tip)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":false}"))
                .andExpect(status().isUnauthorized());
        assertThat(verdictOf(tip)).isNull();
    }

    @Test
    void anotherOwnersTipAnUnflaggedTipAndAnUnknownIdGetTheSame404FromBoth() throws Exception {
        UUID unflagged = captured(deviceId, NOW.minusSeconds(300), Map.of("message", "Lovely", "ai_status", "DONE"));
        String othersEnglish = TestData.uniqueMessage("Play Wonderwall or watch out");
        UUID othersFlagged = captured(
                otherDeviceId,
                NOW.minusSeconds(200),
                flaggedRead(TestData.uniqueMessage("Joue Wonderwall"), "Request: Wonderwall", othersEnglish));
        UUID pendingFlagged = data.tip(deviceId, "FAILED", NOW.minusSeconds(100), flagged("Clown", "PLAIN"));

        List<String> bodies = new ArrayList<>();
        for (UUID id : List.of(unflagged, othersFlagged, pendingFlagged, UUID.randomUUID())) {
            bodies.add(as(owner, get("/api/owner/tips/{id}/message", id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TIP_NOT_FOUND"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
            for (boolean hidden : List.of(false, true)) {
                bodies.add(verdict(owner, id, hidden)
                        .andExpect(status().isNotFound())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
            }
        }

        // instance repeats the requested path, so it differs by the path asked for and nothing else
        assertThat(bodies)
                .hasSize(12)
                .allSatisfy(b -> assertThat(b).contains("\"instance\":\"/api/owner/tips/"))
                .extracting(b -> b.replaceAll("\"instance\":\"[^\"]*\"", ""))
                .containsOnly(bodies.getFirst().replaceAll("\"instance\":\"[^\"]*\"", ""))
                .allSatisfy(b -> assertThat(b).doesNotContain(othersEnglish, "French"));
        assertThat(verdictOf(othersFlagged)).isNull();
        assertThat(verdictOf(unflagged)).isNull();
        as(otherOwner, get("/api/owner/tips/{id}/message", othersFlagged)).andExpect(status().isOk());
    }

    @Test
    void badInputIsRejectedWithoutAChange() throws Exception {
        UUID tip = captured(deviceId, NOW.minusSeconds(60), flagged("Clown", "PLAIN"));

        as(owner, get("/api/owner/tips/{id}/message", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        for (String body : List.of("{}", "{\"hidden\":null}", "{\"hidden\":\"maybe\"}", "not json")) {
            as(
                            owner,
                            put("/api/owner/tips/{id}/verdict", tip)
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        as(
                        owner,
                        put("/api/owner/tips/{id}/verdict", tip)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"hidden\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        assertThat(verdictOf(tip)).isNull();
    }

    @Test
    void revealGivesTheTextWithoutATranslationForEnglishAndIsNeverStored() throws Exception {
        String abuse = TestData.uniqueMessage("You are a talentless clown");
        Map<String, Object> columns = flagged(abuse, "PLAIN");
        columns.put("message_language", "en");
        UUID tip = captured(deviceId, NOW.minusSeconds(60), columns);

        String body = as(owner, get("/api/owner/tips/{id}/message", tip))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.text").value(abuse))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body)
                .isEqualTo("{\"text\":\"" + abuse
                        + "\",\"language\":\"en\",\"languageName\":\"English\",\"englishText\":null}");

        verdict(owner, tip, false).andExpect(status().isOk());
        as(owner, get("/api/owner/tips/{id}/message", tip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(abuse));
        assertThat(verdictOf(tip)).as("reading changes nothing").isEqualTo("NOT_ABUSIVE");
    }

    @Test
    void revealOfANonEnglishTipCarriesItsLanguageAndTranslation() throws Exception {
        String abuse = TestData.uniqueMessage("Joue Wonderwall ou gare a toi");
        String english = TestData.uniqueMessage("Play Wonderwall or watch out");
        UUID tip = captured(deviceId, NOW.minusSeconds(60), flaggedRead(abuse, "Request: Wonderwall", english));

        as(owner, get("/api/owner/tips/{id}/message", tip))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.text").value(abuse))
                .andExpect(jsonPath("$.language").value("fr"))
                .andExpect(jsonPath("$.languageName").value("French"))
                .andExpect(jsonPath("$.englishText").value(english));
        as(owner, get("/api/owner/tips/{id}", tip))
                .andExpect(jsonPath("$.language").value(nullValue()))
                .andExpect(jsonPath("$.englishText").value(nullValue()));
    }

    @Test
    void whileHiddenTheTextAndTheReadAreInNoOwnerPayload() throws Exception {
        String abuse = TestData.uniqueMessage("Joue Wonderwall ou gare a toi");
        String note = TestData.uniqueMessage("Request: Wonderwall");
        String english = TestData.uniqueMessage("Play Wonderwall or watch out");
        UUID tip = captured(deviceId, NOW.minusSeconds(60), flaggedRead(abuse, note, english));
        storeDigest();

        List<String> payloads = new ArrayList<>();
        for (String path : List.of(
                "/api/owner/tips/" + tip,
                "/api/owner/tips",
                "/api/owner/tips?flagged=true",
                "/api/owner/tips?kind=REQUEST",
                "/api/owner/today",
                "/api/owner/requests",
                "/api/owner/digest")) {
            payloads.add(as(owner, get(path))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
        }

        assertThat(payloads)
                .allSatisfy(p -> assertThat(p).doesNotContain(abuse, note, english, "Wondérwall", "French"));
        as(owner, get("/api/owner/tips?flagged=true"))
                .andExpect(jsonPath("$.items[0].id").value(tip.toString()))
                .andExpect(jsonPath("$.items[0].language").value(nullValue()))
                .andExpect(jsonPath("$.items[0].languageName").value(nullValue()))
                .andExpect(jsonPath("$.items[0].englishText").value(nullValue()));
    }

    @Test
    void overruleThenHideAgainRoundTripsEveryOwnerView() throws Exception {
        String abuse = TestData.uniqueMessage("Joue Wonderwall ou gare a toi");
        String note = TestData.uniqueMessage("Request: Wonderwall");
        String english = TestData.uniqueMessage("Play Wonderwall or watch out");
        UUID tip = captured(deviceId, NOW.minusSeconds(120), flaggedRead(abuse, note, english));
        captured(deviceId, NOW.minusSeconds(60), request("Request: Wonderwall please"));
        Map<String, Object> before = data.tipRow(tip);

        assertHidden(tip, abuse);

        verdict(owner, tip, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tip.toString()))
                .andExpect(jsonPath("$.flagged").value(false))
                .andExpect(jsonPath("$.unhidden").value(true))
                .andExpect(jsonPath("$.flagReason").value("threat"))
                .andExpect(jsonPath("$.message").value(abuse))
                .andExpect(jsonPath("$.messageKind").value("REQUEST"))
                .andExpect(jsonPath("$.reaction").value("PLAIN"))
                .andExpect(jsonPath("$.ownerNote").value(note))
                .andExpect(jsonPath("$.language").value("fr"))
                .andExpect(jsonPath("$.languageName").value("French"))
                .andExpect(jsonPath("$.englishText").value(english))
                .andExpect(jsonPath("$.requestedSong").value("Wondérwall"));

        as(owner, get("/api/owner/tips?flagged=true"))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(tip.toString()))));
        as(owner, get("/api/owner/tips?flagged=false&kind=REQUEST"))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[*].id", hasItem(tip.toString())));
        as(owner, get("/api/owner/tips/{id}", tip))
                .andExpect(jsonPath("$.message").value(abuse));
        as(owner, get("/api/owner/today"))
                .andExpect(jsonPath("$.tipCount").value(2))
                .andExpect(jsonPath("$.requestCount").value(2));
        as(owner, get("/api/owner/requests"))
                .andExpect(jsonPath("$.topSong.title").value("Wonderwall"))
                .andExpect(jsonPath("$.topSong.count").value(2))
                .andExpect(jsonPath("$.groups[*].tipIds[*]", hasItem(tip.toString())))
                .andExpect(jsonPath("$.groups[*].label", hasItem(note)));

        // Repeating the verdict changes nothing, not even its time.
        clock.advance(Duration.ofMinutes(1));
        verdict(owner, tip, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unhidden").value(true));
        assertThat(data.tipRow(tip).get("owner_verdict_at")).isEqualTo(Timestamp.from(NOW));

        verdict(owner, tip, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flagged").value(true))
                .andExpect(jsonPath("$.unhidden").value(false))
                .andExpect(jsonPath("$.message").value(nullValue()))
                .andExpect(jsonPath("$.ownerNote").value(nullValue()))
                .andExpect(jsonPath("$.englishText").value(nullValue()))
                .andExpect(jsonPath("$.requestedSong").value(nullValue()));
        verdict(owner, tip, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flagged").value(true));

        assertHidden(tip, abuse);
        Map<String, Object> after = data.tipRow(tip);
        assertThat(after.get("owner_verdict")).isNull();
        assertThat(after.get("owner_verdict_at")).isEqualTo(Timestamp.from(NOW.plus(Duration.ofMinutes(1))));
        // The AI's decision and what the panda played are never changed by the owner's verdict.
        for (String column : List.of(
                "flagged", "flag_reason", "message_kind", "reaction", "owner_note", "requested_song", "reaction_at")) {
            assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM reaction_delivery WHERE tip_id = ?")
                        .param(tip)
                        .query(Long.class)
                        .single())
                .as("no reaction is sent for a verdict")
                .isZero();
    }

    @Test
    void aLegacyFlaggedTipWithNoStoredReadShowsAsPlainOnOverrule() throws Exception {
        String old = TestData.uniqueMessage("Rubbish, give up");
        Map<String, Object> legacy = flagged(old, "PLAIN");
        UUID tip = captured(deviceId, NOW.minusSeconds(60), legacy);
        Map<String, Object> neverRead = flagged(TestData.uniqueMessage("Clown"), null);
        UUID noKind = captured(deviceId, NOW.minusSeconds(30), neverRead);

        verdict(owner, tip, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(old))
                .andExpect(jsonPath("$.messageKind").value("PLAIN"))
                .andExpect(jsonPath("$.reaction").value("PLAIN"))
                .andExpect(jsonPath("$.ownerNote").value(nullValue()))
                .andExpect(jsonPath("$.language").value(nullValue()))
                .andExpect(jsonPath("$.englishText").value(nullValue()))
                .andExpect(jsonPath("$.requestedSong").value(nullValue()));
        as(owner, get("/api/owner/tips?kind=PLAIN&flagged=false"))
                .andExpect(jsonPath("$.items[*].id", hasItem(tip.toString())));

        verdict(owner, noKind, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageKind").value(nullValue()))
                .andExpect(jsonPath("$.reaction").value("PLAIN"))
                .andExpect(jsonPath("$.ownerNote").value(nullValue()));
    }

    @Test
    void concurrentEqualVerdictsChangeTheTipOnce() throws Exception {
        UUID tip = captured(deviceId, NOW.minusSeconds(60), flagged("Clown", "PLAIN"));

        List<ModerationRepository.Change> changes = race(
                i -> () -> moderation.setHidden(owner.id(), tip, false, NOW).orElseThrow());

        assertThat(changes).filteredOn(ModerationRepository.Change::changed).hasSize(1);
        assertThat(verdictOf(tip)).isEqualTo("NOT_ABUSIVE");
    }

    @Test
    void concurrentOppositeVerdictsEndInAStateEveryViewAgreesOn() throws Exception {
        UUID tip = captured(deviceId, NOW.minusSeconds(60), flagged(TestData.uniqueMessage("Clown"), "PLAIN"));
        for (int round = 0; round < 5; round++) {
            List<Boolean> found = race(i -> () -> tipModeration.setHidden(owner.id(), tip, i % 2 == 0));
            assertThat(found).containsOnly(true);

            boolean hidden = verdictOf(tip) == null;
            assertThat(data.tipRow(tip).get("owner_verdict_at")).isNotNull();
            as(owner, get("/api/owner/tips/{id}", tip))
                    .andExpect(jsonPath("$.flagged").value(hidden))
                    .andExpect(jsonPath("$.unhidden").value(!hidden));
            as(owner, get("/api/owner/tips?flagged=true"))
                    .andExpect(jsonPath("$.totalItems").value(hidden ? 1 : 0));
        }
    }

    private void assertHidden(UUID tip, String abuse) throws Exception {
        as(owner, get("/api/owner/tips?flagged=true"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(tip.toString()))
                .andExpect(jsonPath("$.items[0].flagged").value(true))
                .andExpect(jsonPath("$.items[0].unhidden").value(false))
                .andExpect(jsonPath("$.items[0].message").value(nullValue()));
        as(owner, get("/api/owner/tips?flagged=false&kind=REQUEST"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(tip.toString()))));
        as(owner, get("/api/owner/today"))
                .andExpect(jsonPath("$.tipCount").value(2))
                .andExpect(jsonPath("$.requestCount").value(1));
        String requests = as(owner, get("/api/owner/requests"))
                .andExpect(jsonPath("$.topSong").value(nullValue()))
                .andExpect(jsonPath("$.groups[*].tipIds[*]", not(hasItem(tip.toString()))))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(requests).doesNotContain(abuse);
    }

    /** Runs {@link #THREADS} tasks at once and returns their results in order. */
    private <T> List<T> race(java.util.function.IntFunction<Callable<T>> task) throws Exception {
        CyclicBarrier start = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                Callable<T> call = task.apply(i);
                futures.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    return call.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private ResultActions verdict(OwnerPrincipal principal, UUID tip, boolean hidden) throws Exception {
        return as(
                principal,
                put("/api/owner/tips/{id}/verdict", tip)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":" + hidden + "}"));
    }

    private ResultActions as(
            OwnerPrincipal principal,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(request.with(user(principal)));
    }

    private String verdictOf(UUID tip) {
        return (String) data.tipRow(tip).get("owner_verdict");
    }

    private void storeDigest() {
        jdbc.sql("""
                        INSERT INTO owner_digest (owner_id, local_day, bullets, request_groups, message_count,
                                                  source_watermark, generated_at)
                        VALUES (?, CAST(? AS date), CAST(? AS jsonb), CAST(? AS jsonb), 3, ?, ?)""")
                .params(
                        owner.id(),
                        "2026-10-12",
                        "[\"Warm thanks all day.\"]",
                        "[]",
                        Timestamp.from(NOW.minusSeconds(3600)),
                        Timestamp.from(NOW.minusSeconds(3600)))
                .update();
    }

    private UUID captured(UUID device, Instant createdAt, Map<String, Object> columns) {
        Map<String, Object> all = new LinkedHashMap<>(columns);
        all.put("amount_minor", 200L);
        all.put("paypal_capture_id", TestData.uniqueId("CAPTURE"));
        all.put("captured_at", createdAt);
        all.put("reaction_at", createdAt);
        return data.tip(device, "CAPTURED", createdAt, all);
    }

    private static Map<String, Object> request(String note) {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("message", TestData.uniqueMessage("Play Wonderwall please"));
        columns.put("message_kind", "REQUEST");
        columns.put("reaction", "REQUEST");
        columns.put("ai_status", "DONE");
        columns.put("owner_note", note);
        columns.put("requested_song", "Wonderwall");
        columns.put("requested_song_key", "wonderwall");
        return columns;
    }

    /** A flagged tip as stored before Brief 06c: the kind, if any, and no note, language or song. */
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

    /** A flagged request with the model's whole read stored, as classification keeps it now. */
    private static Map<String, Object> flaggedRead(String message, String note, String english) {
        Map<String, Object> columns = flagged(message, "REQUEST");
        columns.put("flag_reason", "threat");
        columns.put("owner_note", note);
        columns.put("message_language", "fr");
        columns.put("message_english", english);
        columns.put("requested_song", "Wondérwall");
        columns.put("requested_song_key", "wonderwall");
        return columns;
    }

    private static OwnerPrincipal principal(UUID ownerId) {
        return new OwnerPrincipal(ownerId, "owner@example.com", "Owner", null);
    }
}
