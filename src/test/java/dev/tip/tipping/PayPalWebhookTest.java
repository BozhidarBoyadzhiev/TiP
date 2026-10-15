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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    String clientIp;

    @BeforeEach
    void createdTip() {
        clientIp = TestData.uniquePublicIp();
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
    void anOrderSomeoneElseCreatedNeverCapturesTheTipItsCustomIdNames() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");

        webhook(WebhookEvents.capture(
                        eventId,
                        "PAYMENT.CAPTURE.COMPLETED",
                        captureId,
                        tipId.toString(),
                        TestData.uniqueId("THEIR-ORDER"),
                        "0.01",
                        "GBP"))
                .andExpect(status().isOk());

        assertThat(data.tipRow(tipId)).containsEntry("status", "CREATED").containsEntry("paypal_capture_id", null);
        assertThat(events.forTip(tipId)).isEmpty();
        assertThat(data.webhookEvents(eventId)).isEqualTo(1);
    }

    @Test
    void aCustomIdAloneMatchesNoTip() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");

        webhook(WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.COMPLETED", captureId, tipId.toString(), null))
                .andExpect(status().isOk());
        webhook(WebhookEvents.capture(
                        TestData.uniqueId("WH"), "PAYMENT.CAPTURE.DENIED", captureId, tipId.toString(), null))
                .andExpect(status().isOk());

        assertThat(data.tipStatus(tipId)).isEqualTo("CREATED");
        assertThat(events.forTip(tipId)).isEmpty();
    }

    @Test
    void aCaptureOfAnotherAmountLeavesTheTipUnchanged() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");

        webhook(WebhookEvents.capture(
                        eventId, "PAYMENT.CAPTURE.COMPLETED", captureId, tipId.toString(), orderId, "0.01", "GBP"))
                .andExpect(status().isOk());
        webhook(WebhookEvents.capture(
                        TestData.uniqueId("WH"),
                        "PAYMENT.CAPTURE.PENDING",
                        captureId,
                        tipId.toString(),
                        orderId,
                        "20.00",
                        "GBP"))
                .andExpect(status().isOk());

        assertThat(data.tipRow(tipId)).containsEntry("status", "CREATED").containsEntry("paypal_capture_id", null);
        assertThat(events.forTip(tipId)).isEmpty();
    }

    @Test
    void aCaptureInAnotherCurrencyOrWithoutAnAmountLeavesTheTipUnchanged() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");

        webhook(WebhookEvents.capture(
                        eventId, "PAYMENT.CAPTURE.COMPLETED", captureId, tipId.toString(), orderId, "2.00", "USD"))
                .andExpect(status().isOk());
        webhook(WebhookEvents.capture(
                        TestData.uniqueId("WH"),
                        "PAYMENT.CAPTURE.COMPLETED",
                        captureId,
                        tipId.toString(),
                        orderId,
                        "two",
                        "GBP"))
                .andExpect(status().isOk());

        assertThat(data.tipStatus(tipId)).isEqualTo("CREATED");
        assertThat(events.forTip(tipId)).isEmpty();
    }

    @Test
    void theAmountMayBeWrittenWithAnyScale() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");

        webhook(WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.COMPLETED", captureId, null, orderId, "2.0", "GBP"))
                .andExpect(status().isOk());

        assertThat(data.tipStatus(tipId)).isEqualTo("CAPTURED");
        assertThat(events.forTip(tipId)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABANDONED", "FAILED"})
    void aCaptureConfirmedAfterTheTipWasClosedIsStillRecorded(String closed) throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        String closedOrder = TestData.uniqueId("ORDER");
        UUID closedTip = data.tip(deviceId, closed, closedOrder, Instant.now());

        webhook(WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.COMPLETED", captureId, null, closedOrder))
                .andExpect(status().isOk());

        assertThat(data.tipRow(closedTip))
                .containsEntry("status", "CAPTURED")
                .containsEntry("paypal_capture_id", captureId);
        assertThat(events.forTip(closedTip)).hasSize(1);
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
        webhook(
                        WebhookEvents.capture(
                                eventId, "PAYMENT.CAPTURE.COMPLETED", captureId, tipId.toString(), orderId),
                        new HttpHeaders())
                .andExpect(status().isBadRequest());

        PAYPAL.verify(0, postRequestedFor(urlPathEqualTo(PayPalStubs.VERIFY_WEBHOOK_PATH)));
        assertThat(data.webhookEvents(eventId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://attacker.example/v1/notifications/certs/CERT-1",
                "https://api.sandbox.paypal.com.attacker.example/certs/CERT-1",
                "https://attacker.example#.paypal.com",
                "https://user@attacker.example/.paypal.com",
                "http://api.sandbox.paypal.com/v1/notifications/certs/CERT-1",
                "https://api.sandbox.paypal.com:8443/v1/notifications/certs/CERT-1",
                "notaurl paypal.com"
            })
    void aCertificateUrlOffPayPalIsRejectedWithoutAskingPayPal(String certUrl) throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        HttpHeaders headers = WebhookEvents.transmissionHeaders();
        headers.set("PAYPAL-CERT-URL", certUrl);

        webhook(WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.COMPLETED", captureId, null, orderId), headers)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEBHOOK_NOT_VERIFIED"));

        PAYPAL.verify(0, postRequestedFor(urlPathEqualTo(PayPalStubs.VERIFY_WEBHOOK_PATH)));
        assertThat(data.tipStatus(tipId)).isEqualTo("CREATED");
    }

    @Test
    void aClientWhoseWebhooksPayPalKeepsRejectingIsLimitedBeforeAnotherCall() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "FAILURE");
        String body = WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.COMPLETED", captureId, null, orderId);
        for (int i = 1; i <= WebhookFailureLimiter.REJECTED_PER_MINUTE; i++) {
            webhook(body).andExpect(status().isBadRequest());
        }

        webhook(body)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        PAYPAL.verify(
                WebhookFailureLimiter.REJECTED_PER_MINUTE,
                postRequestedFor(urlPathEqualTo(PayPalStubs.VERIFY_WEBHOOK_PATH)));
        clientIp = TestData.uniquePublicIp();
        webhook(body).andExpect(status().isBadRequest());
    }

    @Test
    void webhooksPayPalVerifiesAreNeverLimited() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        for (int i = 0; i < WebhookFailureLimiter.REJECTED_PER_MINUTE * 2; i++) {
            webhook(WebhookEvents.capture(TestData.uniqueId("WH"), "CHECKOUT.ORDER.APPROVED", orderId, null, orderId))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void aVerificationOutageDoesNotCountAsARejection() throws Exception {
        PAYPAL.stubFor(post(urlPathEqualTo(PayPalStubs.VERIFY_WEBHOOK_PATH))
                .willReturn(aResponse().withStatus(503)));
        String body = WebhookEvents.capture(eventId, "PAYMENT.CAPTURE.COMPLETED", captureId, null, orderId);
        for (int i = 0; i <= WebhookFailureLimiter.REJECTED_PER_MINUTE; i++) {
            webhook(body).andExpect(status().isServiceUnavailable());
        }
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
    void deniedAndDeclinedWebhooksFailAPendingTip() throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        String deniedOrder = TestData.uniqueId("ORDER");
        UUID denied = data.tip(deviceId, "PENDING", deniedOrder, Instant.now());
        String declinedOrder = TestData.uniqueId("ORDER");
        UUID declined = data.tip(deviceId, "PENDING", declinedOrder, Instant.now());

        webhook(WebhookEvents.capture(
                        eventId, "PAYMENT.CAPTURE.DENIED", TestData.uniqueId("CAPTURE"), null, deniedOrder))
                .andExpect(status().isOk());
        webhook(WebhookEvents.capture(
                        TestData.uniqueId("WH"),
                        "PAYMENT.CAPTURE.DECLINED",
                        TestData.uniqueId("CAPTURE"),
                        null,
                        declinedOrder))
                .andExpect(status().isOk());

        assertThat(data.tipStatus(denied)).isEqualTo("FAILED");
        assertThat(data.tipStatus(declined)).isEqualTo("FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAYMENT.CAPTURE.DECLINED", "PAYMENT.CAPTURE.DENIED"})
    void aDeclineOfACreatedTipLeavesItOpenForTheTippersRetry(String eventType) throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        PayPalStubs.captureCompleted(PAYPAL, orderId, captureId);

        webhook(WebhookEvents.capture(eventId, eventType, TestData.uniqueId("CAPTURE"), null, orderId))
                .andExpect(status().isOk());

        assertThat(data.tipStatus(tipId)).isEqualTo("CREATED");
        assertThat(data.webhookEvents(eventId)).isEqualTo(1);
        mvc.perform(MockMvcRequestBuilders.post("/api/public/tips/" + tipId + "/capture")
                        .with(TestData.newClient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        assertThat(events.forTip(tipId)).hasSize(1);
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
        return webhook(body, WebhookEvents.transmissionHeaders());
    }

    private ResultActions webhook(String body, HttpHeaders headers) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/webhooks/paypal")
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                }));
    }
}
