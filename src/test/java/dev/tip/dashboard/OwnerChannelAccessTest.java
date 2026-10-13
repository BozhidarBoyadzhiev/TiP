package dev.tip.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.devices.RawWebSocket;
import dev.tip.devices.TestSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.socket.WebSocketHttpHeaders;

/**
 * The owner channel and the new access levels: the read only demo is refused with {@code closing} {@code
 * read_only} and never takes a place among an owner's sockets, and an expired sandbox's socket is closed with
 * {@code closing} {@code sandbox_expired}.
 */
class OwnerChannelAccessTest extends IntegrationTest {

    private static final Pattern CSRF_TOKEN = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    OwnerSockets sockets;

    @Autowired
    OwnerKeepalive keepalive;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aReadOnlyDemoSocketIsToldWhyAndClosedWithoutTakingAPlace() throws Exception {
        UUID demoOwner = jdbc.sql("SELECT id FROM owner WHERE kind = 'PUBLIC_DEMO'")
                .query(UUID.class)
                .single();
        String cookie = signIn("/demo/session", Map.of());
        List<TestSocket> demo = new ArrayList<>();
        try {
            for (int i = 0; i <= OwnerSockets.MAX_PER_OWNER; i++) {
                TestSocket socket = connect(cookie);
                demo.add(socket);
                assertThat(socket.nextJson()).isEqualTo(Map.of("type", "closing", "reason", "read_only"));
                assertThat(socket.awaitClose().getCode()).isEqualTo(4004);
            }
            assertThat(sockets.count(demoOwner)).isZero();
        } finally {
            for (TestSocket socket : demo) {
                socket.close();
            }
        }
    }

    @Test
    void anExpiredSandboxsSocketIsClosedAndItsHandshakeRefused() throws Exception {
        Instant now = Instant.parse("2026-10-22T08:00:00Z");
        clock.pin(now);
        String cookie = signIn("/judges/session", Map.of("code", JUDGE_CODE, "timeZone", "UTC"));
        String later = signIn("/judges/session", Map.of("code", JUDGE_CODE, "timeZone", "UTC"));
        try (TestSocket socket = connect(cookie)) {
            socket.sync();

            clock.pin(now.plus(Duration.ofHours(72)));
            keepalive.pingOrClose();

            assertThat(socket.nextJson()).isEqualTo(Map.of("type", "closing", "reason", "sandbox_expired"));
            assertThat(socket.awaitClose().getCode()).isEqualTo(4003);
        }
        try (RawWebSocket refused = RawWebSocket.handshake(
                port,
                "/ws/owner",
                Map.of(
                        "Cookie",
                        later,
                        "Origin",
                        TestSocket.PUBLIC_ORIGIN,
                        "X-Forwarded-For",
                        TestData.uniquePublicIp()))) {
            assertThat(refused.status).isEqualTo(401);
            assertThat(refused.body).contains("\"code\":\"SANDBOX_EXPIRED\"");
        }
    }

    private TestSocket connect(String cookie) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(TestSocket.PUBLIC_ORIGIN);
        headers.set("Cookie", cookie);
        return TestSocket.connect(URI.create("ws://localhost:" + port + "/ws/owner"), headers);
    }

    /** Posts the form from a fresh client address with the login page's CSRF token; returns the session cookie. */
    private String signIn(String path, Map<String, String> fields) throws Exception {
        String ip = TestData.uniquePublicIp();
        HttpResponse<String> page = http.send(
                HttpRequest.newBuilder(uri("/owner/login"))
                        .header("X-Forwarded-For", ip)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher token = CSRF_TOKEN.matcher(page.body());
        assertThat(token.find()).isTrue();
        StringBuilder form = new StringBuilder("_csrf=" + encode(token.group(1)));
        fields.forEach(
                (name, value) -> form.append('&').append(name).append('=').append(encode(value)));
        HttpResponse<String> signedIn = http.send(
                HttpRequest.newBuilder(uri(path))
                        .header("X-Forwarded-For", ip)
                        .header("Cookie", sessionCookie(page))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(signedIn.statusCode()).isEqualTo(302);
        return sessionCookie(signedIn);
    }

    private static String sessionCookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("JSESSIONID="))
                .map(c -> c.substring(0, c.indexOf(';')))
                .findFirst()
                .orElseThrow();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
