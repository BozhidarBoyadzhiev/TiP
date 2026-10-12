package dev.tip.dashboard;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.tip.AnthropicStubs;
import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.ai.api.Reaction;
import dev.tip.ai.api.TipReacted;
import dev.tip.dashboard.DigestRepository.StoredDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class DigestJobTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-12");
    private static final String DIGEST_REQUEST = "Write the panda";

    @Autowired
    DigestJob job;

    @Autowired
    DigestRepository digests;

    @Autowired
    TestData data;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    UUID ownerId;
    UUID deviceId;

    @BeforeEach
    void ownerWithADevice() {
        clock.pin(NOW);
        ownerId = data.owner();
        deviceId = data.device(ownerId, TestData.uniqueSlug(), "Digest Panda");
        reply(List.of("Warm thanks all afternoon."), "[]");
    }

    @Test
    void storesTheDigestOfTheDaysMessagesWithTheWindowsNotes() {
        String a = message("Lovely music", NOW.minusSeconds(3000));
        String b = message("Thanks for the tunes", NOW.minusSeconds(2000));
        UUID requestTip = request("Could you play Wonderwall", "Request: Wonderwall", NOW.minusSeconds(1000));
        UUID oldRequest = request("More Oasis please", "Request: something by Oasis", NOW.minus(Duration.ofDays(3)));
        reply(
                List.of("Two asked for Wonderwall.", "Lots of thanks."),
                "[{\"label\":\"Play Wonderwall\",\"ids\":[\"t1\",\"t9\"]},{\"label\":\"Oasis\",\"ids\":[\"t4\",\"t1\"]}]");
        digests.markStale(ownerId, TODAY);

        job.run();

        StoredDigest stored = digests.find(ownerId, TODAY).orElseThrow();
        assertThat(stored.bullets()).containsExactly("Two asked for Wonderwall.", "Lots of thanks.");
        assertThat(stored.messageCount()).isEqualTo(3);
        assertThat(stored.generatedAt()).isEqualTo(NOW);
        assertThat(stored.groups())
                .containsExactly(
                        new RequestsReport.StoredGroup("Play Wonderwall", List.of(requestTip)),
                        new RequestsReport.StoredGroup("Oasis", List.of(oldRequest)));
        String body = onlyDigestRequest().getBodyAsString();
        assertThat(body)
                .contains(
                        a, b, "<tip_message id=\\\"t1\\\">", "<owner_note id=\\\"t4\\\">\\nRequest: something by Oasis")
                .contains("Request: Wonderwall");
        assertThat(row()).containsEntry("stale", false).containsEntry("claimed_at", null);
    }

    @Test
    void flaggedAndUnclassifiedTipsNeverReachTheModel() {
        message("Lovely music", NOW.minusSeconds(3000));
        message("Thanks for the tunes", NOW.minusSeconds(2000));
        message("You made my day", NOW.minusSeconds(1000));
        String abuse = TestData.uniqueMessage("You are a talentless clown");
        String abusiveNote = TestData.uniqueMessage("Request: stop singing");
        tip(
                Map.of("message", abuse, "flagged", true, "flag_reason", "insult", "message_kind", "PLAIN"),
                NOW.minusSeconds(500));
        tip(
                Map.of(
                        "message",
                        TestData.uniqueMessage("Play Wonderwall or else"),
                        "flagged",
                        true,
                        "flag_reason",
                        "threat",
                        "message_kind",
                        "REQUEST",
                        "owner_note",
                        abusiveNote),
                NOW.minusSeconds(400));
        String unread = TestData.uniqueMessage("Not classified yet");
        data.tip(deviceId, "CAPTURED", NOW.minusSeconds(300), Map.of("message", unread, "ai_status", "PENDING"));
        digests.markStale(ownerId, TODAY);

        job.run();

        assertThat(onlyDigestRequest().getBodyAsString()).doesNotContain(abuse, abusiveNote, unread);
        assertThat(digests.find(ownerId, TODAY).orElseThrow().messageCount()).isEqualTo(3);
    }

    @Test
    void fewerThanThreeUnflaggedMessagesMeansNoCallAndNoDigest() {
        message("Lovely music", NOW.minusSeconds(3000));
        message("Thanks", NOW.minusSeconds(2000));
        tip(
                Map.of("message", "Rubbish clown", "flagged", true, "flag_reason", "insult", "message_kind", "PLAIN"),
                NOW.minusSeconds(100));
        message("Yesterday counts for nothing", NOW.minus(Duration.ofDays(1)));
        digests.markStale(ownerId, TODAY);

        job.run();

        assertThat(digestRequests()).isEmpty();
        assertThat(digests.find(ownerId, TODAY)).isEmpty();
        assertThat(row()).containsEntry("stale", false).containsEntry("claimed_at", null);
    }

    @Test
    void regeneratesAtMostOnceEveryTenMinutesPerOwner() {
        threeMessages();
        digests.markStale(ownerId, TODAY);
        job.run();
        assertThat(digestRequests()).hasSize(1);

        clock.advance(Duration.ofMinutes(2));
        message("And another one", clock.instant());
        digests.markStale(ownerId, TODAY);
        job.run();
        clock.advance(Duration.ofMinutes(7).plusSeconds(59));
        job.run();
        assertThat(digestRequests()).hasSize(1);
        assertThat(row()).containsEntry("stale", true);

        clock.advance(Duration.ofSeconds(1));
        job.run();
        assertThat(digestRequests()).hasSize(2);
        assertThat(digests.find(ownerId, TODAY).orElseThrow().messageCount()).isEqualTo(4);
    }

    @Test
    void nothingNewSinceTheStoredDigestMeansNoCall() {
        threeMessages();
        digests.markStale(ownerId, TODAY);
        job.run();
        clock.advance(Duration.ofMinutes(11));
        tip(
                Map.of("message", "Clown", "flagged", true, "flag_reason", "insult", "message_kind", "PLAIN"),
                clock.instant());
        digests.markStale(ownerId, TODAY);

        job.run();

        assertThat(digestRequests()).hasSize(1);
    }

    @Test
    void storeRejectsAWatermarkThatIsNotNewer() {
        digests.markStale(ownerId, TODAY);
        assertThat(digests.store(ownerId, TODAY, List.of("Newer."), List.of(), 4, NOW, NOW))
                .isTrue();

        assertThat(digests.store(ownerId, TODAY, List.of("Older."), List.of(), 3, NOW.minusSeconds(1), NOW))
                .isFalse();
        assertThat(digests.store(ownerId, TODAY, List.of("Same."), List.of(), 3, NOW, NOW))
                .isFalse();
        assertThat(digests.find(ownerId, TODAY).orElseThrow().bullets()).containsExactly("Newer.");
    }

    @Test
    void aFailedCallKeepsThePreviousDigestAndTriesAgainOnTheNextRun() {
        threeMessages();
        digests.markStale(ownerId, TODAY);
        job.run();
        clock.advance(Duration.ofMinutes(11));
        message("Another one", clock.instant());
        ANTHROPIC.stubFor(post(urlPathEqualTo(AnthropicStubs.MESSAGES_PATH))
                .atPriority(1)
                .withRequestBody(containing(DIGEST_REQUEST))
                .willReturn(aResponse().withStatus(529)));
        digests.markStale(ownerId, TODAY);

        job.run();

        assertThat(digests.find(ownerId, TODAY).orElseThrow().bullets()).containsExactly("Warm thanks all afternoon.");
        assertThat(row()).containsEntry("stale", true).containsEntry("claimed_at", null);

        ANTHROPIC.resetAll();
        AnthropicStubs.fallback(ANTHROPIC);
        reply(List.of("Back again."), "[]");
        clock.advance(Duration.ofMinutes(2));
        job.run();
        assertThat(digests.find(ownerId, TODAY).orElseThrow().bullets()).containsExactly("Back again.");
    }

    @Test
    void anOutputTheParserRejectsKeepsThePreviousDigest() {
        threeMessages();
        reply(List.of(""), "[]");
        digests.markStale(ownerId, TODAY);

        job.run();

        assertThat(digests.find(ownerId, TODAY)).isEmpty();
        assertThat(row()).containsEntry("stale", true);
    }

    @Test
    void aReactionOfTheOwnerMarksTodaysDigestStaleInTheirZone() {
        data.setTimeZone(ownerId, "Pacific/Auckland");
        transactions.executeWithoutResult(
                status -> events.publishEvent(new TipReacted(UUID.randomUUID(), deviceId, Reaction.WARM, 1, NOW)));

        // 15:00 UTC on 12 October is 04:00 on 13 October in Auckland.
        await().atMost(Duration.ofSeconds(30))
                .until(() ->
                        jdbc.sql("SELECT count(*) FROM owner_digest WHERE owner_id = ? AND local_day = ? AND stale")
                                        .params(ownerId, LocalDate.parse("2026-10-13"))
                                        .query(Long.class)
                                        .single()
                                == 1);
    }

    private void threeMessages() {
        message("Lovely music", NOW.minusSeconds(3000));
        message("Thanks for the tunes", NOW.minusSeconds(2000));
        message("You made my day", NOW.minusSeconds(1000));
    }

    /** An unflagged classified message reacted at {@code at}; returns its text. */
    private String message(String text, Instant at) {
        String message = TestData.uniqueMessage(text);
        tip(Map.of("message", message, "message_kind", "WARM"), at);
        return message;
    }

    private UUID request(String text, String note, Instant at) {
        return tip(Map.of("message", TestData.uniqueMessage(text), "message_kind", "REQUEST", "owner_note", note), at);
    }

    private UUID tip(Map<String, Object> columns, Instant at) {
        Map<String, Object> all = new LinkedHashMap<>(columns);
        all.putIfAbsent("flagged", false);
        all.put("ai_status", "DONE");
        all.put("reaction", Boolean.TRUE.equals(all.get("flagged")) ? "PLAIN" : all.get("message_kind"));
        all.put("reaction_at", at);
        all.put("captured_at", at);
        all.put("paypal_capture_id", TestData.uniqueId("CAPTURE"));
        return data.tip(deviceId, "CAPTURED", at, all);
    }

    private void reply(List<String> bullets, String groups) {
        ANTHROPIC.stubFor(post(urlPathEqualTo(AnthropicStubs.MESSAGES_PATH))
                .atPriority(5)
                .withRequestBody(containing(DIGEST_REQUEST))
                .willReturn(AnthropicStubs.reply(AnthropicStubs.digest(bullets, groups))));
    }

    private List<LoggedRequest> digestRequests() {
        return ANTHROPIC
                .findAll(postRequestedFor(urlPathEqualTo(AnthropicStubs.MESSAGES_PATH))
                        .withRequestBody(containing(DIGEST_REQUEST)))
                .stream()
                .filter(r -> r.getBodyAsString().contains(deviceIdMessages()))
                .toList();
    }

    /** Requests of other tests' owners could share the server, so only this owner's count. */
    private String deviceIdMessages() {
        return jdbc.sql("SELECT message FROM tip WHERE device_id = ? AND message_kind = 'WARM' AND NOT flagged"
                        + " ORDER BY created_at LIMIT 1")
                .param(deviceId)
                .query(String.class)
                .optional()
                .orElse("no message");
    }

    private LoggedRequest onlyDigestRequest() {
        assertThat(digestRequests()).hasSize(1);
        return digestRequests().getFirst();
    }

    private Map<String, Object> row() {
        return jdbc.sql("SELECT * FROM owner_digest WHERE owner_id = ? AND local_day = ?")
                .params(ownerId, TODAY)
                .query()
                .singleRow();
    }
}
