package dev.tip.tipping;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.CapturedEvents;
import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.TestData;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Tips created long before the rest of the suite's, so they are the oldest the jobs see and fall in the first batch
 * whatever other tests left behind.
 */
class AbandonedTipsJobTest extends IntegrationTest {

    private static final Instant LONG_AGO = Instant.parse("2001-01-01T00:00:00Z");

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    AbandonedTipsJob abandonedJob;

    @Autowired
    PendingTipsJob pendingJob;

    @Autowired
    TestData data;

    @Autowired
    CapturedEvents events;

    UUID deviceId;

    @BeforeEach
    void pinTheClock() {
        clock.pin(now);
        deviceId = data.device();
    }

    @Test
    void createdTipsOlderThanSixHoursWhoseCaptureWasNeverAttemptedAreAbandonedWithoutALookup() {
        String oldOrder = TestData.uniqueId("ORDER");
        UUID old = data.tip(
                deviceId, "CREATED", oldOrder, now.minus(Duration.ofHours(6)).minusSeconds(1));
        UUID recent = data.tip(deviceId, "CREATED", null, now.minus(Duration.ofHours(5)));
        UUID oldCaptured = data.tip(deviceId, "CAPTURED", null, now.minus(Duration.ofDays(2)));
        UUID oldPending = data.tip(deviceId, "PENDING", null, now.minus(Duration.ofDays(2)));

        abandonedJob.abandonStaleTips();

        assertThat(data.tipStatus(old)).isEqualTo("ABANDONED");
        assertThat(data.tipStatus(recent)).isEqualTo("CREATED");
        assertThat(data.tipStatus(oldCaptured)).isEqualTo("CAPTURED");
        assertThat(data.tipStatus(oldPending)).isEqualTo("PENDING");
        PAYPAL.verify(0, getRequestedFor(urlPathEqualTo("/v2/checkout/orders/" + oldOrder)));
    }

    @Test
    void anAttemptedTipIsLookedUpBeforeItIsAbandoned() {
        String capturedOrder = TestData.uniqueId("ORDER");
        UUID captured = attempted("CREATED", capturedOrder, 0);
        String captureId = TestData.uniqueId("CAPTURE");
        PayPalStubs.getOrder(PAYPAL, capturedOrder, captureId, "COMPLETED");
        String pendingOrder = TestData.uniqueId("ORDER");
        UUID pending = attempted("CREATED", pendingOrder, 1);
        PayPalStubs.getOrder(PAYPAL, pendingOrder, TestData.uniqueId("CAPTURE"), "PENDING");
        String unknownOrder = TestData.uniqueId("ORDER");
        UUID unknown = attempted("CREATED", unknownOrder, 2);
        PayPalStubs.getOrderError(PAYPAL, unknownOrder, 404, "RESOURCE_NOT_FOUND", "INVALID_RESOURCE_ID");
        String otherAmountOrder = TestData.uniqueId("ORDER");
        UUID otherAmount = attempted("CREATED", otherAmountOrder, 3);
        PayPalStubs.getOrder(PAYPAL, otherAmountOrder, TestData.uniqueId("CAPTURE"), "COMPLETED", "0.01", "GBP");

        abandonedJob.abandonStaleTips();

        assertThat(data.tipRow(captured))
                .containsEntry("status", "CAPTURED")
                .containsEntry("paypal_capture_id", captureId);
        assertThat(events.forTip(captured)).hasSize(1);
        assertThat(data.tipStatus(pending)).isEqualTo("PENDING");
        assertThat(data.tipStatus(unknown)).isEqualTo("ABANDONED");
        assertThat(data.tipStatus(otherAmount)).isEqualTo("ABANDONED");
        assertThat(events.forTip(otherAmount)).isEmpty();
    }

    @Test
    void pendingTipsOlderThanAnHourAreSettledFromPayPal() {
        String completedOrder = TestData.uniqueId("ORDER");
        UUID completed = attempted("PENDING", completedOrder, 0);
        String captureId = TestData.uniqueId("CAPTURE");
        PayPalStubs.getOrder(PAYPAL, completedOrder, captureId, "COMPLETED");
        String declinedOrder = TestData.uniqueId("ORDER");
        UUID declined = attempted("PENDING", declinedOrder, 1);
        PayPalStubs.getOrder(PAYPAL, declinedOrder, TestData.uniqueId("CAPTURE"), "DECLINED");
        String stillPendingOrder = TestData.uniqueId("ORDER");
        UUID stillPending = attempted("PENDING", stillPendingOrder, 2);
        PayPalStubs.getOrder(PAYPAL, stillPendingOrder, TestData.uniqueId("CAPTURE"), "PENDING");
        String outageOrder = TestData.uniqueId("ORDER");
        UUID outage = attempted("PENDING", outageOrder, 3);
        PayPalStubs.getOrderError(PAYPAL, outageOrder, 500, "INTERNAL_SERVER_ERROR", "INTERNAL_SERVICE_ERROR");
        String recentOrder = TestData.uniqueId("ORDER");
        UUID recent = data.tip(deviceId, "PENDING", recentOrder, now.minus(Duration.ofMinutes(59)));

        pendingJob.reconcile();

        assertThat(data.tipRow(completed))
                .containsEntry("status", "CAPTURED")
                .containsEntry("paypal_capture_id", captureId);
        assertThat(events.forTip(completed)).hasSize(1);
        assertThat(data.tipStatus(declined)).isEqualTo("FAILED");
        assertThat(data.tipStatus(stillPending)).isEqualTo("PENDING");
        assertThat(data.tipStatus(outage)).isEqualTo("PENDING");
        assertThat(data.tipStatus(recent)).isEqualTo("PENDING");
        PAYPAL.verify(0, getRequestedFor(urlPathEqualTo("/v2/checkout/orders/" + recentOrder)));
    }

    /** A tip of 2.00 GBP whose capture was attempted, created long ago, {@code offset} seconds after the others. */
    private UUID attempted(String status, String orderId, int offset) {
        return data.tip(
                deviceId,
                status,
                LONG_AGO.plusSeconds(offset),
                Map.of("capture_attempted_at", now, "paypal_order_id", orderId));
    }
}
