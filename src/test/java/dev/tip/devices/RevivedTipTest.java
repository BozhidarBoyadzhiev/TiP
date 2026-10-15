package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.AnthropicStubs;
import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.ReactedEvents;
import dev.tip.TestData;
import dev.tip.WebhookEvents;
import dev.tip.ai.api.TipReacted;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A tip PayPal confirms after it was marked failed or abandoned is recorded and classified, but neither the device
 * nor an observer plays it: whoever tipped left hours ago.
 */
class RevivedTipTest extends IntegrationTest {

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

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "ABANDONED"})
    void aRevivedTipIsClassifiedButNeverPlayed(String closed) throws Exception {
        String slug = TestData.uniqueSlug();
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(slug, "Revived Panda", secret);
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        String lateMessage = TestData.uniqueMessage("Thanks for the song");
        String lateOrder = TestData.uniqueId("ORDER");
        UUID late =
                data.tip(deviceId, closed, Instant.now(), Map.of("message", lateMessage, "paypal_order_id", lateOrder));
        AnthropicStubs.replyFor(ANTHROPIC, lateMessage, AnthropicStubs.classification("WARM", false, null));
        String freshMessage = TestData.uniqueMessage("Lovely playing");
        String freshOrder = TestData.uniqueId("ORDER");
        UUID fresh = data.tip(
                deviceId, "CREATED", Instant.now(), Map.of("message", freshMessage, "paypal_order_id", freshOrder));
        AnthropicStubs.replyFor(ANTHROPIC, freshMessage, AnthropicStubs.classification("PLAYFUL", false, null));

        try (TestSocket device = TestSocket.connectDevice(port, deviceId, secret);
                TestSocket observer = TestSocket.connectObserver(port, slug, TestData.uniquePublicIp())) {
            assertThat(device.nextJson()).containsEntry("type", "hello");
            await().atMost(Duration.ofSeconds(5)).until(() -> observers.isWatched(slug));

            completed(lateOrder);
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> !reacted.forTip(late).isEmpty());
            completed(freshOrder);

            assertThat(device.nextJson()).containsEntry("type", "reaction").containsEntry("id", fresh.toString());
            assertThat(observer.nextJson()).containsEntry("reaction", "PLAYFUL");
            device.sync();
            observer.sync();
            assertThat(device.pending()).isZero();
            assertThat(observer.pending()).isZero();
        }

        assertThat(data.tipRow(late))
                .containsEntry("status", "CAPTURED")
                .containsEntry("revived", true)
                .containsEntry("ai_status", "DONE")
                .containsEntry("reaction", "WARM");
        assertThat(reacted.forTip(late))
                .singleElement()
                .extracting(TipReacted::playOnDevice)
                .isEqualTo(false);
        assertThat(deliveries(late)).isZero();
        assertThat(data.tipRow(fresh)).containsEntry("revived", false);
        assertThat(deliveries(fresh)).isOne();
    }

    private void completed(String orderId) throws Exception {
        mvc.perform(post("/webhooks/paypal")
                        .headers(WebhookEvents.transmissionHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WebhookEvents.capture(
                                TestData.uniqueId("WH"),
                                "PAYMENT.CAPTURE.COMPLETED",
                                TestData.uniqueId("CAPTURE"),
                                null,
                                orderId))
                        .with(TestData.newClient()))
                .andExpect(status().isOk());
    }

    private int deliveries(UUID tipId) {
        return jdbc.sql("SELECT count(*) FROM reaction_delivery WHERE tip_id = ?")
                .param(tipId)
                .query(Integer.class)
                .single();
    }
}
