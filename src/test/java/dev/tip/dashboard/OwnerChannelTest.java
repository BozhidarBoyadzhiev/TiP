package dev.tip.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.AnthropicStubs;
import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.TestData;
import dev.tip.devices.PresenceJobRunner;
import dev.tip.devices.RawWebSocket;
import dev.tip.devices.TestSocket;
import dev.tip.owners.api.OwnerPrincipal;
import dev.tip.tipping.api.TipCaptured;
import dev.tip.tipping.api.TipRefunded;
import dev.tip.tipping.api.TipReversed;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The owner's live channel over real HTTP and WebSockets, with a real sign in. */
class OwnerChannelTest extends IntegrationTest {

    private static final Pattern CSRF_TOKEN = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> FRAME = new TypeReference<>() {};

    /** For what must happen; what must not is checked with a ping and pong sync, never a sleep. */
    private static final Duration WAIT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    TestData data;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    OwnerSockets sockets;

    @Autowired
    OwnerKeepalive keepalive;

    @Autowired
    PresenceJobRunner presence;

    String email;
    UUID ownerId;
    UUID deviceId;
    String slug;

    @BeforeEach
    void ownerWithADevice() {
        email = TestData.uniqueEmail();
        ownerId = data.owner(email, "Alex", TestData.OWNER_PASSWORD);
        slug = TestData.uniqueSlug();
        deviceId = data.device(ownerId, slug, "Live Panda");
    }

    @Test
    void withoutASessionTheHandshakeIs401() throws Exception {
        try (RawWebSocket socket = handshake(Map.of("Origin", TestSocket.PUBLIC_ORIGIN))) {
            assertThat(socket.status).isEqualTo(401);
        }
        try (RawWebSocket socket = handshake(
                Map.of("Origin", TestSocket.PUBLIC_ORIGIN, "Cookie", "JSESSIONID=0123456789ABCDEF0123456789ABCDEF"))) {
            assertThat(socket.status).isEqualTo(401);
        }
    }

    @Test
    void anyOriginButThePublicOneIs403AndThePublicOneConnects() throws Exception {
        String cookie = signIn(email);
        for (Map<String, String> headers : List.of(
                Map.of("Cookie", cookie),
                Map.of("Cookie", cookie, "Origin", "https://evil.example"),
                Map.of("Cookie", cookie, "Origin", "http://localhost:8080.evil.example"),
                Map.of("Cookie", cookie, "Origin", "http://localhost:" + port),
                Map.of("Cookie", cookie, "Origin", "null"))) {
            try (RawWebSocket socket = handshake(headers)) {
                assertThat(socket.status).as("%s", headers.get("Origin")).isEqualTo(403);
            }
        }
        try (RawWebSocket socket = handshake(Map.of("Cookie", cookie, "Origin", TestSocket.PUBLIC_ORIGIN))) {
            assertThat(socket.status).isEqualTo(101);
        }
    }

    @Test
    void theOwnerHearsAboutTheirOwnTipsOnlyAndTodayOnceForABurst() throws Exception {
        String otherEmail = TestData.uniqueEmail();
        UUID otherOwner = data.owner(otherEmail, "Sam", TestData.OWNER_PASSWORD);
        data.device(otherOwner, TestData.uniqueSlug(), "Other Panda");
        try (TestSocket mine = connect(signIn(email));
                TestSocket theirs = connect(signIn(otherEmail))) {
            awaitSockets(ownerId, 1);
            awaitSockets(otherOwner, 1);
            List<UUID> tips = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                UUID tipId = UUID.randomUUID();
                tips.add(tipId);
                transactions.executeWithoutResult(
                        status -> events.publishEvent(new TipCaptured(tipId, deviceId, clock.instant())));
            }

            List<Map<String, Object>> received = new ArrayList<>();
            for (int i = 0; i < tips.size(); i++) {
                received.add(mine.nextJson());
            }
            assertThat(received)
                    .allSatisfy(
                            m -> assertThat(m).containsOnlyKeys("type", "id").containsEntry("type", "tip"))
                    .extracting(m -> UUID.fromString((String) m.get("id")))
                    .containsExactlyInAnyOrderElementsOf(tips);
            mine.sync();
            assertThat(mine.pending()).as("no today before the job runs").isZero();

            sockets.flushToday();
            assertThat(mine.nextJson()).isEqualTo(Map.of("type", "today"));
            sockets.flushToday();
            mine.sync();
            assertThat(mine.pending()).as("one today for the whole burst").isZero();
            theirs.sync();
            assertThat(theirs.pending()).isZero();
        }
    }

    @Test
    void refundsAndReversalsAreNotifiedToo() throws Exception {
        try (TestSocket socket = connect(signIn(email))) {
            awaitSockets(ownerId, 1);
            UUID refunded = UUID.randomUUID();
            UUID reversed = UUID.randomUUID();
            transactions.executeWithoutResult(status -> {
                events.publishEvent(new TipRefunded(refunded, deviceId, clock.instant()));
                events.publishEvent(new TipReversed(reversed, deviceId, clock.instant()));
            });

            assertThat(List.of(socket.nextJson(), socket.nextJson()))
                    .containsExactlyInAnyOrder(
                            Map.of("type", "tip", "id", refunded.toString()),
                            Map.of("type", "tip", "id", reversed.toString()));
        }
    }

    @Test
    void framesOfARealCaptureAndClassificationCarryNoTipContent() throws Exception {
        String message = TestData.uniqueMessage("Puedes tocar Wonderwall por favor");
        String note = "Request: Wonderwall in Spanish";
        String english = "Can you play Wonderwall please";
        String orderId = TestData.uniqueId("ORDER");
        UUID tipId = data.tipWithMessage(deviceId, orderId, message);
        AnthropicStubs.replyFor(
                ANTHROPIC,
                message,
                AnthropicStubs.classification("REQUEST", false, null, note, "es", english, "Wonderwall"));
        PayPalStubs.captureCompleted(PAYPAL, orderId, TestData.uniqueId("CAPTURE"));
        String cookie = signIn(email);

        try (RawWebSocket socket = handshake(Map.of("Cookie", cookie, "Origin", TestSocket.PUBLIC_ORIGIN))) {
            assertThat(socket.status).isEqualTo(101);
            awaitSockets(ownerId, 1);
            mvc.perform(post("/api/public/tips/{tipId}/capture", tipId)).andExpect(status().isOk());

            // Captured, then classified: a tip message each. Then the today job runs.
            List<String> frames = new ArrayList<>(List.of(socket.readUntilText(), socket.readUntilText()));
            sockets.flushToday();
            frames.add(socket.readUntilText());

            assertThat(frames)
                    .allSatisfy(frame ->
                            assertThat(frame).doesNotContain(message, note, english, "Wonderwall", "REQUEST", "GBP"));
            // Only a type and an id: no amount, whatever digits a random tip id happens to contain.
            assertThat(frames)
                    .allSatisfy(frame ->
                            assertThat(JSON.readValue(frame, FRAME).keySet()).isSubsetOf("type", "id"));
            assertThat(frames)
                    .containsExactly(
                            "{\"type\":\"tip\",\"id\":\"" + tipId + "\"}",
                            "{\"type\":\"tip\",\"id\":\"" + tipId + "\"}",
                            "{\"type\":\"today\"}");
        }
    }

    @Test
    void aVerdictIsAnnouncedAsATipIdOnlyAndNeverReachesObservers() throws Exception {
        String abuse = TestData.uniqueMessage("You are a talentless clown");
        String note = TestData.uniqueMessage("Request: stop singing");
        String english = TestData.uniqueMessage("Stop singing, you clown");
        Map<String, Object> columns = new HashMap<>();
        columns.put("message", abuse);
        columns.put("message_kind", "REQUEST");
        columns.put("owner_note", note);
        columns.put("message_language", "fr");
        columns.put("message_english", english);
        columns.put("reaction", "PLAIN");
        columns.put("flagged", true);
        columns.put("flag_reason", "insult");
        columns.put("ai_status", "DONE");
        columns.put("paypal_capture_id", TestData.uniqueId("CAPTURE"));
        UUID tipId = data.tip(deviceId, "CAPTURED", clock.instant(), columns);
        String cookie = signIn(email);

        try (RawWebSocket socket = handshake(Map.of("Cookie", cookie, "Origin", TestSocket.PUBLIC_ORIGIN));
                TestSocket observer = TestSocket.connectObserver(port, slug, TestData.uniquePublicIp())) {
            assertThat(socket.status).isEqualTo(101);
            awaitSockets(ownerId, 1);
            List<String> frames = new ArrayList<>();
            for (boolean hidden : List.of(false, true)) {
                mvc.perform(put("/api/owner/tips/{id}/verdict", tipId)
                                .with(user(principal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"hidden\":" + hidden + "}"))
                        .andExpect(status().isOk());
                frames.add(socket.readUntilText());
                frames.add(socket.readUntilText());
                sockets.flushToday();
                frames.add(socket.readUntilText());
            }

            String tip = "{\"type\":\"tip\",\"id\":\"" + tipId + "\"}";
            String digest = "{\"type\":\"digest\"}";
            String today = "{\"type\":\"today\"}";
            assertThat(frames).containsExactly(tip, digest, today, tip, digest, today);
            assertThat(frames)
                    .allSatisfy(frame -> assertThat(frame).doesNotContain(abuse, note, english, "French", "REQUEST"));
            observer.sync();
            assertThat(observer.pending())
                    .as("observers hear nothing of a verdict")
                    .isZero();
        }
    }

    @Test
    void signingOutClosesEveryOneOfTheOwnersSockets() throws Exception {
        String cookie = signIn(email);
        String otherSessionCookie = signIn(email);
        try (TestSocket first = connect(cookie);
                TestSocket second = connect(cookie);
                TestSocket otherSession = connect(otherSessionCookie)) {
            awaitSockets(ownerId, 3);

            signOut(cookie);

            for (TestSocket socket : List.of(first, second, otherSession)) {
                assertThat(socket.awaitClose().getCode()).isEqualTo(4001);
                assertThat(socket.nextJson()).isEqualTo(Map.of("type", "closing", "reason", "signed_out"));
            }
        }
        assertThat(sockets.count(ownerId)).isZero();
        try (RawWebSocket again = handshake(Map.of("Cookie", cookie, "Origin", TestSocket.PUBLIC_ORIGIN))) {
            assertThat(again.status).isEqualTo(401);
        }
    }

    @Test
    void aSocketWhoseSessionEndsIsClosedByTheContainerAtOnce() throws Exception {
        try (TestSocket expiring = connect(signIn(email))) {
            awaitSockets(ownerId, 1);
            try (TestSocket staying = connect(signIn(email))) {
                awaitSockets(ownerId, 2);

                // What Tomcat does to a session that timed out.
                firstHttpSession().invalidate();

                assertThat(expiring.awaitClose().getCode()).isEqualTo(1008);
                staying.sync();
                assertThat(staying.isOpen()).isTrue();
                awaitSockets(ownerId, 1);
            }
        }
    }

    @Test
    void aSocketWhoseSessionNoLongerHoldsTheOwnerIsClosedAtTheNextKeepaliveAndOthersStay() throws Exception {
        try (TestSocket expiring = connect(signIn(email))) {
            awaitSockets(ownerId, 1);
            try (TestSocket staying = connect(signIn(email))) {
                awaitSockets(ownerId, 2);
                firstHttpSession().removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);

                keepalive.pingOrClose();

                assertThat(expiring.awaitClose().getCode()).isEqualTo(4002);
                assertThat(expiring.nextJson()).isEqualTo(Map.of("type", "closing", "reason", "session_expired"));
                staying.sync();
                assertThat(staying.isOpen()).isTrue();
                awaitSockets(ownerId, 1);
            }
        }
    }

    /** The HTTP session of the owner's oldest socket. */
    private HttpSession firstHttpSession() {
        return sockets.all().stream()
                .filter(owned -> owned.ownerId().equals(ownerId))
                .findFirst()
                .map(owned -> (HttpSession) owned.session().getAttributes().get(OwnerHandshakeInterceptor.HTTP_SESSION))
                .orElseThrow();
    }

    @Test
    void aSixthSocketClosesTheOldest() throws Exception {
        String cookie = signIn(email);
        List<TestSocket> open = new ArrayList<>();
        try {
            for (int i = 0; i < OwnerSockets.MAX_PER_OWNER; i++) {
                open.add(connect(cookie));
                awaitSockets(ownerId, i + 1);
            }
            open.add(connect(cookie));

            assertThat(open.getFirst().awaitClose().getCode()).isEqualTo(4000);
            assertThat(open.getFirst().nextJson()).isEqualTo(Map.of("type", "closing", "reason", "replaced"));
            for (TestSocket socket : open.subList(1, open.size())) {
                socket.sync();
                assertThat(socket.isOpen()).isTrue();
            }
            assertThat(sockets.count(ownerId)).isEqualTo(OwnerSockets.MAX_PER_OWNER);
        } finally {
            for (TestSocket socket : open) {
                socket.close();
            }
        }
    }

    @Test
    void inboundMessagesAreIgnoredButAnOversizedOneCloses() throws Exception {
        try (TestSocket socket = connect(signIn(email))) {
            socket.send("{\"type\":\"subscribe\",\"owner\":\"someone else\"}");
            socket.sync();
            assertThat(socket.isOpen()).isTrue();
            assertThat(socket.pending()).isZero();

            socket.send("x".repeat(OwnerChannelHandler.MAX_INBOUND_BYTES + 1));

            assertThat(socket.awaitClose().getCode()).isEqualTo(1009);
        }
    }

    @Test
    void aDeviceIsReportedOfflineOnlyAfterItHasBeenGoneTenSeconds() throws Exception {
        Instant start = Instant.parse("2026-10-13T14:00:00Z");
        clock.pin(start);
        String secret = TestData.randomSecret();
        jdbc.sql("UPDATE device SET secret_hash = sha256(?) WHERE id = ?")
                .params(java.util.Base64.getUrlDecoder().decode(secret), deviceId)
                .update();
        try (TestSocket owner = connect(signIn(email))) {
            awaitSockets(ownerId, 1);
            TestSocket device = TestSocket.connectDevice(port, deviceId, secret);
            device.next();
            assertThat(owner.nextJson())
                    .isEqualTo(Map.of(
                            "type", "device", "slug", slug, "online", true, "lastSeenAt", "2026-10-13T14:00:00Z"));

            // Gone, and back after five seconds: nothing for the owner.
            clock.advance(Duration.ofSeconds(1));
            device.close();
            awaitLastSeen(start.plusSeconds(1));
            clock.advance(Duration.ofSeconds(5));
            presence.reportOffline();
            device = TestSocket.connectDevice(port, deviceId, secret);
            // hello comes after the server registered the session and stored the connect time.
            device.next();
            clock.advance(Duration.ofSeconds(10));
            presence.reportOffline();
            owner.sync();
            assertThat(owner.pending()).isZero();

            // Gone for good: still online after 9 seconds, offline at 10.
            device.close();
            Instant gone = clock.instant();
            awaitLastSeen(gone);
            clock.advance(Duration.ofSeconds(9));
            presence.reportOffline();
            owner.sync();
            assertThat(owner.pending()).isZero();
            mvc.perform(get("/api/owner/devices").with(user(principal())))
                    .andExpect(jsonPath("$[0].online").value(true));

            clock.advance(Duration.ofSeconds(1));
            presence.reportOffline();

            assertThat(owner.nextJson())
                    .isEqualTo(Map.of("type", "device", "slug", slug, "online", false, "lastSeenAt", gone.toString()));
            mvc.perform(get("/api/owner/devices").with(user(principal())))
                    .andExpect(jsonPath("$[0].online").value(false))
                    .andExpect(jsonPath("$[0].lastSeenAt").value(gone.toString()));
        }
    }

    @Test
    void aStoredDigestIsAnnounced() throws Exception {
        try (TestSocket socket = connect(signIn(email))) {
            awaitSockets(ownerId, 1);

            sockets.digestChanged(ownerId);

            assertThat(socket.nextJson()).isEqualTo(Map.of("type", "digest"));
        }
    }

    private OwnerPrincipal principal() {
        return new OwnerPrincipal(ownerId, email, "Alex", null);
    }

    private void awaitSockets(UUID owner, int count) {
        await().atMost(WAIT).until(() -> sockets.count(owner) == count);
    }

    /** The tracker updates its memory before it stores the time, so this also waits for the memory. */
    private void awaitLastSeen(Instant at) {
        await().atMost(WAIT)
                .until(() -> Timestamp.from(at)
                        .equals(jdbc.sql("SELECT last_seen_at FROM device WHERE id = ?")
                                .param(deviceId)
                                .query(Timestamp.class)
                                .single()));
    }

    private TestSocket connect(String cookie) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(TestSocket.PUBLIC_ORIGIN);
        headers.set("Cookie", cookie);
        return TestSocket.connect(URI.create("ws://localhost:" + port + "/ws/owner"), headers);
    }

    private RawWebSocket handshake(Map<String, String> headers) throws Exception {
        Map<String, String> all = new HashMap<>(headers);
        all.put("X-Forwarded-For", TestData.uniquePublicIp());
        return RawWebSocket.handshake(port, "/ws/owner", all);
    }

    /** Signs in over real HTTP from a fresh client address and returns the session cookie. */
    private String signIn(String ownerEmail) throws Exception {
        String ip = TestData.uniquePublicIp();
        HttpResponse<String> page = http.send(
                HttpRequest.newBuilder(uri("/owner/login"))
                        .header("X-Forwarded-For", ip)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        String form = "email=" + encode(ownerEmail) + "&password=" + encode(TestData.OWNER_PASSWORD) + "&_csrf="
                + encode(csrfToken(page.body()));
        HttpResponse<String> login = http.send(
                HttpRequest.newBuilder(uri("/owner/login"))
                        .header("X-Forwarded-For", ip)
                        .header("Cookie", sessionCookie(page))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(302);
        return sessionCookie(login);
    }

    private void signOut(String cookie) throws Exception {
        HttpResponse<String> home = http.send(
                HttpRequest.newBuilder(uri("/owner"))
                        .header("Cookie", cookie)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> logout = http.send(
                HttpRequest.newBuilder(uri("/owner/logout"))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + encode(csrfToken(home.body()))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(logout.statusCode()).isEqualTo(302);
        assertThat(logout.headers().firstValue("Location"))
                .hasValueSatisfying(l -> assertThat(l).contains("signedOut"));
    }

    private static String csrfToken(String html) {
        Matcher token = CSRF_TOKEN.matcher(html);
        assertThat(token.find()).as("CSRF token on the page").isTrue();
        return token.group(1);
    }

    private static String sessionCookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("JSESSIONID="))
                .map(c -> c.split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new AssertionError("no session cookie"));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
