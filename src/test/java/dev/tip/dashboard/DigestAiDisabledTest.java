package dev.tip.dashboard;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.AnthropicStubs;
import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.ai.api.TipDigester;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"TIP_AI_ENABLED=false", "ANTHROPIC_API_KEY="})
class DigestAiDisabledTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    DigestJob job;

    @Autowired
    DigestRepository digests;

    @Autowired
    TestData data;

    @Test
    void withoutAiThereIsNoDigesterNoCallAndNoDigest() {
        Instant now = Instant.parse("2026-10-12T15:00:00Z");
        clock.pin(now);
        UUID ownerId = data.owner();
        UUID deviceId = data.device(ownerId, TestData.uniqueSlug(), "Quiet Panda");
        for (int i = 1; i <= 4; i++) {
            Instant at = now.minusSeconds(600L * i);
            data.tip(
                    deviceId,
                    "CAPTURED",
                    at,
                    Map.of("message", "Thanks " + i, "message_kind", "WARM", "ai_status", "DONE", "reaction_at", at));
        }
        digests.markStale(ownerId, LocalDate.parse("2026-10-12"));

        job.run();

        assertThat(context.getBeansOfType(TipDigester.class)).isEmpty();
        assertThat(ANTHROPIC.findAll(postRequestedFor(urlPathEqualTo(AnthropicStubs.MESSAGES_PATH))))
                .isEmpty();
        assertThat(digests.find(ownerId, LocalDate.parse("2026-10-12"))).isEmpty();
    }
}
