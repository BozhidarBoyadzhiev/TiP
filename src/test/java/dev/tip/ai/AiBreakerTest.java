package dev.tip.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import dev.tip.AnthropicStubs;
import dev.tip.IntegrationTest;
import dev.tip.ReactedEvents;
import dev.tip.TestData;
import dev.tip.ai.api.AiUsage;
import dev.tip.ai.api.Reaction;
import dev.tip.owners.api.OwnerPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A failed model call never fails a paid tip: it reacts PLAIN once as UNAVAILABLE and is not retried. Any 400, 401,
 * 402, 403 or 404 also rests the model for 10 minutes, then lets one trial call through.
 */
@ExtendWith(OutputCaptureExtension.class)
class AiBreakerTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    @Autowired
    TestData data;

    @Autowired
    TipReactionWorker worker;

    @Autowired
    AiRecoveryJob recoveryJob;

    @Autowired
    AiBreaker breaker;

    @Autowired
    AiUsage usage;

    @Autowired
    MockMvc mvc;

    @Autowired
    ReactedEvents reacted;

    @Autowired
    JdbcClient jdbc;

    UUID deviceId;

    @BeforeEach
    void device() {
        clock.pin(NOW);
        deviceId = data.device();
    }

    /** The breaker lives as long as the context, so a test that opened it closes it the way the app does. */
    @AfterEach
    void closeTheBreaker() {
        breaker.succeeded();
    }

    @ParameterizedTest
    @CsvSource({
        "402, billing_error, Your credit balance is too low to access the Anthropic API",
        "401, authentication_error, invalid x-api-key",
        "403, permission_error, Your API key does not have permission",
        "400, invalid_request_error, You have reached your specified workspace API usage limits",
        "400, invalid_request_error, max_tokens: must be at most 64000",
        "404, not_found_error, model: claude-haiku-5-5"
    })
    void anyRejectionOfTheRequestRestsTheModelForTenMinutes(int status, String type, String text, CapturedOutput output)
            throws Exception {
        String first = TestData.uniqueMessage("First");
        UUID firstTip = tip(first, 1000);
        fail(first, status, type, text);

        worker.process(firstTip);

        assertUnavailable(firstTip);
        assertThat(breaker.resting()).isTrue();
        mvc.perform(get("/api/owner/me").with(user(owner())))
                .andExpect(jsonPath("$.aiResting").value(true));
        assertThat(output.getOut())
                .contains("ai_breaker_opened status=" + status + " type=" + type + " message=" + text)
                .doesNotContain(first);

        String during = TestData.uniqueMessage("During the rest");
        UUID duringTip = tip(during, 500);
        AnthropicStubs.replyFor(ANTHROPIC, during, AnthropicStubs.classification("WARM", false, null));
        clock.pin(NOW.plus(Duration.ofMinutes(9)));
        worker.process(duringTip);

        assertUnavailable(duringTip);
        assertThat(AnthropicStubs.requestsFor(ANTHROPIC, during)).isEmpty();

        String after = TestData.uniqueMessage("After the rest");
        UUID afterTip = tip(after, 500);
        AnthropicStubs.replyFor(ANTHROPIC, after, AnthropicStubs.classification("WARM", false, null));
        clock.pin(NOW.plus(Duration.ofMinutes(10)));
        worker.process(afterTip);

        assertThat(data.tipRow(afterTip)).containsEntry("ai_status", "DONE").containsEntry("reaction", "WARM");
        assertThat(breaker.resting()).isFalse();
        assertThat(output.getOut()).contains("ai_breaker_closed");
        mvc.perform(get("/api/owner/me").with(user(owner())))
                .andExpect(jsonPath("$.aiResting").value(false));
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503, 529})
    void otherFailuresReactPlainOnceWithoutRetryAndLeaveTheModelAwake(int status) {
        String message = TestData.uniqueMessage("Unlucky");
        UUID tipId = tip(message, 1000);
        fail(message, status, "api_error", "something went wrong");

        worker.process(tipId);
        recoveryJob.recover();

        assertUnavailable(tipId);
        assertThat(AnthropicStubs.requestsFor(ANTHROPIC, message)).hasSize(1);
        assertThat(breaker.resting()).isFalse();
        String next = TestData.uniqueMessage("Next one");
        UUID nextTip = tip(next, 200);
        AnthropicStubs.replyFor(ANTHROPIC, next, AnthropicStubs.classification("PLAYFUL", false, null));
        worker.process(nextTip);
        assertThat(data.tipRow(nextTip)).containsEntry("ai_status", "DONE");
    }

    /**
     * A 5xx, a read timeout (the stub answers after the 2 s test timeout) and a connection that fails without an
     * answer: each tip reacts PLAIN once as UNAVAILABLE, the device is sent PLAIN x1, the breaker stays closed, and
     * the next tip calls the model as usual.
     */
    @ParameterizedTest
    @EnumSource(Failure.class)
    void aFailedCallEndsThatTipOnlyAndLeavesTheBreakerClosed(Failure failure, CapturedOutput output) {
        String message = TestData.uniqueMessage("Bad luck " + failure);
        UUID tipId = tip(message, 1000);
        ANTHROPIC.stubFor(post(urlPathEqualTo(AnthropicStubs.MESSAGES_PATH))
                .withRequestBody(containing(message))
                .willReturn(failure.response()));

        worker.process(tipId);
        recoveryJob.recover();

        assertUnavailable(tipId);
        await().atMost(Duration.ofSeconds(30)).until(() -> delivery(tipId).isPresent());
        assertThat(delivery(tipId).orElseThrow()).containsEntry("reaction", "PLAIN");
        assertThat(((Number) delivery(tipId).orElseThrow().get("repeats")).intValue())
                .isOne();
        assertThat(AnthropicStubs.requestsFor(ANTHROPIC, message)).hasSize(1);
        assertThat(breaker.resting()).isFalse();
        assertThat(output.getOut())
                .contains("Tip " + tipId + " reacts PLAIN once, the model is unavailable: " + failure.reason)
                .doesNotContain("ai_breaker_opened");

        String next = TestData.uniqueMessage("Next one after " + failure);
        UUID nextTip = tip(next, 200);
        AnthropicStubs.replyFor(ANTHROPIC, next, AnthropicStubs.classification("PLAYFUL", false, null));
        worker.process(nextTip);

        assertThat(data.tipRow(nextTip)).containsEntry("ai_status", "DONE").containsEntry("reaction", "PLAYFUL");
        assertThat(AnthropicStubs.requestsFor(ANTHROPIC, next)).hasSize(1);
    }

    enum Failure {
        SERVER_ERROR("api status 503"),
        READ_TIMEOUT("AnthropicIoException"),
        CONNECTION_RESET("AnthropicIoException"),
        EMPTY_RESPONSE("AnthropicIoException");

        /** What the worker logs as the reason, which names the SDK exception for a call without an answer. */
        final String reason;

        Failure(String reason) {
            this.reason = reason;
        }

        ResponseDefinitionBuilder response() {
            return switch (this) {
                case SERVER_ERROR ->
                    aResponse()
                            .withStatus(503)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\","
                                    + "\"message\":\"Service unavailable\"}}");
                case READ_TIMEOUT ->
                    AnthropicStubs.reply(AnthropicStubs.classification("WARM", false, null))
                            .withFixedDelay(3_000);
                case CONNECTION_RESET -> aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER);
                case EMPTY_RESPONSE -> aResponse().withFault(Fault.EMPTY_RESPONSE);
            };
        }
    }

    @Test
    void aTrialThatHitsTheLimitAgainRestsForAnotherTenMinutes() {
        String first = TestData.uniqueMessage("Spent");
        fail(first, 402, "billing_error", "Your credit balance is too low");
        worker.process(tip(first, 100));
        assertThat(breaker.resting()).isTrue();

        String trial = TestData.uniqueMessage("Trial");
        fail(trial, 402, "billing_error", "Your credit balance is too low");
        clock.pin(NOW.plus(Duration.ofMinutes(10)));
        worker.process(tip(trial, 100));

        assertThat(AnthropicStubs.requestsFor(ANTHROPIC, trial)).hasSize(1);
        String later = TestData.uniqueMessage("Still resting");
        AnthropicStubs.replyFor(ANTHROPIC, later, AnthropicStubs.classification("WARM", false, null));
        clock.pin(NOW.plus(Duration.ofMinutes(19)));
        UUID laterTip = tip(later, 100);
        worker.process(laterTip);

        assertUnavailable(laterTip);
        assertThat(AnthropicStubs.requestsFor(ANTHROPIC, later)).isEmpty();
    }

    @Test
    void onlyOneTrialGoesOutAfterTheRest() {
        breaker.failed(402, "billing_error", "Your credit balance is too low");
        clock.pin(NOW.plus(AiBreaker.REST));

        assertThat(breaker.tryCall()).isTrue();
        assertThat(breaker.tryCall()).isFalse();
        breaker.failedWithoutAnswer();
        assertThat(breaker.tryCall()).isTrue();
    }

    @Test
    void everyCallAndFailureIsCountedByTheHour() {
        Instant from = NOW.plus(Duration.ofDays(400));
        clock.pin(from);
        AiUsage.Totals before = usage.since(from);
        String ok = TestData.uniqueMessage("Counted");
        AnthropicStubs.replyFor(ANTHROPIC, ok, AnthropicStubs.classification("WARM", false, null));
        String bad = TestData.uniqueMessage("Counted failure");
        fail(bad, 500, "api_error", "boom");

        worker.process(tip(ok, 100));
        worker.process(tip(bad, 100));

        AiUsage.Totals after = usage.since(from);
        assertThat(after.calls() - before.calls()).isEqualTo(2);
        assertThat(after.failures() - before.failures()).isEqualTo(1);
    }

    private UUID tip(String message, long amountMinor) {
        UUID tipId = data.capturedTip(deviceId, message);
        data.setAmount(tipId, amountMinor);
        return tipId;
    }

    private void assertUnavailable(UUID tipId) {
        assertThat(data.tipRow(tipId))
                .containsEntry("ai_status", "UNAVAILABLE")
                .containsEntry("reaction", "PLAIN")
                .containsEntry("message_kind", null)
                .containsEntry("flagged", false);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> !reacted.forTip(tipId).isEmpty());
        assertThat(reacted.forTip(tipId)).singleElement().satisfies(e -> {
            assertThat(e.reaction()).isEqualTo(Reaction.PLAIN);
            assertThat(e.repeats()).isOne();
        });
    }

    private static void fail(String message, int status, String type, String text) {
        ANTHROPIC.stubFor(post(urlPathEqualTo(AnthropicStubs.MESSAGES_PATH))
                .withRequestBody(containing(message))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + text
                                + "\"}}")));
    }

    private Optional<Map<String, Object>> delivery(UUID tipId) {
        return jdbc
                .sql("SELECT reaction, repeats FROM reaction_delivery WHERE tip_id = ?")
                .param(tipId)
                .query()
                .listOfRows()
                .stream()
                .findFirst();
    }

    private OwnerPrincipal owner() {
        String email = TestData.uniqueEmail();
        return new OwnerPrincipal(data.owner(email, "Alex", TestData.OWNER_PASSWORD), email, "Alex", null);
    }
}
