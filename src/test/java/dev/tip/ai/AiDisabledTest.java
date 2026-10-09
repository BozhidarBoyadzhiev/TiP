package dev.tip.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anthropic.client.AnthropicClient;
import dev.tip.AnthropicStubs;
import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.ReactedEvents;
import dev.tip.TestData;
import dev.tip.ai.api.Reaction;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@TestPropertySource(properties = {"TIP_AI_ENABLED=false", "ANTHROPIC_API_KEY="})
class AiDisabledTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mvc;

    @Autowired
    TestData data;

    @Autowired
    ReactedEvents reacted;

    @Test
    void startsWithoutAKeyAndBuildsNoClaudeClient() {
        assertThat(context.getBeansOfType(AnthropicClient.class)).isEmpty();
        assertThat(context.getBeansOfType(MessageClassifier.class)).isEmpty();
    }

    @Test
    void capturedTipWithAMessageIsSkippedAsPlain() throws Exception {
        String orderId = TestData.uniqueId("ORDER");
        UUID tipId = data.tipWithMessage(data.device(), orderId, TestData.uniqueMessage("Brilliant"));
        PayPalStubs.captureCompleted(PAYPAL, orderId, TestData.uniqueId("CAPTURE"));

        mvc.perform(post("/api/public/tips/{tipId}/capture", tipId)).andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(15)).until(() -> "SKIPPED".equals(data.aiStatus(tipId)));
        assertThat(data.tipRow(tipId))
                .containsEntry("reaction", "PLAIN")
                .containsEntry("message_kind", null)
                .containsEntry("flagged", false);
        await().atMost(Duration.ofSeconds(5)).until(() -> !reacted.forTip(tipId).isEmpty());
        assertThat(reacted.forTip(tipId))
                .singleElement()
                .satisfies(e -> assertThat(e.reaction()).isEqualTo(Reaction.PLAIN));
        assertThat(ANTHROPIC.findAll(postRequestedFor(urlPathEqualTo(AnthropicStubs.MESSAGES_PATH))))
                .isEmpty();
    }
}
