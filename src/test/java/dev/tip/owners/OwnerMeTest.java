package dev.tip.owners;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.owners.api.OwnerPrincipal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** {@code /api/owner/me} tells the page which kind of owner it shows: its kind, whether it is read only and its expiry. */
class OwnerMeTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-22T13:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestData data;

    @Test
    void aRealOwnerIsAnOwnerThatCanWriteAndNeverExpires() throws Exception {
        String email = TestData.uniqueEmail();
        OwnerPrincipal real =
                new OwnerPrincipal(data.owner(email, "Real", TestData.OWNER_PASSWORD), email, "Real", null);

        mvc.perform(get("/api/owner/me").with(user(real)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Real"))
                .andExpect(jsonPath("$.kind").value("OWNER"))
                .andExpect(jsonPath("$.readOnly").value(false))
                .andExpect(jsonPath("$.expiresAt").isEmpty())
                .andExpect(jsonPath("$.aiResting").value(false))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void aSandboxSaysWhenItCloses() throws Exception {
        clock.pin(NOW);
        Judges.Sandbox sandbox = new Judges(mvc, jdbc).sandbox(JUDGE_CODE, "Europe/Sofia");

        mvc.perform(get("/api/owner/me").session(sandbox.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Judge"))
                .andExpect(jsonPath("$.timeZone").value("Europe/Sofia"))
                .andExpect(jsonPath("$.kind").value("SANDBOX"))
                .andExpect(jsonPath("$.readOnly").value(false))
                .andExpect(jsonPath("$.expiresAt")
                        .value(NOW.plus(Duration.ofHours(72)).toString()));
    }

    @Test
    void thePublicDemoIsReadOnlyAndNeverExpires() throws Exception {
        mvc.perform(get("/api/owner/me").session(new Judges(mvc, jdbc).demo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("PUBLIC_DEMO"))
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.expiresAt").isEmpty());
    }
}
