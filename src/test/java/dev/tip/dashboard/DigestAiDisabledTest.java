package dev.tip.dashboard;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import dev.tip.AnthropicStubs;
import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.ai.api.TipDigester;
import dev.tip.owners.api.OwnerPrincipal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void withoutAiThereIsNoDigesterNoCallAndNoDigest() throws Exception {
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

        // Notes dropped by a verdict are never rewritten without AI, so the page waits instead.
        jdbc.sql("UPDATE owner_digest SET generated_at = ? WHERE owner_id = ?")
                .params(java.sql.Timestamp.from(now.minusSeconds(3600)), ownerId)
                .update();
        assertThat(digests.dropped(ownerId, LocalDate.parse("2026-10-12"))).isTrue();
        mvc.perform(get("/api/owner/digest")
                        .with(user(new OwnerPrincipal(ownerId, "owner@example.com", "Owner", null))))
                .andExpect(content().string("null"));
    }
}
