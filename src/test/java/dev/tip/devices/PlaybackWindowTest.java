package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.AnthropicStubs;
import dev.tip.HeldResponses;
import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.ReactedEvents;
import dev.tip.TestData;
import dev.tip.WebhookEvents;
import dev.tip.ai.api.TipReacted;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A tip plays on the device only when its capture landed within 10 minutes of TiP's first capture attempt. A later
 * capture (a late webhook, a settled pending tip, a revived failed or abandoned tip) and one TiP never attempted are
 * recorded and classified, but neither the device nor an observer plays them. A webhook that lands while the capture
 * call is still out plays as usual.
 */
class PlaybackWindowTest extends IntegrationTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);

    @LocalServerPort
    int port;

    @Autowired
    TestData data;

    @Autowired
    MockMvc mvc;

    @Autowired
    ReactedEvents reacted;

    @Autowired
    ObserverSessions observers;

    @Autowired
    JdbcClient jdbc;

    @Test
    void onlyCapturesInsideTheWindowPlay() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock.pin(now);
        String slug = TestData.uniqueSlug();
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(slug, "Window Panda", secret);
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");

        TestTip failed = tip(deviceId, "FAILED", now.minus(Duration.ofHours(6)));
        TestTip abandoned = tip(deviceId, "ABANDONED", now.minus(Duration.ofHours(6)));
        TestTip pendingTooLate = tip(deviceId, "PENDING", now.minus(WINDOW).minus(1, ChronoUnit.MICROS));
        TestTip createdTooLate = tip(deviceId, "CREATED", now.minus(WINDOW).minus(1, ChronoUnit.MICROS));
        TestTip neverAttempted = tip(deviceId, "CREATED", null);
        TestTip onTheEdge = tip(deviceId, "CREATED", now.minus(WINDOW));
        TestTip fresh = tip(deviceId, "CREATED", now);
        AnthropicStubs.replyFor(ANTHROPIC, fresh.message(), AnthropicStubs.classification("PLAYFUL", false, null));
        List<TestTip> silent = List.of(failed, abandoned, pendingTooLate, createdTooLate, neverAttempted);

        try (TestSocket device = TestSocket.connectDevice(port, deviceId, secret);
                TestSocket observer = TestSocket.connectObserver(port, slug, TestData.uniquePublicIp())) {
            assertThat(device.nextJson()).containsEntry("type", "hello");
            await().atMost(Duration.ofSeconds(5)).until(() -> observers.isWatched(slug));

            for (TestTip tip : silent) {
                completed(tip.orderId());
            }
            for (TestTip tip : silent) {
                await().atMost(Duration.ofSeconds(30))
                        .until(() -> !reacted.forTip(tip.id()).isEmpty());
            }
            completed(onTheEdge.orderId());
            assertThat(device.nextJson())
                    .containsEntry("type", "reaction")
                    .containsEntry("id", onTheEdge.id().toString());
            assertThat(observer.nextJson()).containsEntry("reaction", "PLAIN");
            completed(fresh.orderId());
            assertThat(device.nextJson())
                    .containsEntry("type", "reaction")
                    .containsEntry("id", fresh.id().toString());
            assertThat(observer.nextJson()).containsEntry("reaction", "PLAYFUL");
            device.sync();
            observer.sync();
            assertThat(device.pending()).isZero();
            assertThat(observer.pending()).isZero();
        }

        for (TestTip tip : silent) {
            assertThat(data.tipRow(tip.id()))
                    .containsEntry("status", "CAPTURED")
                    .containsEntry("ai_status", "DONE");
            assertThat(reacted.forTip(tip.id()))
                    .singleElement()
                    .extracting(TipReacted::playOnDevice)
                    .isEqualTo(false);
            assertThat(deliveries(tip.id())).isZero();
        }
        assertThat(data.tipRow(failed.id())).containsEntry("revived", true);
        assertThat(data.tipRow(abandoned.id())).containsEntry("revived", true);
        assertThat(data.tipRow(pendingTooLate.id())).containsEntry("revived", false);
        assertThat(data.tipRow(fresh.id())).containsEntry("revived", false);
        assertThat(deliveries(onTheEdge.id())).isOne();
        assertThat(deliveries(fresh.id())).isOne();
    }

    /**
     * The capture attempt is committed before the capture call goes out, so a COMPLETED webhook that PayPal sends
     * while our call still waits for its answer finds it, and the tip plays once. The only test that catches
     * capture_attempted_at being written after the PayPal call (Brief 10, C1): never remove it.
     */
    @Test
    void aWebhookAppliedWhileTheCaptureCallIsHeldStillPlaysOnce() throws Exception {
        String slug = TestData.uniqueSlug();
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(slug, "Held Panda", secret);
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        TestTip tip = tip(deviceId, "CREATED", null);
        String captureId = TestData.uniqueId("CAPTURE");
        HeldResponses.Hold hold = HeldResponses.hold();
        PayPalStubs.captureCompletedHeld(PAYPAL, tip.orderId(), captureId);

        try (TestSocket device = TestSocket.connectDevice(port, deviceId, secret);
                ExecutorService pool = Executors.newSingleThreadExecutor()) {
            assertThat(device.nextJson()).containsEntry("type", "hello");
            Future<MvcResult> capture = pool.submit(() -> mvc.perform(
                            post("/api/public/tips/{tipId}/capture", tip.id()).with(TestData.newClient()))
                    .andReturn());
            hold.awaitArrived();

            assertThat(data.tipRow(tip.id()).get("capture_attempted_at"))
                    .as("committed before the capture call went out")
                    .isNotNull();
            completed(tip.orderId(), captureId);
            assertThat(data.tipStatus(tip.id())).isEqualTo("CAPTURED");
            assertThat(device.nextJson())
                    .containsEntry("type", "reaction")
                    .containsEntry("id", tip.id().toString());

            hold.release();
            MvcResult answer = capture.get(30, TimeUnit.SECONDS);
            assertThat(answer.getResponse().getStatus()).isEqualTo(200);
            assertThat(answer.getResponse().getContentAsString()).contains("\"CAPTURED\"");
            device.sync();
            assertThat(device.pending()).isZero();
        }

        assertThat(reacted.forTip(tip.id()))
                .singleElement()
                .extracting(TipReacted::playOnDevice)
                .isEqualTo(true);
        assertThat(deliveries(tip.id())).isOne();
    }

    private TestTip tip(UUID deviceId, String status, Instant captureAttemptedAt) {
        String message = TestData.uniqueMessage("Thanks for the song");
        String orderId = TestData.uniqueId("ORDER");
        Map<String, Object> columns = new HashMap<>(Map.of("message", message, "paypal_order_id", orderId));
        if (captureAttemptedAt != null) {
            columns.put("capture_attempted_at", captureAttemptedAt);
        }
        return new TestTip(data.tip(deviceId, status, Instant.now(), columns), orderId, message);
    }

    private void completed(String orderId) throws Exception {
        completed(orderId, TestData.uniqueId("CAPTURE"));
    }

    private void completed(String orderId, String captureId) throws Exception {
        mvc.perform(post("/webhooks/paypal")
                        .headers(WebhookEvents.transmissionHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WebhookEvents.capture(
                                TestData.uniqueId("WH"), "PAYMENT.CAPTURE.COMPLETED", captureId, null, orderId))
                        .with(TestData.newClient()))
                .andExpect(status().isOk());
    }

    private int deliveries(UUID tipId) {
        return jdbc.sql("SELECT count(*) FROM reaction_delivery WHERE tip_id = ?")
                .param(tipId)
                .query(Integer.class)
                .single();
    }

    private record TestTip(UUID id, String orderId, String message) {}
}
