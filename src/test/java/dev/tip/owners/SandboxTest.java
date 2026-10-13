package dev.tip.owners;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.dashboard.api.SampleTips;
import dev.tip.devices.RawWebSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** Judge sandboxes: made by an access code, fully working on their sample tips, gone once expired. */
class SandboxTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestData data;

    @Autowired
    SampleTips samples;

    @LocalServerPort
    int port;

    Judges judges;

    @BeforeEach
    void judges() {
        judges = new Judges(mvc, jdbc);
    }

    @Test
    void aCodeMakesASandboxOwnerWithAVirtualPandaAndSampleTipsAndSignsTheBrowserIn() throws Exception {
        Instant now = Instant.parse("2026-10-20T14:30:00Z");
        clock.pin(now);

        Judges.Sandbox sandbox =
                judges.sandbox("  tip-" + JUDGE_CODE.substring(4).toLowerCase() + " ", "Europe/Sofia");

        Map<String, Object> owner = ownerRow(sandbox.ownerId());
        assertThat(owner)
                .containsEntry("kind", "SANDBOX")
                .containsEntry("email", null)
                .containsEntry("password_hash", null)
                .containsEntry("display_name", "Judge")
                .containsEntry("code_label", "judges")
                .containsEntry("time_zone", "Europe/Sofia");
        assertThat(instant(owner.get("expires_at"))).isEqualTo(now.plus(Duration.ofHours(72)));
        assertThat(sandbox.slug()).matches("judge-[0-9a-hjkmnp-tv-z]{8}");
        assertThat(jdbc.sql("SELECT display_name FROM device WHERE id = ?")
                        .param(sandbox.deviceId())
                        .query(String.class)
                        .single())
                .isEqualTo("TiP");
        assertThat(demoTips(sandbox.deviceId())).isGreaterThanOrEqualTo(20);
        assertThat(jdbc.sql("SELECT count(*) FROM tip WHERE device_id = ? AND NOT demo")
                        .param(sandbox.deviceId())
                        .query(Long.class)
                        .single())
                .isZero();

        mvc.perform(get("/api/owner/me").session(sandbox.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Judge"))
                .andExpect(jsonPath("$.timeZone").value("Europe/Sofia"))
                .andExpect(jsonPath("$.aiResting").value(false));
        mvc.perform(get("/api/owner/digest").session(sandbox.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bullets.length()").value(3));
        mvc.perform(get("/api/owner/today").session(sandbox.session()))
                .andExpect(jsonPath("$.tipCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(5)));
        mvc.perform(get("/api/owner/requests").session(sandbox.session()))
                .andExpect(jsonPath("$.topSong.title").value("Marmalade Moonwalk"));
        mvc.perform(get("/api/owner/tips").session(sandbox.session()))
                .andExpect(jsonPath("$.items[0].demo").value(true))
                .andExpect(jsonPath("$.items[0].aiStatus").exists());
        assertThat(ANTHROPIC.getAllServeEvents()).isEmpty();
    }

    @Test
    void anUnknownTimeZoneFallsBackToUtc() throws Exception {
        for (String zone : new String[] {"Mars/Olympus", "", "x".repeat(65)}) {
            Judges.Sandbox sandbox = judges.sandbox(JUDGE_CODE, zone);

            assertThat(ownerRow(sandbox.ownerId())).containsEntry("time_zone", "UTC");
        }
    }

    @Test
    void aSandboxNeverOutlivesItsCodeAndItsSessionNeverOutlivesTheSandbox() throws Exception {
        Instant now = SHORT_CODE_EXPIRES.minus(Duration.ofMinutes(30));
        clock.pin(now);

        Judges.Sandbox sandbox = judges.sandbox(SHORT_CODE, "UTC");

        assertThat(instant(ownerRow(sandbox.ownerId()).get("expires_at"))).isEqualTo(SHORT_CODE_EXPIRES);
        assertThat(sandbox.session().getMaxInactiveInterval()).isPositive().isLessThanOrEqualTo(30 * 60);
        assertThat(Judges.principal(sandbox.session()).expiresAt()).isEqualTo(SHORT_CODE_EXPIRES);
    }

    @Test
    void wrongAndExpiredCodesGetTheSameAnswerAsAWrongPasswordAndMakeNothing() throws Exception {
        clock.pin(SHORT_CODE_EXPIRES.plusSeconds(1));
        long sandboxes = sandboxCount();
        String wrong = "TIP-" + "ABCDE-FGHJK-MNPQR-STVWX";

        MockHttpServletResponse wrongCode = rejected(wrong);
        MockHttpServletResponse expiredEntry = rejected(EXPIRED_CODE);
        MockHttpServletResponse expiredNow = rejected(SHORT_CODE);
        MockHttpServletResponse empty = rejected("");

        assertThat(wrongCode.getStatus()).isEqualTo(401);
        assertThat(wrongCode.getContentAsString()).contains("\"code\":\"LOGIN_FAILED\"");
        for (MockHttpServletResponse other : new MockHttpServletResponse[] {expiredEntry, expiredNow, empty}) {
            assertThat(other.getStatus()).isEqualTo(wrongCode.getStatus());
            assertThat(other.getContentAsString()).isEqualTo(wrongCode.getContentAsString());
        }
        assertThat(sandboxCount()).isEqualTo(sandboxes);
    }

    @Test
    void anEleventhAttemptInAnHourFromOneIpIsRateLimited() throws Exception {
        String ip = TestData.uniquePublicIp();
        for (int i = 0; i < 10; i++) {
            assertThat(judges.judgeSession("wrong", "UTC", ip, new MockHttpSession())
                            .getStatus())
                    .isEqualTo(401);
        }

        MockHttpServletResponse limited = judges.judgeSession(JUDGE_CODE, "UTC", ip, new MockHttpSession());

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
        assertThat(judges.judgeSession(JUDGE_CODE, "UTC", TestData.uniquePublicIp(), new MockHttpSession())
                        .getStatus())
                .isEqualTo(302);
    }

    @Test
    void withoutACsrfTokenNothingIsMade() throws Exception {
        long sandboxes = sandboxCount();

        mvc.perform(post("/judges/session").param("code", JUDGE_CODE).param("timeZone", "UTC"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        assertThat(sandboxCount()).isEqualTo(sandboxes);
    }

    @Test
    void theSandboxPandaTakesTipsAndCanBeWatchedButNeverConnectsAsHardware() throws Exception {
        Judges.Sandbox sandbox = judges.sandbox(JUDGE_CODE, "UTC");

        mvc.perform(get("/t/" + sandbox.slug())).andExpect(status().isOk());
        mvc.perform(get("/p/" + sandbox.slug())).andExpect(status().isOk());
        mvc.perform(get("/api/public/devices/" + sandbox.slug())).andExpect(status().isOk());

        String secret = TestData.randomSecret();
        jdbc.sql("UPDATE device SET secret_hash = sha256(?) WHERE id = ?")
                .params(Base64.getUrlDecoder().decode(secret), sandbox.deviceId())
                .update();
        int sandboxDevice = deviceHandshake(sandbox.deviceId(), secret);
        int unknownDevice = deviceHandshake(UUID.randomUUID(), secret);

        assertThat(sandboxDevice).isEqualTo(401).isEqualTo(unknownDevice);
    }

    @Test
    void anExpiredSandboxIsSignedOutAndItsPagesAnswerLikeAnUnknownDevice() throws Exception {
        Instant now = Instant.parse("2026-10-20T10:00:00Z");
        clock.pin(now);
        Judges.Sandbox sandbox = judges.sandbox(JUDGE_CODE, "UTC");
        mvc.perform(get("/api/owner/me").session(sandbox.session())).andExpect(status().isOk());

        clock.pin(now.plus(Duration.ofHours(72)));

        mvc.perform(get("/api/owner/me").session(sandbox.session()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SANDBOX_EXPIRED"));
        assertThat(sandbox.session().isInvalid()).isTrue();
        Judges.Sandbox again = judges.sandbox(JUDGE_CODE, "UTC");
        clock.pin(now.plus(Duration.ofHours(144)));
        mvc.perform(get("/owner").session(again.session())).andExpect(redirectedUrl("/owner/login?sandboxExpired"));
        mvc.perform(get("/t/" + sandbox.slug()).accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
        mvc.perform(get("/p/" + sandbox.slug()).accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/devices/" + sandbox.slug()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
        mvc.perform(post("/api/public/devices/" + sandbox.slug() + "/tips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":200,\"currency\":\"GBP\"}")
                        .with(Judges.remoteAddr(TestData.uniquePublicIp())))
                .andExpect(status().isNotFound());
        try (RawWebSocket observer = RawWebSocket.handshake(
                port,
                "/ws/observe/" + sandbox.slug(),
                Map.of("Origin", "http://localhost:8080", "X-Forwarded-For", TestData.uniquePublicIp()))) {
            assertThat(observer.status).isEqualTo(404);
        }
    }

    @Test
    void sampleTipsWorkLikeRealOnesExceptRefund() throws Exception {
        Judges.Sandbox sandbox = judges.sandbox(JUDGE_CODE, "UTC");
        UUID shown = judges.demoTip(sandbox.deviceId(), false, null);
        UUID bulgarian = judges.demoTip(sandbox.deviceId(), true, "bg");

        mvc.perform(post("/api/owner/tips/{id}/refund", shown)
                        .session(sandbox.session())
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEMO_TIP"));
        assertThat(PAYPAL.findAll(postRequestedFor(urlPathMatching("/v2/payments/.*"))))
                .isEmpty();
        assertThat(data.tipStatus(shown)).isEqualTo("CAPTURED");

        mvc.perform(get("/api/owner/tips/{id}/message", bulgarian).session(sandbox.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("bg"))
                .andExpect(jsonPath("$.languageName").value("Bulgarian"))
                .andExpect(jsonPath("$.englishText").isNotEmpty());
        mvc.perform(put("/api/owner/tips/{id}/verdict", bulgarian)
                        .session(sandbox.session())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unhidden").value(true))
                .andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.englishText").isNotEmpty());
        assertThat(jdbc.sql("SELECT stale FROM owner_digest WHERE owner_id = ?")
                        .param(sandbox.ownerId())
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void sampleTipsLandOnTheOwnersLocalDays() {
        Instant now = Instant.parse("2026-10-20T00:20:00Z");
        clock.pin(now);
        UUID owner = data.owner();
        UUID device = data.device(owner, TestData.uniqueSlug(), "TiP");

        samples.seed(owner, device, java.time.ZoneId.of("Asia/Tokyo"));

        assertThat(jdbc.sql("""
                                SELECT count(*) FROM tip
                                WHERE device_id = ? AND (created_at AT TIME ZONE 'Asia/Tokyo')::date = DATE '2026-10-20'""").param(device).query(Long.class).single()).isGreaterThanOrEqualTo(5);
        assertThat(jdbc.sql("SELECT max(created_at) FROM tip WHERE device_id = ?")
                        .param(device)
                        .query(java.sql.Timestamp.class)
                        .single()
                        .toInstant())
                .isBeforeOrEqualTo(now.truncatedTo(ChronoUnit.MICROS));
        assertThat(jdbc.sql("SELECT local_day FROM owner_digest WHERE owner_id = ?")
                        .param(owner)
                        .query(java.time.LocalDate.class)
                        .single())
                .isEqualTo(java.time.LocalDate.of(2026, 10, 20));
    }

    private MockHttpServletResponse rejected(String code) throws Exception {
        return judges.judgeSession(code, "UTC", TestData.uniquePublicIp(), new MockHttpSession());
    }

    private int deviceHandshake(UUID deviceId, String secret) throws Exception {
        try (RawWebSocket socket = RawWebSocket.handshake(
                port,
                "/ws/device/" + deviceId,
                Map.of("Authorization", "Bearer " + secret, "X-Forwarded-For", TestData.uniquePublicIp()))) {
            return socket.status;
        }
    }

    private long demoTips(UUID deviceId) {
        return jdbc.sql("SELECT count(*) FROM tip WHERE device_id = ? AND demo")
                .param(deviceId)
                .query(Long.class)
                .single();
    }

    private long sandboxCount() {
        return jdbc.sql("SELECT count(*) FROM owner WHERE kind = 'SANDBOX'")
                .query(Long.class)
                .single();
    }

    private Map<String, Object> ownerRow(UUID id) {
        return jdbc.sql("SELECT * FROM owner WHERE id = ?").param(id).query().singleRow();
    }

    private static Instant instant(Object timestamp) {
        return ((java.sql.Timestamp) timestamp).toInstant();
    }
}
