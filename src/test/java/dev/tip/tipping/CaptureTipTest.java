package dev.tip.tipping;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.CapturedEvents;
import dev.tip.HeldResponses;
import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.TestData;
import dev.tip.WebhookEvents;
import dev.tip.tipping.api.TipCaptured;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class CaptureTipTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestData data;

    @Autowired
    CapturedEvents events;

    @Autowired
    JdbcClient jdbc;

    UUID deviceId;
    String orderId;
    String captureId;
    UUID tipId;

    @BeforeEach
    void createdTip() {
        deviceId = data.device();
        orderId = TestData.uniqueId("ORDER");
        captureId = TestData.uniqueId("CAPTURE");
        tipId = data.tip(deviceId, "CREATED", orderId, Instant.now());
    }

    @Test
    void completedCaptureMovesTipToCapturedAndPublishesOneEvent() throws Exception {
        PayPalStubs.captureCompleted(PAYPAL, orderId, captureId);

        capture(tipId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));

        assertThat(data.tipRow(tipId))
                .containsEntry("status", "CAPTURED")
                .containsEntry("paypal_capture_id", captureId);
        assertThat(data.tipRow(tipId).get("captured_at")).isNotNull();
        List<TipCaptured> published = events.forTip(tipId);
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().deviceId()).isEqualTo(deviceId);
    }

    @Test
    void pendingCaptureMovesTipToPendingWithoutEvent() throws Exception {
        PayPalStubs.capturePending(PAYPAL, orderId, captureId);

        capture(tipId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(data.tipRow(tipId)).containsEntry("status", "PENDING").containsEntry("paypal_capture_id", captureId);
        assertThat(events.forTip(tipId)).isEmpty();
    }

    @Test
    void declinedInstrumentReturns422AndTipStaysCreated() throws Exception {
        PayPalStubs.captureError(PAYPAL, orderId, 422, "UNPROCESSABLE_ENTITY", "INSTRUMENT_DECLINED");

        capture(tipId)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSTRUMENT_DECLINED"));

        assertThat(data.tipStatus(tipId)).isEqualTo("CREATED");
        assertThat(events.forTip(tipId)).isEmpty();
    }

    @Test
    void capturingTwiceCallsPayPalOnceAndPublishesOneEvent() throws Exception {
        PayPalStubs.captureCompleted(PAYPAL, orderId, captureId);

        capture(tipId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
        capture(tipId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));

        PAYPAL.verify(1, postRequestedFor(urlPathEqualTo(PayPalStubs.capturePath(orderId))));
        assertThat(events.forTip(tipId)).hasSize(1);
    }

    @Test
    void alreadyCapturedOrderIsReconciledFromPayPal() throws Exception {
        PayPalStubs.captureError(PAYPAL, orderId, 422, "UNPROCESSABLE_ENTITY", "ORDER_ALREADY_CAPTURED");
        PayPalStubs.getOrder(PAYPAL, orderId, captureId, "COMPLETED");

        capture(tipId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));

        PAYPAL.verify(1, getRequestedFor(urlPathEqualTo("/v2/checkout/orders/" + orderId)));
        assertThat(data.tipRow(tipId)).containsEntry("paypal_capture_id", captureId);
        assertThat(events.forTip(tipId)).hasSize(1);
    }

    @Test
    void aCaptureOfAnotherAmountOrCurrencyLeavesTheTipUnchanged() throws Exception {
        PayPalStubs.captureCompleted(PAYPAL, orderId, captureId, "0.01", "GBP");

        capture(tipId)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYPAL_CAPTURE_FAILED"));

        PayPalStubs.capturePending(PAYPAL, orderId, captureId, "2.00", "EUR");
        capture(tipId)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYPAL_CAPTURE_FAILED"));

        assertThat(data.tipRow(tipId)).containsEntry("status", "CREATED").containsEntry("paypal_capture_id", null);
        assertThat(events.forTip(tipId)).isEmpty();
    }

    @Test
    void anAlreadyCapturedOrderOfAnotherAmountLeavesTheTipUnchanged() throws Exception {
        PayPalStubs.captureError(PAYPAL, orderId, 422, "UNPROCESSABLE_ENTITY", "ORDER_ALREADY_CAPTURED");
        PayPalStubs.getOrder(PAYPAL, orderId, captureId, "COMPLETED", "200.00", "GBP");

        capture(tipId)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYPAL_CAPTURE_FAILED"));

        assertThat(data.tipStatus(tipId)).isEqualTo("CREATED");
        assertThat(events.forTip(tipId)).isEmpty();
    }

    @Test
    void theCapturedAmountMayBeWrittenWithAnyScale() throws Exception {
        PayPalStubs.captureCompleted(PAYPAL, orderId, captureId, "2", "GBP");

        capture(tipId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    void payPalOutageReturns502AndKeepsTipCapturable() throws Exception {
        PayPalStubs.captureError(PAYPAL, orderId, 500, "INTERNAL_SERVER_ERROR", "INTERNAL_SERVICE_ERROR");

        capture(tipId)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYPAL_CAPTURE_FAILED"));

        assertThat(data.tipStatus(tipId)).isEqualTo("CREATED");
        assertThat(data.tipRow(tipId).get("capture_attempted_at"))
                .as("the abandon job looks such a tip up before abandoning it")
                .isNotNull();
    }

    @Test
    void retryingKeepsTheTimeOfTheFirstAttempt() throws Exception {
        PayPalStubs.captureError(PAYPAL, orderId, 500, "INTERNAL_SERVER_ERROR", "INTERNAL_SERVICE_ERROR");
        Instant first = Instant.parse("2026-11-01T10:00:00Z");
        clock.pin(first);
        capture(tipId).andExpect(status().isBadGateway());
        clock.pin(first.plus(Duration.ofMinutes(5)));
        capture(tipId).andExpect(status().isBadGateway());

        assertThat(jdbc.sql("SELECT capture_attempted_at FROM tip WHERE id = ?")
                        .param(tipId)
                        .query(OffsetDateTime.class)
                        .single()
                        .toInstant())
                .isEqualTo(first);
    }

    @Test
    void unknownOrderAtPayPalFailsTheTip() throws Exception {
        PayPalStubs.captureError(PAYPAL, orderId, 404, "RESOURCE_NOT_FOUND", "INVALID_RESOURCE_ID");

        capture(tipId)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYPAL_ORDER_NOT_FOUND"));

        assertThat(data.tipStatus(tipId)).isEqualTo("FAILED");
    }

    /**
     * The webhook may record the capture while our capture call waits; a late "order not found" never fails it, and
     * the tipper is answered as for a capture.
     */
    @Test
    void anOrderNotFoundAnswerNeverFailsATipTheWebhookCaptured() throws Exception {
        MockHttpServletResponse answer = failedCallWhileTheWebhookLands(
                404, "RESOURCE_NOT_FOUND", "INVALID_RESOURCE_ID", "PAYMENT.CAPTURE.COMPLETED");

        assertCapturedAnswer(answer);
        assertThat(data.tipRow(tipId))
                .containsEntry("status", "CAPTURED")
                .containsEntry("paypal_capture_id", captureId);
        assertThat(events.forTip(tipId)).hasSize(1);
    }

    @Test
    void aServerErrorWhileTheWebhookCapturesTheTipAnswersLikeACapture() throws Exception {
        MockHttpServletResponse answer = failedCallWhileTheWebhookLands(
                500, "INTERNAL_SERVER_ERROR", "INTERNAL_SERVICE_ERROR", "PAYMENT.CAPTURE.COMPLETED");

        assertCapturedAnswer(answer);
        assertThat(data.tipStatus(tipId)).isEqualTo("CAPTURED");
        assertThat(events.forTip(tipId)).hasSize(1);
    }

    @Test
    void aServerErrorWhileTheWebhookPendsTheTipAnswersLikeAPendingCapture() throws Exception {
        MockHttpServletResponse answer = failedCallWhileTheWebhookLands(
                500, "INTERNAL_SERVER_ERROR", "INTERNAL_SERVICE_ERROR", "PAYMENT.CAPTURE.PENDING");

        assertThat(answer.getStatus()).isEqualTo(200);
        assertThat(answer.getContentAsString()).isEqualTo("{\"status\":\"PENDING\"}");
        assertThat(data.tipStatus(tipId)).isEqualTo("PENDING");
        assertThat(events.forTip(tipId)).isEmpty();
    }

    /** Holds PayPal's failed capture answer, applies the webhook meanwhile, then lets the answer go. */
    private MockHttpServletResponse failedCallWhileTheWebhookLands(
            int status, String name, String issue, String eventType) throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        HeldResponses.Hold hold = HeldResponses.hold();
        PayPalStubs.captureErrorHeld(PAYPAL, orderId, status, name, issue);

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> answer = pool.submit(() -> capture(tipId).andReturn());
            hold.awaitArrived();
            mvc.perform(post("/webhooks/paypal")
                            .headers(WebhookEvents.transmissionHeaders())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    WebhookEvents.capture(TestData.uniqueId("WH"), eventType, captureId, null, orderId))
                            .with(TestData.newClient()))
                    .andExpect(status().isOk());
            hold.release();
            return answer.get(30, TimeUnit.SECONDS).getResponse();
        }
    }

    /** Exactly what a successful capture answers. */
    private static void assertCapturedAnswer(MockHttpServletResponse answer) throws Exception {
        assertThat(answer.getStatus()).isEqualTo(200);
        assertThat(answer.getContentAsString()).isEqualTo("{\"status\":\"CAPTURED\"}");
    }

    @Test
    void finishedTipReturnsItsStatusWithoutCallingPayPal() throws Exception {
        UUID refunded = data.tip(deviceId, "REFUNDED", TestData.uniqueId("ORDER"), Instant.now());

        capture(refunded)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        PAYPAL.verify(0, postRequestedFor(urlPathEqualTo(PayPalStubs.capturePath(orderId))));
    }

    @Test
    void failedAndAbandonedTipsAreClosedWith409WithoutCallingPayPal() throws Exception {
        UUID failed = data.tip(deviceId, "FAILED", TestData.uniqueId("ORDER"), Instant.now());
        UUID abandoned = data.tip(deviceId, "ABANDONED", TestData.uniqueId("ORDER"), Instant.now());

        capture(failed)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("TIP_CLOSED"));
        capture(abandoned)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TIP_CLOSED"));

        assertThat(PAYPAL.findAll(postRequestedFor(urlPathMatching("/v2/checkout/orders/.*/capture"))))
                .isEmpty();
    }

    @Test
    void pendingAndCapturedTipsKeepReturningTheirStatus() throws Exception {
        UUID captured = data.tip(deviceId, "CAPTURED", TestData.uniqueId("ORDER"), Instant.now());
        String pendingOrder = TestData.uniqueId("ORDER");
        UUID pending = data.tip(deviceId, "PENDING", pendingOrder, Instant.now());
        PayPalStubs.capturePending(PAYPAL, pendingOrder, captureId);

        capture(captured)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        capture(pending)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void unknownTipIs404() throws Exception {
        capture(UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TIP_NOT_FOUND"));
    }

    @Test
    void malformedTipIdIs400() throws Exception {
        mvc.perform(post("/api/public/tips/not-a-uuid/capture").with(TestData.newClient()))
                .andExpect(status().isBadRequest());
    }

    private ResultActions capture(UUID id) throws Exception {
        return mvc.perform(post("/api/public/tips/{tipId}/capture", id).with(TestData.newClient()));
    }
}
