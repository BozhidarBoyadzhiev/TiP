package dev.tip.dashboard;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.tip.AnthropicStubs;
import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.owners.api.OwnerPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * An overruled message is read by the next digest under the usual limits; hiding it again drops the digest
 * and its request groups at once. The job is run by the test, with time on the {@code MutableClock}.
 */
class ModerationDigestTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-12");
    private static final String DIGEST_REQUEST = "Write the panda";

    @Autowired
    DigestJob job;

    @Autowired
    DigestRepository digests;

    @Autowired
    MockMvc mvc;

    @Autowired
    TestData data;

    @Autowired
    JdbcClient jdbc;

    OwnerPrincipal owner;
    UUID deviceId;
    final List<String> messages = new ArrayList<>();

    @BeforeEach
    void ownerWithADevice() {
        clock.pin(NOW);
        // Only this test's digest is stale, so the job works on it alone.
        jdbc.sql("UPDATE owner_digest SET stale = false").update();
        owner = new OwnerPrincipal(data.owner(), "owner@example.com", "Owner", null);
        deviceId = data.device(owner.id(), TestData.uniqueSlug(), "Digest Panda");
        messages.clear();
        ANTHROPIC.stubFor(post(urlPathEqualTo(AnthropicStubs.MESSAGES_PATH))
                .atPriority(5)
                .withRequestBody(containing(DIGEST_REQUEST))
                .willReturn(AnthropicStubs.reply(AnthropicStubs.digest(
                        List.of("Warm thanks all afternoon."), "[{\"label\":\"Play Wonderwall\",\"ids\":[\"t1\"]}]"))));
    }

    @Test
    void anOverruledMessageIsReadByTheNextDigestOnceTheUsualTenMinutesHavePassed() throws Exception {
        message("Lovely music", NOW.minusSeconds(3000));
        message("Thanks for the tunes", NOW.minusSeconds(2000));
        message("You made my day", NOW.minusSeconds(1000));
        String flaggedText = TestData.uniqueMessage("Wonderwall now, you clown");
        UUID flagged = flagged(flaggedText, NOW.minusSeconds(500));
        digests.markStale(owner.id(), TODAY);
        job.run();
        assertThat(digestRequests())
                .singleElement()
                .satisfies(r -> assertThat(r.getBodyAsString()).doesNotContain(flaggedText));

        clock.advance(Duration.ofMinutes(1));
        verdict(flagged, false);
        assertThat(row()).containsEntry("stale", true);
        job.run();
        assertThat(digestRequests()).as("the 10 minute limit still holds").hasSize(1);

        clock.advance(Duration.ofMinutes(10));
        job.run();

        assertThat(digestRequests()).hasSize(2);
        assertThat(digestRequests().getLast().getBodyAsString())
                .contains(flaggedText)
                .contains("Request: Wonderwall")
                .contains("<tip_message id=\\\"t");
        assertThat(digests.find(owner.id(), TODAY).orElseThrow().messageCount()).isEqualTo(4);
    }

    @Test
    void hidingAgainDropsTheNotesAndGroupsAtOnceAndTheNextDigestLeavesTheMessageOut() throws Exception {
        message("Lovely music", NOW.minusSeconds(3000));
        message("Thanks for the tunes", NOW.minusSeconds(2000));
        message("You made my day", NOW.minusSeconds(1000));
        String flaggedText = TestData.uniqueMessage("Wonderwall now, you clown");
        UUID flagged = flagged(flaggedText, NOW.minusSeconds(500));
        verdict(flagged, false);
        job.run();
        assertThat(digestRequests())
                .singleElement()
                .satisfies(r -> assertThat(r.getBodyAsString()).contains(flaggedText));
        asOwner("/api/owner/requests")
                .andExpect(jsonPath("$.grouped").value("ai"))
                .andExpect(jsonPath("$.groups[0].label").value("Play Wonderwall"));

        clock.advance(Duration.ofMinutes(1));
        verdict(flagged, true);

        // No job run in between: the notes and the groups are gone the moment the message is hidden again, and
        // with three messages still shown the panda is rewriting them, not waiting for messages.
        asOwner("/api/owner/digest").andExpect(content().json("{\"status\":\"rewriting\"}", true));
        asOwner("/api/owner/requests")
                .andExpect(jsonPath("$.grouped").value("exact"))
                .andExpect(jsonPath("$.groups.length()").value(0));
        assertThat(row())
                .containsEntry("bullets", null)
                .containsEntry("request_groups", null)
                .containsEntry("source_watermark", null)
                .containsEntry("stale", true);

        job.run();
        assertThat(digestRequests()).as("the 10 minute limit still holds").hasSize(1);
        clock.advance(Duration.ofMinutes(10));
        job.run();

        assertThat(digestRequests()).hasSize(2);
        assertThat(digestRequests().getLast().getBodyAsString()).doesNotContain(flaggedText);
        asOwner("/api/owner/digest").andExpect(jsonPath("$.messageCount").value(3));
    }

    @Test
    void withFewerThanThreeShownMessagesLeftTheNotesStayEmpty() throws Exception {
        message("Lovely music", NOW.minusSeconds(3000));
        message("Thanks for the tunes", NOW.minusSeconds(2000));
        UUID flagged = flagged(TestData.uniqueMessage("Wonderwall now, you clown"), NOW.minusSeconds(500));
        verdict(flagged, false);
        job.run();
        asOwner("/api/owner/digest").andExpect(jsonPath("$.messageCount").value(3));

        verdict(flagged, true);
        asOwner("/api/owner/digest").andExpect(content().string("null"));
        clock.advance(Duration.ofMinutes(11));
        job.run();

        assertThat(digestRequests()).hasSize(1);
        asOwner("/api/owner/digest").andExpect(content().string("null"));
        assertThat(row()).containsEntry("stale", false).containsEntry("claimed_at", null);
    }

    @Test
    void notesNeverWrittenTodayAreNotBeingRewrittenWhateverTheMessages() throws Exception {
        message("Lovely music", NOW.minusSeconds(3000));
        message("Thanks for the tunes", NOW.minusSeconds(2000));
        message("You made my day", NOW.minusSeconds(1000));
        UUID flagged = flagged(TestData.uniqueMessage("Wonderwall now, you clown"), NOW.minusSeconds(500));

        verdict(flagged, false);
        verdict(flagged, true);

        assertThat(row()).containsEntry("generated_at", null).containsEntry("bullets", null);
        asOwner("/api/owner/digest").andExpect(content().string("null"));
    }

    private void verdict(UUID tip, boolean hidden) throws Exception {
        mvc.perform(put("/api/owner/tips/{id}/verdict", tip)
                        .with(user(owner))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":" + hidden + "}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions asOwner(String path) throws Exception {
        return mvc.perform(get(path).with(user(owner))).andExpect(status().isOk());
    }

    private void message(String text, Instant at) {
        String message = TestData.uniqueMessage(text);
        messages.add(message);
        tip(Map.of("message", message, "message_kind", "WARM", "reaction", "WARM"), at);
    }

    /** A flagged request with its stored read, which an overrule shows. */
    private UUID flagged(String text, Instant at) {
        return tip(
                Map.of(
                        "message",
                        text,
                        "message_kind",
                        "REQUEST",
                        "reaction",
                        "PLAIN",
                        "flagged",
                        true,
                        "flag_reason",
                        "insult",
                        "owner_note",
                        "Request: Wonderwall"),
                at);
    }

    private UUID tip(Map<String, Object> columns, Instant at) {
        Map<String, Object> all = new LinkedHashMap<>(columns);
        all.put("ai_status", "DONE");
        all.put("reaction_at", at);
        all.put("captured_at", at);
        all.put("paypal_capture_id", TestData.uniqueId("CAPTURE"));
        return data.tip(deviceId, "CAPTURED", at, all);
    }

    /** Other tests' owners share the server, so only requests carrying this test's messages count. */
    private List<LoggedRequest> digestRequests() {
        return ANTHROPIC
                .findAll(postRequestedFor(urlPathEqualTo(AnthropicStubs.MESSAGES_PATH))
                        .withRequestBody(containing(DIGEST_REQUEST)))
                .stream()
                .filter(r -> r.getBodyAsString().contains(messages.getFirst()))
                .toList();
    }

    private Map<String, Object> row() {
        return jdbc.sql("SELECT * FROM owner_digest WHERE owner_id = ? AND local_day = ?")
                .params(owner.id(), TODAY)
                .query()
                .singleRow();
    }
}
