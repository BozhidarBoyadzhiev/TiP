package dev.tip.tipping;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.CapturedEvents;
import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.TestData;
import dev.tip.WebhookEvents;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The capture response and the PAYMENT.CAPTURE.COMPLETED webhook arrive together over real HTTP. The
 * conditional update in PostgreSQL must leave exactly one winner every time. Random PayPal stub delays
 * let either path reach the database first.
 */
class CaptureWebhookRaceTest extends IntegrationTest {

    private static final int ROUNDS = 20;
    private static final int MAX_JITTER_MILLIS = 40;

    @LocalServerPort
    int port;

    @Autowired
    TestData data;

    @Autowired
    CapturedEvents events;

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void captureAndWebhookTogetherPublishExactlyOneEvent() throws Exception {
        PayPalStubs.verifyWebhookWithJitter(PAYPAL, MAX_JITTER_MILLIS);
        UUID deviceId = data.device();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < ROUNDS; round++) {
                String orderId = TestData.uniqueId("ORDER");
                String captureId = TestData.uniqueId("CAPTURE");
                UUID tipId = data.tip(deviceId, "CREATED", orderId, Instant.now());
                PayPalStubs.captureCompletedWithJitter(PAYPAL, orderId, captureId, MAX_JITTER_MILLIS);
                String webhookBody = WebhookEvents.capture(
                        TestData.uniqueId("WH"), "PAYMENT.CAPTURE.COMPLETED", captureId, tipId.toString(), orderId);

                CountDownLatch start = new CountDownLatch(1);
                Future<HttpResponse<String>> capture = pool.submit(() -> afterLatch(start, captureRequest(tipId)));
                Future<HttpResponse<String>> webhook =
                        pool.submit(() -> afterLatch(start, webhookRequest(webhookBody)));
                start.countDown();

                assertThat(capture.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                assertThat(capture.get().body()).contains("\"CAPTURED\"");
                assertThat(webhook.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                assertThat(events.forTip(tipId)).as("events in round %d", round).hasSize(1);
                assertThat(data.tipRow(tipId))
                        .containsEntry("status", "CAPTURED")
                        .containsEntry("paypal_capture_id", captureId);
            }
        }
    }

    private HttpResponse<String> afterLatch(CountDownLatch start, HttpRequest request) throws Exception {
        start.await();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest captureRequest(UUID tipId) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/public/tips/" + tipId + "/capture"))
                .header("X-Forwarded-For", TestData.uniquePublicIp())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
    }

    private HttpRequest webhookRequest(String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/webhooks/paypal"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        WebhookEvents.transmissionHeaders().forEach((name, values) -> values.forEach(v -> builder.header(name, v)));
        return builder.build();
    }
}
