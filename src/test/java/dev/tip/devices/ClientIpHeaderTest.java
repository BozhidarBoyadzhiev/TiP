package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.TestData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The app with {@code TIP_CLIENT_IP_HEADER} set to a single value header that the host's edge overwrites.
 * Real HTTP from localhost, a private address the app trusts as the proxy. Only one test per context may use
 * the connection address's tip bucket, because every request without a usable header lands in it.
 */
abstract class ClientIpHeaderTest extends IntegrationTest {

    private static final int TIPS_PER_MINUTE = 10;

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    TestData data;

    abstract String header();

    @Test
    void headerIsTheClientAndForwardedForHasNoEffect() throws Exception {
        String client = TestData.uniquePublicIp();

        for (int i = 1; i <= TIPS_PER_MINUTE; i++) {
            assertThat(createTip("no-such-panda", header(), client, "X-Forwarded-For", TestData.uniquePublicIp())
                            .statusCode())
                    .as("tip %d", i)
                    .isEqualTo(404);
        }

        assertThat(createTip("no-such-panda", header(), client, "X-Forwarded-For", TestData.uniquePublicIp())
                        .statusCode())
                .isEqualTo(429);
        assertThat(createTip("no-such-panda", header(), TestData.uniquePublicIp(), "X-Forwarded-For", client)
                        .statusCode())
                .isEqualTo(404);
    }

    @Test
    void absentOrUnusableHeaderFallsBackToTheConnectionAddress() throws Exception {
        PayPalStubs.createOrder(PAYPAL, TestData.uniqueId("ORDER"));
        String forwardedFor = "X-Forwarded-For";
        List<String[]> requests = new ArrayList<>();
        requests.add(new String[] {});
        requests.add(new String[] {forwardedFor, TestData.uniquePublicIp()});
        requests.add(new String[] {header(), TestData.uniquePublicIp() + ", " + TestData.uniquePublicIp()});
        requests.add(new String[] {header(), "panda.example.com"});
        requests.add(new String[] {header(), "not an address"});
        requests.add(new String[] {header(), "256.1.2.3"});
        requests.add(new String[] {header(), TestData.uniquePublicIp() + ":443"});
        requests.add(new String[] {header(), TestData.uniquePublicIp(), header(), TestData.uniquePublicIp()});
        requests.add(new String[] {header(), "[2001:db8::1]"});

        assertThat(createTip("demo-panda").statusCode()).as("no header at all").isEqualTo(201);
        for (String[] headers : requests) {
            assertThat(createTip("no-such-panda", headers).statusCode())
                    .as(String.join(" ", headers))
                    .isEqualTo(404);
        }

        assertThat(createTip("no-such-panda", header(), "garbage").statusCode())
                .as("eleventh request from the connection address")
                .isEqualTo(429);
        assertThat(createTip("no-such-panda", header(), TestData.uniquePublicIp())
                        .statusCode())
                .isEqualTo(404);
    }

    @Test
    void ipv6LiteralIsTheClient() throws Exception {
        String client = "2001:db8::" + Integer.toHexString(UUID.randomUUID().hashCode() & 0xffff);

        for (int i = 1; i <= TIPS_PER_MINUTE; i++) {
            createTip("no-such-panda", header(), client);
        }

        assertThat(createTip("no-such-panda", header(), client).statusCode()).isEqualTo(429);
    }

    @Test
    void deviceHandshakeFailuresCountAgainstTheHeadersClient() throws Exception {
        UUID deviceId = data.device(TestData.uniqueSlug(), "Panda", TestData.randomSecret());
        String client = TestData.uniquePublicIp();

        for (int attempt = 1; attempt <= HandshakeFailureLimiter.FAILURES_PER_MINUTE; attempt++) {
            try (RawWebSocket response = deviceHandshake(deviceId, client)) {
                assertThat(response.status).as("attempt %d", attempt).isEqualTo(401);
            }
        }

        try (RawWebSocket spoofed = deviceHandshake(deviceId, client);
                RawWebSocket otherClient = deviceHandshake(deviceId, TestData.uniquePublicIp())) {
            assertThat(spoofed.status).isEqualTo(429);
            assertThat(otherClient.status).isEqualTo(401);
        }
    }

    @Test
    void observerConnectionsCountAgainstTheHeadersClient() throws Exception {
        String slug = TestData.uniqueSlug();
        data.device(slug, "Busy Panda");
        String client = TestData.uniquePublicIp();
        List<RawWebSocket> open = new ArrayList<>();
        try {
            for (int i = 0; i < ObserverConnectionLimiter.MAX_PER_IP; i++) {
                RawWebSocket observer = observe(slug, client);
                open.add(observer);
                assertThat(observer.status).as("connection %d", i + 1).isEqualTo(101);
            }

            try (RawWebSocket sixth = observe(slug, client);
                    RawWebSocket otherClient = observe(slug, TestData.uniquePublicIp())) {
                assertThat(sixth.status).isEqualTo(429);
                assertThat(otherClient.status).isEqualTo(101);
            }
        } finally {
            for (RawWebSocket socket : open) {
                socket.close();
            }
        }
    }

    /** A tip request with the given header name and value pairs; a name may repeat. */
    private HttpResponse<String> createTip(String slug, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/public/devices/" + slug + "/tips"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"amountMinor\":100,\"currency\":\"GBP\",\"message\":null}"));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A failing device handshake from {@code client}, with a different spoofed X-Forwarded-For each time. */
    private RawWebSocket deviceHandshake(UUID deviceId, String client) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put(header(), client);
        headers.put("X-Forwarded-For", TestData.uniquePublicIp());
        headers.put("Authorization", "Bearer " + TestData.randomSecret());
        return RawWebSocket.handshake(port, "/ws/device/" + deviceId, headers);
    }

    private RawWebSocket observe(String slug, String client) throws Exception {
        return RawWebSocket.handshake(
                port,
                "/ws/observe/" + slug,
                Map.of(
                        "Origin",
                        TestSocket.PUBLIC_ORIGIN,
                        header(),
                        client,
                        "X-Forwarded-For",
                        TestData.uniquePublicIp()));
    }
}
