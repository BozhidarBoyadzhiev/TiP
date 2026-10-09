package dev.tip.tipping;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.CapturedEvents;
import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.TestData;
import dev.tip.WebhookEvents;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class PayPalWebhookTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestData data;

    @Autowired
    CapturedEvents events;

    UUID deviceId;
    String orderId;
    String captureId;
    String eventId;
    UUID tipId;

    @BeforeEach
    void createdTip() {
        deviceId = data.device();
        orderId = TestData.uniqueId("ORDER");
        captureId = TestData.uniqueId("CAPTURE");
        eventId = TestData.uniqueId("WH");
        tipId = data.tip(deviceId, "CREATED", orderId, Instant.now());
    }

    @Test
    void completedWebhookCapturesTheTipAndPublishesOneEvent() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");

        webhook(WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.COMPLETED", captureId, tipId.toString(), orderId))
                .andExpect(status().isOk());

        assertThat(data.tipRow(tipId))
                .containsEntry("status", "CAPTURED")
                .containsEntry("paypal_capture_id", captureId);
        assertThat(events.forTip(tipId)).hasSize(1);
        assertThat(data.webhookEvents(eventId)).isEqualTo(1);
    }

    @Test
    void tipIsFoundByRelatedOrderIdWhenCustomIdIsMissing() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");

        webhook(WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.COMPLETED", captureId, null, orderId))
                .andExpect(status().isOk());

        assertThat(data.tipStatus(tipId)).isEqualTo("CAPTURED");
    }

    @Test
    void failedVerificationReturns400AndStoresNothing() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "FAILURE");

        webhook(WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.COMPLETED", captureId, tipId.toString(), orderId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEBHOOK_NOT_VERIFIED"));

        assertThat(data.webhookEvents(eventId)).isZero();
        assertThat(data.tipStatus(tipId)).isEqualTo("CREATED");
        assertThat(events.forTip(tipId)).isEmpty();
    }

    @Test
    void missingTransmissionHeadersAreRejectedWithoutAskingPayPal() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/webhooks/paypal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WebhookEvents.capture(
                                eventId, "PAYMENT.CAPTURE.COMPLETED", captureId, tipId.toString(), orderId)))
                .andExpect(status().isBadRequest());

        PAYPAL.verify(0, postRequestedFor(urlPathEqualTo(PayPalStubs.VERIFY_WEBHOOK_PATH)));
        assertThat(data.webhookEvents(eventId)).isZero();
    }

    @Test
    void verificationOutageReturns503SoPayPalRetries() throws Exception {
        PAYPAL.stubFor(post(urlPathEqualTo(PayPalStubs.VERIFY_WEBHOOK_PATH))
                .willReturn(aResponse().withStatus(500)));

        webhook(WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.COMPLETED", captureId, tipId.toString(), orderId))
                .andExpect(status().isServiceUnavailable());

        assertThat(data.webhookEvents(eventId)).isZero();
        assertThat(data.tipStatus(tipId)).isEqualTo("CREATED");
    }

    @Test
    void sameEventIdTwiceIsProcessedOnce() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        String body = WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.COMPLETED", captureId, tipId.toString(), orderId);

        webhook(body).andExpect(status().isOk());
        webhook(body).andExpect(status().isOk());

        assertThat(data.webhookEvents(eventId)).isEqualTo(1);
        assertThat(events.forTip(tipId)).hasSize(1);
    }

    @Test
    void pendingThenCompletedWebhooksCaptureTheTip() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");

        webhook(WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.PENDING", captureId, tipId.toString(), orderId))
                .andExpect(status().isOk());
        assertThat(data.tipStatus(tipId)).isEqualTo("PENDING");

        webhook(WebhookEvents.capture(
                        TestData.uniqueId("WH"), "PAYMENT.CAPTURE.COMPLETED", captureId, tipId.toString(), orderId))
                .andExpect(status().isOk());
        assertThat(data.tipStatus(tipId)).isEqualTo("CAPTURED");
        assertThat(events.forTip(tipId)).hasSize(1);
    }

    @Test
    void declinedWebhookFailsTheTipLikeDenied() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        UUID denied = data.tip(deviceId, "PENDING", TestData.uniqueId("ORDER"), Instant.now());

        webhook(WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.DECLINED", captureId, tipId.toString(), orderId))
                .andExpect(status().isOk());
        webhook(WebhookEvents.capture(
                        TestData.uniqueId("WH"),
                        "PAYMENT.CAPTURE.DENIED",
                        TestData.uniqueId("CAPTURE"),
                        denied.toString(),
                        null))
                .andExpect(status().isOk());

        assertThat(data.tipStatus(tipId)).isEqualTo("FAILED");
        assertThat(data.tipStatus(denied)).isEqualTo("FAILED");
        assertThat(events.forTip(tipId)).isEmpty();
    }

    @Test
    void reversedWebhookMovesCapturedToReversed() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        UUID captured = data.tip(deviceId, "CAPTURED", TestData.uniqueId("ORDER"), Instant.now());
        data.setCaptureId(captured, captureId);

        webhook(WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.REVERSED", captureId, captured.toString(), null))
                .andExpect(status().isOk());

        assertThat(data.tipStatus(captured)).isEqualTo("REVERSED");
    }

    @Test
    void reversalIsMatchedByCaptureIdWithoutCustomId() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        UUID captured = data.tip(deviceId, "CAPTURED", TestData.uniqueId("ORDER"), Instant.now());
        data.setCaptureId(captured, captureId);

        webhook(WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.REVERSED", captureId, null, null))
                .andExpect(status().isOk());

        assertThat(data.tipStatus(captured)).isEqualTo("REVERSED");
    }

    @Test
    void reversalDoesNotTouchATipThatWasNeverCaptured() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");

        webhook(WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.REVERSED", captureId, tipId.toString(), orderId))
                .andExpect(status().isOk());

        assertThat(data.tipStatus(tipId)).isEqualTo("CREATED");
    }

    @Test
    void refundedWebhookMovesCapturedToRefunded() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        UUID captured = data.tip(deviceId, "CAPTURED", TestData.uniqueId("ORDER"), Instant.now());
        data.setCaptureId(captured, captureId);

        webhook(WebhookEvents.refund(eventId, TestData.uniqueId("REFUND"), captureId, captured.toString()))
                .andExpect(status().isOk());

        assertThat(data.tipStatus(captured)).isEqualTo("REFUNDED");
    }

    @Test
    void refundIsMatchedThroughTheCaptureLinkWithoutCustomId() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        UUID captured = data.tip(deviceId, "CAPTURED", TestData.uniqueId("ORDER"), Instant.now());
        data.setCaptureId(captured, captureId);

        webhook(WebhookEvents.refund(eventId, TestData.uniqueId("REFUND"), captureId, null))
                .andExpect(status().isOk());

        assertThat(data.tipStatus(captured)).isEqualTo("REFUNDED");
    }

    @Test
    void refundDoesNotTouchATipThatWasNeverCaptured() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");

        webhook(WebhookEvents.refund(eventId, TestData.uniqueId("REFUND"), captureId, tipId.toString()))
                .andExpect(status().isOk());

        assertThat(data.tipStatus(tipId)).isEqualTo("CREATED");
    }

    @Test
    void otherEventTypesAreStoredAndAcknowledgedWithoutAction() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");

        webhook(WebhookEvents.capture(eventId, "CHECKOUT.ORDER.APPROVED", orderId, tipId.toString(), orderId))
                .andExpect(status().isOk());

        assertThat(data.webhookEvents(eventId)).isEqualTo(1);
        assertThat(data.tipStatus(tipId)).isEqualTo("CREATED");
    }

    @Test
    void processingFailureRollsBackTheEventRowAndReturns500() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        UUID other = data.tip(deviceId, "CAPTURED", TestData.uniqueId("ORDER"), Instant.now());
        data.setCaptureId(other, captureId);

        webhook(WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.COMPLETED", captureId, tipId.toString(), orderId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.trace").doesNotExist());

        assertThat(data.webhookEvents(eventId)).isZero();
        assertThat(data.tipStatus(tipId)).isEqualTo("CREATED");
        assertThat(events.forTip(tipId)).isEmpty();
    }

    private ResultActions webhook(String body) throws Exception {
        HttpHeaders headers = WebhookEvents.transmissionHeaders();
        return mvc.perform(MockMvcRequestBuilders.post("/webhooks/paypal")
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
