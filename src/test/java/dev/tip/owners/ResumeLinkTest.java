package dev.tip.owners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.owners.api.OwnerPrincipal;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** "Continue on another device": a one time link, valid 10 minutes, bound to its sandbox. */
class ResumeLinkTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-21T09:00:00Z");

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
    void theLinkSignsAnotherBrowserInOnceAndTheTokenLeavesTheAddressBar() throws Exception {
        String url = link();
        String token = url.substring(url.indexOf("?t=") + 3);
        assertThat(url).startsWith("http://localhost:8080/judges/resume?t=");
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(16);
        assertThat(jdbc.sql("SELECT token_hash FROM resume_token WHERE owner_id = ?")
                        .param(sandbox.ownerId())
                        .query(byte[].class)
                        .single())
                .isEqualTo(MessageDigest.getInstance("SHA-256")
                        .digest(Base64.getUrlDecoder().decode(token)));

        MockHttpSession other = new MockHttpSession();
        mvc.perform(get("/judges/resume").param("t", token).session(other).with(TestData.newClient()))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/owner"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        assertThat(Judges.principal(other).id()).isEqualTo(sandbox.ownerId());
        mvc.perform(get("/api/owner/me").session(other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Judge"));

        MockHttpServletResponse second = resume(token);
        MockHttpServletResponse garbage = resume("not-a-token");
        MockHttpServletResponse missing = mvc.perform(get("/judges/resume").with(TestData.newClient()))
                .andReturn()
                .getResponse();
        for (MockHttpServletResponse failed : List.of(second, garbage, missing)) {
            assertFailed(failed);
        }
    }

    @Test
    void anExpiredLinkFailsLikeAnUnknownOne() throws Exception {
        String token = token(link());

        clock.pin(NOW.plus(Duration.ofMinutes(10)));

        assertFailed(resume(token));
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
                .digest(Base64.getUrlDecoder().decode(token(link())));
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
    void onlyASandboxCanMakeALink() throws Exception {
        String email = TestData.uniqueEmail();
        OwnerPrincipal real =
                new OwnerPrincipal(data.owner(email, "Real", TestData.OWNER_PASSWORD), email, "Real", null);

        mvc.perform(post("/api/owner/resume-link").with(user(real)).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SANDBOX_ONLY"));
        mvc.perform(post("/api/owner/resume-link").session(sandbox.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void resumesAreRateLimitedPerIp() throws Exception {
        String ip = TestData.uniquePublicIp();
        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/judges/resume").param("t", "x").with(Judges.remoteAddr(ip)))
                    .andExpect(status().isSeeOther());
        }

        mvc.perform(get("/judges/resume").param("t", "x").with(Judges.remoteAddr(ip)))
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

    private static String token(String url) {
        return url.substring(url.indexOf("?t=") + 3);
    }

    private MockHttpServletResponse resume(String token) throws Exception {
        return mvc.perform(get("/judges/resume").param("t", token).with(TestData.newClient()))
                .andReturn()
                .getResponse();
    }

    private static void assertFailed(MockHttpServletResponse response) {
        assertThat(response.getStatus()).isEqualTo(303);
        assertThat(response.getHeader("Location")).isEqualTo("/owner/login?resumeFailed");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }
}
