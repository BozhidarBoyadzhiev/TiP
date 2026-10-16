package dev.tip.shared;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.TestData;
import dev.tip.WebhookEvents;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Over a real port, because a chunked body, which declares no length, cannot be sent through MockMvc. */
class RequestSizeLimitTest extends IntegrationTest {

    private static final String TOO_LARGE = "x".repeat(RequestSizeLimit.MAX_BYTES + 1);

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    TestData data;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aDeclaredBodyOverTheLimitIs413BeforeAnythingReadsIt() throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/webhooks/paypal"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"" + TOO_LARGE + "\"}"));
        WebhookEvents.transmissionHeaders().forEach((name, values) -> values.forEach(v -> request.header(name, v)));

        HttpResponse<String> response = send(request);

        isTooLarge(response);
        PAYPAL.verify(0, postRequestedFor(urlPathEqualTo(PayPalStubs.VERIFY_WEBHOOK_PATH)));
    }

    /** Only the headers and a few bytes arrive: the answer comes at once, without waiting for the declared body. */
    @Test
    void aDeclaredLengthOverTheLimitIsRefusedWithoutReadingTheBody() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(1_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /webhooks/paypal HTTP/1.1\r\n"
                            + "Host: localhost\r\n"
                            + "X-Forwarded-For: " + TestData.uniquePublicIp() + "\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: 10000000\r\n\r\n"
                            + "{\"id\":\"x\"")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            String statusLine = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                    .readLine();

            assertThat(statusLine).startsWith("HTTP/1.1 413");
        }
        PAYPAL.verify(0, postRequestedFor(urlPathEqualTo(PayPalStubs.VERIFY_WEBHOOK_PATH)));
    }

    @Test
    void aChunkedBodyOverTheLimitIs413() throws Exception {
        String slug = TestData.uniqueSlug();
        data.device(slug, "Size Panda");
        String body = "{\"amountMinor\":200,\"currency\":\"GBP\",\"message\":\"" + TOO_LARGE + "\"}";

        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/public/devices/" + slug + "/tips"))
                .header("Content-Type", "application/json")
                .POST(chunked(body)));

        isTooLarge(response);
        assertThat(jdbc.sql("SELECT count(*) FROM tip t JOIN device d ON d.id = t.device_id WHERE d.slug = ?")
                        .param(slug)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aFormPostOverTheLimitIs413() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/owner/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("email=" + TOO_LARGE + "&password=x")));

        isTooLarge(response);
    }

    @Test
    void aChunkedBodyUnderTheLimitIsTakenAsUsual() throws Exception {
        String slug = TestData.uniqueSlug();
        data.device(slug, "Size Panda");
        PayPalStubs.createOrder(PAYPAL, TestData.uniqueId("ORDER"));

        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/public/devices/" + slug + "/tips"))
                .header("Content-Type", "application/json")
                .POST(chunked("{\"amountMinor\":200,\"currency\":\"GBP\",\"message\":\"Thanks!\"}")));

        assertThat(response.statusCode()).isEqualTo(201);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(
                request.header("X-Forwarded-For", TestData.uniquePublicIp()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.BodyPublisher chunked(String body) {
        return HttpRequest.BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static void isTooLarge(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body()).contains("\"code\":\"CONTENT_TOO_LARGE\"").doesNotContain("xxxx");
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
