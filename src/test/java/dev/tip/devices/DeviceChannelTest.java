package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class DeviceChannelTest extends IntegrationTest {

    private static final AtomicInteger IPS = new AtomicInteger();
    private static final Instant NOW = Instant.parse("2026-10-13T14:00:00Z");

    @LocalServerPort
    int port;

    @Autowired
    TestData data;

    @Autowired
    DeviceSessions sessions;

    /** A client IP of its own, through the forwarded header the app trusts, so rate limits do not leak between tests. */
    static String uniqueIp() {
        int n = IPS.incrementAndGet();
        return "10.3." + (n / 250) + "." + (n % 250 + 1);
    }

    @Test
    void correctSecretConnectsAndReceivesHello() throws Exception {
        clock.pin(NOW);
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(TestData.uniqueSlug(), "Hello Panda", secret);

        try (TestSocket device = TestSocket.connectDevice(port, deviceId, secret)) {
            assertThat(device.nextJson())
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of("type", "hello", "deviceName", "Hello Panda", "serverTime", "2026-10-13T14:00:00Z"));
        }
    }

    @Test
    void rejectedHandshakesAllLookTheSame() throws Exception {
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(TestData.uniqueSlug(), "Panda", secret);

        List<RawWebSocket> rejected = List.of(
                handshake("/ws/device/" + deviceId, "Bearer " + TestData.randomSecret()),
                handshake("/ws/device/" + UUID.randomUUID(), "Bearer " + secret),
                handshake("/ws/device/" + deviceId, null),
                handshake("/ws/device/not-a-uuid", "Bearer " + secret),
                handshake("/ws/device/" + deviceId.toString().toUpperCase(), "Bearer " + secret),
                handshake("/ws/device/" + deviceId, "Basic " + secret),
                handshake("/ws/device/" + deviceId, "Bearer not-base64url-of-32-bytes"));

        RawWebSocket first = rejected.getFirst();
        assertThat(first.status).isEqualTo(401);
        assertThat(first.body).isEmpty();
        for (RawWebSocket response : rejected) {
            assertThat(response.status).isEqualTo(401);
            assertThat(response.body).isEqualTo(first.body);
            assertThat(comparable(response.headers)).isEqualTo(comparable(first.headers));
            response.close();
        }
    }

    @Test
    void eleventhFailedHandshakeInAMinuteFromOneIpGets429() throws Exception {
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(TestData.uniqueSlug(), "Panda", secret);
        String ip = uniqueIp();

        for (int attempt = 1; attempt <= HandshakeFailureLimiter.FAILURES_PER_MINUTE; attempt++) {
            try (RawWebSocket response = handshake(ip, "/ws/device/" + deviceId, "Bearer " + TestData.randomSecret())) {
                assertThat(response.status).as("attempt %d", attempt).isEqualTo(401);
            }
        }

        try (RawWebSocket eleventh = handshake(ip, "/ws/device/" + deviceId, "Bearer " + TestData.randomSecret());
                RawWebSocket rightSecret = handshake(ip, "/ws/device/" + deviceId, "Bearer " + secret);
                RawWebSocket otherIp = handshake(uniqueIp(), "/ws/device/" + deviceId, "Bearer " + secret)) {
            assertThat(eleventh.status).isEqualTo(429);
            assertThat(rightSecret.status).isEqualTo(429);
            assertThat(otherIp.status).isEqualTo(101);
        }
    }

    @Test
    void spoofedLeftmostForwardedForEntriesCountAgainstTheRealClientBehindTheProxy() throws Exception {
        UUID deviceId = data.device(TestData.uniqueSlug(), "Panda", TestData.randomSecret());
        String client = TestData.uniquePublicIp();

        for (int attempt = 1; attempt <= HandshakeFailureLimiter.FAILURES_PER_MINUTE; attempt++) {
            String forwardedFor = TestData.uniquePublicIp() + ", " + client;
            try (RawWebSocket response =
                    handshake(forwardedFor, "/ws/device/" + deviceId, "Bearer " + TestData.randomSecret())) {
                assertThat(response.status).as("attempt %d", attempt).isEqualTo(401);
            }
        }

        try (RawWebSocket spoofed = handshake(
                        TestData.uniquePublicIp() + ", " + client,
                        "/ws/device/" + deviceId,
                        "Bearer " + TestData.randomSecret());
                RawWebSocket otherClient = handshake(
                        TestData.uniquePublicIp(), "/ws/device/" + deviceId, "Bearer " + TestData.randomSecret())) {
            assertThat(spoofed.status).isEqualTo(429);
            assertThat(otherClient.status).isEqualTo(401);
        }
    }

    @Test
    void secondConnectionClosesTheFirstWith4000AndStaysRegistered() throws Exception {
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(TestData.uniqueSlug(), "Panda", secret);

        try (TestSocket first = TestSocket.connectDevice(port, deviceId, secret)) {
            first.next();
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> sessions.find(deviceId).isPresent());
            WebSocketSession firstSession = sessions.find(deviceId).orElseThrow();
            try (TestSocket second = TestSocket.connectDevice(port, deviceId, secret)) {
                second.next();

                CloseStatus status = first.awaitClose();
                assertThat(status.getCode()).isEqualTo(4000);
                assertThat(status.getReason()).isEqualTo("replaced");
                // Close codes do not reach devices through Render's proxy, so the reason comes first as a message.
                assertThat(first.nextJson()).isEqualTo(Map.of("type", "closing", "reason", "replaced"));
                assertThat(first.pending()).isZero();
                await().atMost(Duration.ofSeconds(5)).until(() -> !firstSession.isOpen());

                // The first session's close callback has run by now and must not have removed the second.
                await().during(Duration.ofMillis(500))
                        .atMost(Duration.ofSeconds(2))
                        .until(() -> sessions.find(deviceId)
                                .filter(WebSocketSession::isOpen)
                                .isPresent());
                assertThat(sessions.find(deviceId)).get().isNotSameAs(firstSession);
                sessions.find(deviceId).orElseThrow().sendMessage(new TextMessage("probe"));
                assertThat(second.next()).isEqualTo("probe");
                assertThat(second.isOpen()).isTrue();
            }
        }
    }

    @Test
    void lateCloseOfTheReplacedSessionNeverEvictsTheNewOne() throws Exception {
        DeviceSessions registry =
                new DeviceSessions(new ChannelMessages(JsonMapper.builder().build()));
        UUID deviceId = UUID.randomUUID();
        WebSocketSession old = mock(WebSocketSession.class);
        WebSocketSession replacement = mock(WebSocketSession.class);

        registry.register(deviceId, old);
        registry.register(deviceId, replacement);
        registry.unregister(deviceId, old);

        InOrder order = inOrder(old);
        order.verify(old).sendMessage(new TextMessage("{\"type\":\"closing\",\"reason\":\"replaced\"}"));
        order.verify(old).close(DeviceSessions.REPLACED);
        verify(replacement, never()).close(DeviceSessions.REPLACED);
        assertThat(registry.find(deviceId)).containsSame(replacement);

        registry.unregister(deviceId, replacement);
        assertThat(registry.find(deviceId)).isEmpty();
    }

    @Test
    void messageOver1KbClosesWith1009() throws Exception {
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(TestData.uniqueSlug(), "Panda", secret);

        try (TestSocket device = TestSocket.connectDevice(port, deviceId, secret)) {
            device.next();
            device.send("x".repeat(DeviceChannelHandler.MAX_INBOUND_BYTES));
            device.sync();
            assertThat(device.isOpen()).isTrue();

            device.send("x".repeat(DeviceChannelHandler.MAX_INBOUND_BYTES + 1));

            assertThat(device.awaitClose().getCode()).isEqualTo(1009);
        }
    }

    @Test
    void twentyInvalidMessagesInAMinuteCloseWith1008() throws Exception {
        clock.pin(NOW);
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(TestData.uniqueSlug(), "Panda", secret);

        try (TestSocket device = TestSocket.connectDevice(port, deviceId, secret)) {
            device.next();
            sendInvalid(device, InvalidMessages.LIMIT - 1);
            device.sync();
            assertThat(device.isOpen()).isTrue();

            sendInvalid(device, 1);

            assertThat(device.awaitClose().getCode()).isEqualTo(1008);
        }
    }

    @Test
    void invalidMessagesOlderThanAMinuteDoNotCount() throws Exception {
        clock.pin(NOW);
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(TestData.uniqueSlug(), "Panda", secret);

        try (TestSocket device = TestSocket.connectDevice(port, deviceId, secret)) {
            device.next();
            sendInvalid(device, InvalidMessages.LIMIT - 1);
            device.sync();
            clock.advance(Duration.ofSeconds(61));

            sendInvalid(device, InvalidMessages.LIMIT - 1);
            device.sync();
            assertThat(device.isOpen()).isTrue();

            sendInvalid(device, 1);
            assertThat(device.awaitClose().getCode()).isEqualTo(1008);
        }
    }

    @Test
    void handshakeWithoutUpgradeIsGenericProblemDetails() throws Exception {
        String secret = TestData.randomSecret();
        UUID deviceId = data.device(TestData.uniqueSlug(), "Panda", secret);
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ws/device/" + deviceId))
                                .header("Authorization", "Bearer " + secret)
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .contains("\"code\":\"BAD_REQUEST\"")
                .contains("\"detail\":\"The request is not valid.\"")
                .doesNotContain("Upgrade");
    }

    private static void sendInvalid(TestSocket device, int count) {
        List<String> invalid =
                List.of("not json", "{\"type\":\"dance\"}", "{\"type\":\"ack\",\"id\":\"nope\"}", "[]", "null");
        for (int i = 0; i < count; i++) {
            device.send(invalid.get(i % invalid.size()));
        }
    }

    private RawWebSocket handshake(String path, String authorization) throws Exception {
        return handshake(uniqueIp(), path, authorization);
    }

    private RawWebSocket handshake(String ip, String path, String authorization) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Forwarded-For", ip);
        if (authorization != null) {
            headers.put("Authorization", authorization);
        }
        return RawWebSocket.handshake(port, path, headers);
    }

    /** Headers without the ones that differ on every response: the date and the CSP nonce. */
    private static Map<String, String> comparable(Map<String, String> headers) {
        Map<String, String> copy = new HashMap<>(headers);
        copy.remove("date");
        copy.computeIfPresent("content-security-policy", (name, csp) -> csp.replaceAll("'nonce-[^']*'", "'nonce'"));
        return copy;
    }
}
