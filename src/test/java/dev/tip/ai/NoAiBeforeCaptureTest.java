package dev.tip.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.AnthropicStubs;
import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.TestData;
import dev.tip.WebhookEvents;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The model is only ever called for a tip whose payment PayPal confirmed: a created, pending, declined or failed tip
 * costs no call, whatever the recovery job does, so nobody can run up the AI bill without paying.
 */
class NoAiBeforeCaptureTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestData data;

    @Autowired
    AiRecoveryJob recoveryJob;

    @Autowired
    JsonMapper json;

    @Test
    void onlyAVerifiedCaptureLeadsToAModelCall() throws Exception {
        String slug = TestData.uniqueSlug();
        data.device(slug, "Paid Panda");
        String message = TestData.uniqueMessage("Nobody paid for this");
        String orderId = TestData.uniqueId("ORDER");
        PayPalStubs.createOrder(PAYPAL, orderId);

        UUID tipId = createTip(slug, message);
        recoveryJob.recover();
        assertThat(AnthropicStubs.requestsFor(ANTHROPIC, message)).isEmpty();

        PayPalStubs.captureError(PAYPAL, orderId, 422, "UNPROCESSABLE_ENTITY", "INSTRUMENT_DECLINED");
        mvc.perform(post("/api/public/tips/{id}/capture", tipId).with(TestData.newClient()))
                .andExpect(status().isUnprocessableContent());
        String captureId = TestData.uniqueId("CAPTURE");
        webhook(captureId, tipId, orderId, "PAYMENT.CAPTURE.PENDING");
        recoveryJob.recover();
        assertThat(data.tipStatus(tipId)).isEqualTo("PENDING");
        assertThat(AnthropicStubs.requestsFor(ANTHROPIC, message)).isEmpty();

        webhook(captureId, tipId, orderId, "PAYMENT.CAPTURE.COMPLETED");

        await().atMost(Duration.ofSeconds(30)).until(() -> !"PENDING".equals(data.aiStatus(tipId)));
        assertThat(AnthropicStubs.requestsFor(ANTHROPIC, message)).hasSize(1);
    }

    @Test
    void anUnverifiedWebhookNeverLeadsToAModelCall() throws Exception {
        String slug = TestData.uniqueSlug();
        data.device(slug, "Paid Panda");
        String message = TestData.uniqueMessage("Forged payment");
        String orderId = TestData.uniqueId("ORDER");
        PayPalStubs.createOrder(PAYPAL, orderId);
        UUID tipId = createTip(slug, message);
        PayPalStubs.verifyWebhook(PAYPAL, "FAILURE");

        mvc.perform(post("/webhooks/paypal")
                        .headers(WebhookEvents.transmissionHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WebhookEvents.capture(
                                TestData.uniqueId("WH"),
                                "PAYMENT.CAPTURE.COMPLETED",
                                TestData.uniqueId("CAPTURE"),
                                tipId.toString(),
                                orderId)))
                .andExpect(status().isBadRequest());
        recoveryJob.recover();

        assertThat(data.tipStatus(tipId)).isEqualTo("CREATED");
        assertThat(AnthropicStubs.requestsFor(ANTHROPIC, message)).isEmpty();
    }

    private UUID createTip(String slug, String message) throws Exception {
        String body = mvc.perform(post("/api/public/devices/{slug}/tips", slug)
                        .with(TestData.newClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":500,\"currency\":\"GBP\",\"message\":\"" + message + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(body).path("tipId").asString());
    }

    private void webhook(String captureId, UUID tipId, String orderId, String type) throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        mvc.perform(post("/webhooks/paypal")
                        .headers(WebhookEvents.transmissionHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WebhookEvents.capture(
                                TestData.uniqueId("WH"), type, captureId, tipId.toString(), orderId)))
                .andExpect(status().isOk());
    }
}
