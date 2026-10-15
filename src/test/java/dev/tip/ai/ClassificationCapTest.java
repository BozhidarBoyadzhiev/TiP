package dev.tip.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tip.AnthropicStubs;
import dev.tip.IntegrationTest;
import dev.tip.ReactedEvents;
import dev.tip.TestData;
import dev.tip.ai.api.Reaction;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * Past the global cap on classification calls a tip reacts PLAIN once as UNAVAILABLE without a call, and is never
 * classified later. Each test pins its own UTC day, since the cap lives as long as the context.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {"tip.ai.cap.per-hour=2", "tip.ai.cap.per-day=3"})
class ClassificationCapTest extends IntegrationTest {

    @Autowired
    TestData data;

    @Autowired
    TipReactionWorker worker;

    @Autowired
    ReactedEvents reacted;

    UUID deviceId;

    @BeforeEach
    void device() {
        deviceId = data.device();
    }

    @Test
    void pastTheHourlyCapATipReactsPlainOnceAndIsNeverClassifiedLater(CapturedOutput output) {
        Instant hour = Instant.parse("2026-11-02T10:00:00Z");
        clock.pin(hour.plus(Duration.ofMinutes(5)));
        assertClassified(classify("First"));
        assertClassified(classify("Second"));

        String over = TestData.uniqueMessage("Over the cap");
        UUID overTip = classifyRaw(over);
        UUID alsoOver = classify("Also over the cap");
        worker.process(overTip);

        assertUnavailable(overTip);
        assertUnavailable(alsoOver);
        assertThat(AnthropicStubs.requestsFor(ANTHROPIC, over)).isEmpty();
        assertThat(output.getOut())
                .containsOnlyOnce("ai_cap_reached window=hour limit=2 since=2026-11-02T10:00:00Z")
                .contains("Tip " + overTip + " reacts PLAIN once, the classification cap is reached")
                .doesNotContain(over);

        clock.pin(hour.plus(Duration.ofHours(1)));
        assertClassified(classify("Next hour"));
    }

    @Test
    void pastTheDailyCapNoHourHasRoomUntilTheNextDay(CapturedOutput output) {
        Instant day = Instant.parse("2026-11-03T00:00:00Z");
        clock.pin(day.plus(Duration.ofHours(9)));
        assertClassified(classify("Nine"));
        assertClassified(classify("Nine again"));
        clock.pin(day.plus(Duration.ofHours(10)));
        assertClassified(classify("Ten"));

        clock.pin(day.plus(Duration.ofHours(11)));
        assertUnavailable(classify("Eleven"));
        clock.pin(day.plus(Duration.ofHours(23)));
        assertUnavailable(classify("Late"));
        assertThat(output.getOut()).containsOnlyOnce("ai_cap_reached window=day limit=3 since=2026-11-03T00:00:00Z");

        clock.pin(day.plus(Duration.ofDays(1)));
        assertClassified(classify("Next day"));
    }

    @Test
    void aTipWithoutAMessageTakesNothingFromTheCap() {
        clock.pin(Instant.parse("2026-11-04T10:00:00Z"));
        UUID silent = data.capturedTip(deviceId, null);
        worker.process(silent);
        assertThat(data.tipRow(silent)).containsEntry("ai_status", "SKIPPED");

        assertClassified(classify("One"));
        assertClassified(classify("Two"));
    }

    private UUID classify(String text) {
        return classifyRaw(TestData.uniqueMessage(text));
    }

    /** A captured tip with {@code message}, answered WARM by the model, processed once. */
    private UUID classifyRaw(String message) {
        AnthropicStubs.replyFor(ANTHROPIC, message, AnthropicStubs.classification("WARM", false, null));
        UUID tipId = data.capturedTip(deviceId, message);
        worker.process(tipId);
        return tipId;
    }

    private void assertClassified(UUID tipId) {
        assertThat(data.tipRow(tipId)).containsEntry("ai_status", "DONE").containsEntry("reaction", "WARM");
    }

    private void assertUnavailable(UUID tipId) {
        assertThat(data.tipRow(tipId))
                .containsEntry("ai_status", "UNAVAILABLE")
                .containsEntry("reaction", "PLAIN")
                .containsEntry("message_kind", null);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> !reacted.forTip(tipId).isEmpty());
        assertThat(reacted.forTip(tipId)).singleElement().satisfies(e -> {
            assertThat(e.reaction()).isEqualTo(Reaction.PLAIN);
            assertThat(e.repeats()).isOne();
        });
    }
}
