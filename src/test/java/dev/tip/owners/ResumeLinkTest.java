package dev.tip.owners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.owners.api.OwnerPrincipal;
import io.nayuki.qrcodegen.QrCode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** "Continue on another device": a one time link, valid 10 minutes, bound to its sandbox. */
class ResumeLinkTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-21T09:00:00Z");

    private static final Pattern SESSION_COOKIE = Pattern.compile("JSESSIONID=[^;]+");

    private static final Pattern CSRF_TOKEN = Pattern.compile("<meta name=\"csrf-token\" content=\"([^\"]+)\">");

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    ResumeTokens tokens;

    @Autowired
    TestData data;

    Judges.Sandbox sandbox;

    @BeforeEach
    void sandbox() throws Exception {
        clock.pin(NOW);
        sandbox = new Judges(mvc, jdbc).sandbox(JUDGE_CODE, "UTC");
    }

    @Test
    void theLinkCarriesItsTokenInTheFragmentAndSignsAnotherBrowserInOnce() throws Exception {
        String url = link();
        String token = Judges.token(url);
        assertThat(url).startsWith("http://localhost:8080/judges/resume#t=").doesNotContain("?");
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(16);
        assertThat(jdbc.sql("SELECT token_hash FROM resume_token WHERE owner_id = ?")
                        .param(sandbox.ownerId())
                        .query(byte[].class)
                        .single())
                .isEqualTo(MessageDigest.getInstance("SHA-256")
                        .digest(Base64.getUrlDecoder().decode(token)));

        MockHttpSession other = new MockHttpSession();
        mvc.perform(Judges.resume(token).session(other).with(TestData.newClient()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        assertThat(Judges.principal(other).id()).isEqualTo(sandbox.ownerId());
        mvc.perform(get("/api/owner/me").session(other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Judge"));

        MockHttpServletResponse second = resume(token);
        assertFailed(second);
        for (String bad : Arrays.asList("not-a-token", null, "", "A".repeat(22))) {
            MockHttpServletResponse failed = resume(bad);
            assertFailed(failed);
            assertThat(withoutInstance(failed)).isEqualTo(withoutInstance(second));
        }
    }

    /**
     * Over a real port, so the browser's old cookie can be sent again: the session the page made is never the one
     * that gets signed in.
     */
    @Test
    void signingInGivesTheBrowserANewSessionIdAndTheOldOneStaysSignedOut() throws Exception {
        String token = Judges.token(link());
        String ip = TestData.uniquePublicIp();

        HttpResponse<String> page = http.send(
                HttpRequest.newBuilder(uri("/judges/resume"))
                        .header("X-Forwarded-For", ip)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        String before = sessionCookie(page);
        Matcher csrfToken = CSRF_TOKEN.matcher(page.body());
        assertThat(csrfToken.find()).isTrue();

        HttpResponse<String> resumed = http.send(
                HttpRequest.newBuilder(uri("/judges/resume"))
                        .header("X-Forwarded-For", ip)
                        .header("Cookie", before)
                        .header("X-CSRF-TOKEN", csrfToken.group(1))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + token + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resumed.statusCode()).isEqualTo(204);
        String after = sessionCookie(resumed);

        assertThat(after).isNotEqualTo(before);
        assertThat(me(before).statusCode()).isEqualTo(401);
        HttpResponse<String> signedIn = me(after);
        assertThat(signedIn.statusCode()).isEqualTo(200);
        assertThat(json.readTree(signedIn.body()).path("displayName").asString())
                .isEqualTo("Judge");
    }

    @Test
    void fetchingThePageNeverSpendsTheToken() throws Exception {
        String token = Judges.token(link());

        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
            for (MockHttpServletRequestBuilder fetch : List.of(
                    request(method, "/judges/resume"),
                    request(method, "/judges/resume").param("t", token),
                    request(method, "/judges/resume?t=" + token + "&preview=1"))) {
                mvc.perform(fetch.with(TestData.newClient()))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("Referrer-Policy", "no-referrer"))
                        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM resume_token WHERE owner_id = ? AND used_at IS NULL")
                        .param(sandbox.ownerId())
                        .query(Long.class)
                        .single())
                .isOne();

        MockHttpSession other = new MockHttpSession();
        mvc.perform(Judges.resume(token).session(other).with(TestData.newClient()))
                .andExpect(status().isNoContent());
        assertThat(Judges.principal(other).id()).isEqualTo(sandbox.ownerId());
    }

    @Test
    void thePostNeedsTheCsrfTokenAndSpendsNothingWithoutIt() throws Exception {
        String token = Judges.token(link());

        mvc.perform(post("/judges/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}")
                        .with(TestData.newClient()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        mvc.perform(Judges.resume(token).with(TestData.newClient())).andExpect(status().isNoContent());
    }

    @Test
    void anExpiredLinkFailsLikeAnUnknownOne() throws Exception {
        String token = Judges.token(link());

        clock.pin(NOW.plus(Duration.ofMinutes(10)));

        MockHttpServletResponse expired = resume(token);
        MockHttpServletResponse unknown =
                resume(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]));
        assertFailed(expired);
        assertFailed(unknown);
        assertThat(withoutInstance(expired)).isEqualTo(withoutInstance(unknown));
    }

    @Test
    void aTokenOfASandboxThatHasExpiredFails() throws Exception {
        String token = Judges.token(link());
        jdbc.sql("UPDATE owner SET expires_at = ? WHERE id = ?")
                .params(java.sql.Timestamp.from(NOW.minus(Duration.ofMinutes(1))), sandbox.ownerId())
                .update();
        MockHttpSession other = new MockHttpSession();

        MockHttpServletResponse expiredSandbox = mvc.perform(
                        Judges.resume(token).session(other).with(TestData.newClient()))
                .andReturn()
                .getResponse();
        MockHttpServletResponse unknown =
                resume(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]));

        assertFailed(expiredSandbox);
        assertThat(withoutInstance(expiredSandbox)).isEqualTo(withoutInstance(unknown));
        assertThat(other.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
    }

    @Test
    void aLinkNeverOutlivesItsSandbox() throws Exception {
        clock.pin(NOW.plus(Duration.ofHours(72)).minus(Duration.ofMinutes(3)));
        String body = mvc.perform(post("/api/owner/resume-link")
                        .session(sandbox.session())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(Instant.parse(json.readTree(body).path("expiresAt").asString()))
                .isEqualTo(NOW.plus(Duration.ofHours(72)));
    }

    @Test
    void twoBrowsersOpeningTheSameLinkAtOnceGetOneSession() throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(Base64.getUrlDecoder().decode(Judges.token(link())));
        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Optional<UUID>>> results = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return tokens.consume(hash, NOW.plusSeconds(1));
                }));
            }
            start.countDown();
            List<Optional<UUID>> owners = new ArrayList<>();
            for (Future<Optional<UUID>> result : results) {
                owners.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(owners)
                    .filteredOn(Optional::isPresent)
                    .singleElement()
                    .isEqualTo(Optional.of(sandbox.ownerId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void onlyASandboxCanMakeALinkAndAnyOtherOwnerGetsThe404OfAnUnknownPath() throws Exception {
        String email = TestData.uniqueEmail();
        OwnerPrincipal real =
                new OwnerPrincipal(data.owner(email, "Real", TestData.OWNER_PASSWORD), email, "Real", null);

        MockHttpServletResponse link = mvc.perform(
                        post("/api/owner/resume-link").with(user(real)).with(csrf()))
                .andReturn()
                .getResponse();
        MockHttpServletResponse unknown = mvc.perform(
                        get("/api/owner/no-such-thing").with(user(real)))
                .andReturn()
                .getResponse();

        assertThat(link.getStatus()).isEqualTo(404).isEqualTo(unknown.getStatus());
        assertThat(withoutInstance(link)).isEqualTo(withoutInstance(unknown));
        assertThat(jdbc.sql("SELECT count(*) FROM resume_token WHERE owner_id = ?")
                        .param(real.id())
                        .query(Long.class)
                        .single())
                .isZero();
        mvc.perform(post("/api/owner/resume-link").session(sandbox.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void theLinkComesWithItsQrCodeAtErrorCorrectionMedium() throws Exception {
        String body = mvc.perform(post("/api/owner/resume-link")
                        .session(sandbox.session())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var link = json.readTree(body);
        assertThat(link.path("url").asString()).contains("/judges/resume#t=").doesNotContain("?");
        QrCode expected = QrCode.encodeText(link.path("url").asString(), QrCode.Ecc.MEDIUM);

        assertThat(link.path("qr").path("size").asInt()).isEqualTo(expected.size);
        List<String> rows = new ArrayList<>();
        link.path("qr").path("rows").forEach(row -> rows.add(row.asString()));
        assertThat(rows)
                .hasSize(expected.size)
                .allSatisfy(row -> assertThat(row).hasSize(expected.size).matches("[01]+"));
        for (int y = 0; y < expected.size; y++) {
            for (int x = 0; x < expected.size; x++) {
                assertThat(rows.get(y).charAt(x) == '1').isEqualTo(expected.getModule(x, y));
            }
        }
    }

    @Test
    void eachSandboxMakesAtMostFiveLinksInTenMinutes() throws Exception {
        for (int i = 0; i < 5; i++) {
            link();
        }

        mvc.perform(post("/api/owner/resume-link").session(sandbox.session()).with(csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        Judges.Sandbox other = new Judges(mvc, jdbc).sandbox(JUDGE_CODE, "UTC");
        mvc.perform(post("/api/owner/resume-link").session(other.session()).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void resumesAreRateLimitedPerIp() throws Exception {
        String ip = TestData.uniquePublicIp();
        for (int i = 0; i < 20; i++) {
            mvc.perform(Judges.resume("x").with(Judges.remoteAddr(ip))).andExpect(status().isUnauthorized());
        }

        mvc.perform(get("/judges/resume").with(Judges.remoteAddr(ip))).andExpect(status().isOk());
        mvc.perform(Judges.resume("x").with(Judges.remoteAddr(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    private String link() throws Exception {
        String body = mvc.perform(post("/api/owner/resume-link")
                        .session(sandbox.session())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body).path("url").asString();
    }

    private HttpResponse<String> me(String cookie) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri("/api/owner/me"))
                        .header("X-Forwarded-For", TestData.uniquePublicIp())
                        .header("Cookie", cookie)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** The {@code JSESSIONID=...} pair the response set. */
    private static String sessionCookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .map(SESSION_COOKIE::matcher)
                .filter(Matcher::find)
                .map(Matcher::group)
                .reduce((first, last) -> last)
                .orElseThrow(() -> new AssertionError("no session cookie set"));
    }

    private String withoutInstance(MockHttpServletResponse response) throws Exception {
        var problem = (ObjectNode) json.readTree(response.getContentAsString());
        problem.remove("instance");
        return problem.toString();
    }

    private MockHttpServletResponse resume(String token) throws Exception {
        return mvc.perform(Judges.resume(token).with(TestData.newClient()))
                .andReturn()
                .getResponse();
    }

    private void assertFailed(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(json.readTree(response.getContentAsString()).path("code").asString())
                .isEqualTo("RESUME_FAILED");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }
}
