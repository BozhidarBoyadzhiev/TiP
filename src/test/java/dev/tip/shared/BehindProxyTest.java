package dev.tip.shared;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.TestData;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The app as Render runs it: behind a proxy that terminates TLS, appends the real client address to
 * {@code X-Forwarded-For} and sets {@code X-Forwarded-Proto}. Real HTTP on a random port, because the
 * container applies the forwarded headers and MockMvc has no container. The test client connects from
 * localhost, a private address, so the app trusts it as the proxy.
 */
class BehindProxyTest extends IntegrationTest {

    private static final int TIPS_PER_MINUTE = 10;
    private static final int LOGINS_PER_MINUTE = 5;
    private static final Pattern CSRF_TOKEN = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    TestData data;

    @Autowired
    HealthContributorRegistry health;

    @Test
    void spoofedLeftmostForwardedForEntriesShareTheRealClientsTipBucket() throws Exception {
        String client = TestData.uniquePublicIp();

        for (int i = 1; i <= TIPS_PER_MINUTE; i++) {
            assertThat(createTip(TestData.uniquePublicIp() + ", " + client).statusCode())
                    .as("tip %d", i)
                    .isEqualTo(404);
        }

        assertThat(createTip(TestData.uniquePublicIp() + ", " + client).statusCode())
                .isEqualTo(429);
    }

    @Test
    void differentClientsBehindTheProxyGetTheirOwnTipBuckets() throws Exception {
        String client = TestData.uniquePublicIp();
        for (int i = 1; i <= TIPS_PER_MINUTE; i++) {
            createTip(client);
        }
        assertThat(createTip(client).statusCode()).isEqualTo(429);

        assertThat(createTip(TestData.uniquePublicIp()).statusCode()).isEqualTo(404);
    }

    @Test
    void tipWithoutForwardedForIsCreated() throws Exception {
        PayPalStubs.createOrder(PAYPAL, TestData.uniqueId("ORDER"));

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri("/api/public/devices/demo-panda/tips"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"amountMinor\":100,\"currency\":\"GBP\",\"message\":null}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void clientIpDiagnosticsLogNothingWhenOff(CapturedOutput output) throws Exception {
        String client = TestData.uniquePublicIp();

        assertThat(createTip(client).statusCode()).isEqualTo(404);

        assertThat(output.getOut()).doesNotContain("Client IP check").doesNotContain(client);
    }

    @Test
    void spoofedLeftmostForwardedForEntriesShareTheRealClientsLoginBucket() throws Exception {
        String client = TestData.uniquePublicIp();

        for (int i = 1; i <= LOGINS_PER_MINUTE; i++) {
            assertThat(login(TestData.uniquePublicIp() + ", " + client, TestData.uniqueEmail(), "wrong-password")
                            .statusCode())
                    .as("attempt %d", i)
                    .isEqualTo(401);
        }

        assertThat(login(TestData.uniquePublicIp() + ", " + client, TestData.uniqueEmail(), "wrong-password")
                        .statusCode())
                .isEqualTo(429);
    }

    @Test
    void loginOverForwardedHttpsSetsASecureSessionCookieAndRedirectsOverHttps() throws Exception {
        String email = TestData.uniqueEmail();
        data.owner(email, "Ada Owner", TestData.OWNER_PASSWORD);

        HttpResponse<String> response = login(TestData.uniquePublicIp(), email, TestData.OWNER_PASSWORD);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(this::isHttpsOrRelative);
        List<String> sessionCookies = response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith("JSESSIONID="))
                .toList();
        assertThat(sessionCookies)
                .isNotEmpty()
                .allSatisfy(cookie -> assertThat(cookie)
                        .contains("; Secure")
                        .contains("; HttpOnly")
                        .containsIgnoringCase("SameSite=Lax"));
        assertThat(response.headers().firstValue("Strict-Transport-Security")).isPresent();
    }

    @Test
    void signInRedirectOverForwardedHttpsStaysOnHttps() throws Exception {
        HttpResponse<String> response = http.send(
                forwarded(HttpRequest.newBuilder(uri("/owner")), TestData.uniquePublicIp())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue("https://tip.example.com/owner/login");
    }

    @Test
    void pageOverForwardedHttpsNamesThePublicWebSocketOriginNotTheForwardedHost() throws Exception {
        HttpResponse<String> response = http.send(
                forwarded(HttpRequest.newBuilder(uri("/p/demo-panda")), TestData.uniquePublicIp())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("Content-Security-Policy"))
                .hasValueSatisfying(policy -> assertThat(policy)
                        .contains("connect-src 'self' ws://localhost:8080 ")
                        .doesNotContain("tip.example.com"));
    }

    @Test
    void healthIsPublicAndRevealsOnlyItsStatus() throws Exception {
        HttpResponse<String> response = http.send(
                forwarded(HttpRequest.newBuilder(uri("/actuator/health")), TestData.uniquePublicIp())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void healthChecksOnlyLocalResourcesNeverPayPalOrAnthropic() {
        List<String> contributors =
                health.stream().map(entry -> entry.name()).sorted().toList();

        assertThat(contributors).containsExactly("db", "diskSpace", "ping", "ssl");
    }

    private HttpResponse<String> createTip(String forwardedFor) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri("/api/public/devices/no-such-panda/tips"))
                        .header("X-Forwarded-For", forwardedFor)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"amountMinor\":100,\"currency\":\"GBP\",\"message\":null}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Opens the login page for a session and its CSRF token, then signs in, both over forwarded HTTPS. */
    private HttpResponse<String> login(String forwardedFor, String email, String password) throws Exception {
        HttpResponse<String> page = http.send(
                forwarded(HttpRequest.newBuilder(uri("/owner/login")), forwardedFor)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher token = CSRF_TOKEN.matcher(page.body());
        assertThat(token.find()).as("CSRF token on the login page").isTrue();
        List<String> cookies = new ArrayList<>();
        page.headers().allValues("Set-Cookie").forEach(cookie -> cookies.add(cookie.split(";", 2)[0]));

        String form = "email=" + encode(email) + "&password=" + encode(password) + "&_csrf=" + encode(token.group(1));
        return http.send(
                forwarded(HttpRequest.newBuilder(uri("/owner/login")), forwardedFor)
                        .header("Cookie", String.join("; ", cookies))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The headers Render's proxy adds for a browser on {@code https://tip.example.com}. */
    private static HttpRequest.Builder forwarded(HttpRequest.Builder request, String forwardedFor) {
        return request.header("X-Forwarded-For", forwardedFor)
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "tip.example.com");
    }

    private void isHttpsOrRelative(String location) {
        assertThat(location)
                .satisfiesAnyOf(
                        value -> assertThat(value).startsWith("https://"),
                        value -> assertThat(value).startsWith("/"));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
