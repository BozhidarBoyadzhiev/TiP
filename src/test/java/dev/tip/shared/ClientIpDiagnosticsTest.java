package dev.tip.shared;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/** The temporary client IP log with {@code TIP_DEBUG_CLIENT_IP=true}. Off, it is covered by {@link BehindProxyTest}. */
@TestPropertySource(properties = "TIP_DEBUG_CLIENT_IP=true")
@ExtendWith(OutputCaptureExtension.class)
class ClientIpDiagnosticsTest extends IntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Test
    void logsOneLineWithTheAddressesAndTheRawProxyHeadersOfAPublicApiRequest(CapturedOutput output) throws Exception {
        String client = TestData.uniquePublicIp();
        String forwardedFor = "203.0.113.1, " + client;
        String longValue = "a".repeat(250);
        String message = "message-" + TestData.uniqueId("TEXT");
        String cookie = TestData.uniqueId("COOKIE");

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri("/api/public/devices/no-such-panda/tips"))
                        .header("X-Forwarded-For", forwardedFor)
                        .header("X-Real-IP", "198.51.100.7")
                        .header("True-Client-IP", longValue)
                        .header("Cookie", "SESSION=" + cookie)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"amountMinor\":100,\"currency\":\"GBP\",\"message\":\"" + message + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        String expected = "Client IP check: connection=127.0.0.1 resolved=" + client
                + " X-Forwarded-For=\"" + forwardedFor + "\""
                + " X-Real-IP=\"198.51.100.7\""
                + " True-Client-IP=\"" + "a".repeat(200) + "\""
                + " CF-Connecting-IP=-";
        assertThat(output.getOut()).contains(expected + System.lineSeparator());
        assertThat(Pattern.compile(Pattern.quote("resolved=" + client + " "))
                        .matcher(output.getOut())
                        .results()
                        .count())
                .isEqualTo(1);
        assertThat(output.getOut()).doesNotContain(message).doesNotContain(cookie);
    }

    @Test
    void logsNothingOutsideThePublicApi(CapturedOutput output) throws Exception {
        String client = TestData.uniquePublicIp();

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri("/actuator/health"))
                        .header("X-Forwarded-For", client)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(output.getOut()).doesNotContain("resolved=" + client);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
