package dev.tip.owners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.shared.LogSafe;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** The access log lines are written, and none holds an IP, a code or a token, only hashed IPs and ids. */
@ExtendWith(OutputCaptureExtension.class)
class AccessLogTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    DailySummaryJob summary;

    @Test
    void everyAccessEventLogsOneLineWithoutPersonalData(CapturedOutput output) throws Exception {
        Judges judges = new Judges(mvc, jdbc);
        String rejectedIp = TestData.uniquePublicIp();
        String limitedIp = TestData.uniquePublicIp();
        String wrongCode = "TIP-ABCDE-FGHJK-MNPQR-STVWX";

        judges.judgeSession(wrongCode, "UTC", rejectedIp, new MockHttpSession());
        for (int i = 0; i < 11; i++) {
            judges.judgeSession("nope", "UTC", limitedIp, new MockHttpSession());
        }
        Judges.Sandbox sandbox = judges.sandbox(JUDGE_CODE, "UTC");
        String url = json.readTree(mvc.perform(post("/api/owner/resume-link")
                                .session(sandbox.session())
                                .with(csrf()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("url")
                .asString();
        String token = url.substring(url.indexOf("?t=") + 3);
        mvc.perform(get("/judges/resume").param("t", token).with(TestData.newClient()));
        judges.demo();
        summary.logSummary();

        String out = output.getOut();
        assertThat(out)
                .contains("code_rejected ip=" + LogSafe.ip(rejectedIp))
                .contains("rate_limited endpoint=judges_session ip=" + LogSafe.ip(limitedIp))
                .contains("sandbox_created label=judges owner=" + sandbox.ownerId())
                .contains("resume_link_used owner=" + sandbox.ownerId())
                .contains("demo_session ip=")
                .containsPattern(
                        "daily_summary sandboxes=\\S*judges:\\d+\\S* tipsPaid=\\S+ aiCalls=\\d+ aiFailures=\\d+");
        for (String secret : List.of(
                rejectedIp,
                limitedIp,
                wrongCode,
                JUDGE_CODE,
                AccessCode.normalise(JUDGE_CODE),
                AccessCode.hashHex(JUDGE_CODE),
                token)) {
            assertThat(out).doesNotContain(secret);
        }
    }
}
