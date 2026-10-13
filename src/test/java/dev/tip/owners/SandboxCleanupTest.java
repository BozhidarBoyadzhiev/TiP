package dev.tip.owners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.TestData;
import dev.tip.WebhookEvents;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** The hourly job deletes expired sandboxes and everything under them, in batches, and leaves the rest alone. */
@ExtendWith(OutputCaptureExtension.class)
class SandboxCleanupTest extends IntegrationTest {

    private static final Instant START = Instant.parse("2026-11-02T09:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestData data;

    @Autowired
    SandboxCleanupJob job;

    Judges judges;

    @BeforeEach
    void judges() {
        judges = new Judges(mvc, jdbc);
    }

    @Test
    void anExpiredSandboxGoesWithEverythingUnderItAndOthersStay(CapturedOutput output) throws Exception {
        clock.pin(START);
        Judges.Sandbox expiring = judges.sandbox(JUDGE_CODE, "UTC");
        UUID paidTip = data.tip(expiring.deviceId(), "CAPTURED", TestData.uniqueId("ORDER"), START);
        data.setCaptureId(paidTip, TestData.uniqueId("CAPTURE"));
        jdbc.sql("INSERT INTO reaction_delivery (tip_id, device_id, reaction, reacted_at) VALUES (?, ?, 'WARM', now())")
                .params(paidTip, expiring.deviceId())
                .update();
        mvc.perform(post("/api/owner/resume-link").session(expiring.session()).with(csrf()))
                .andExpect(status().isOk());
        clock.pin(START.plus(Duration.ofHours(1)));
        Judges.Sandbox staying = judges.sandbox(JUDGE_CODE, "UTC");
        UUID realOwner = data.owner();
        UUID realDevice = data.device(realOwner, TestData.uniqueSlug(), "Real Panda");
        UUID realTip = data.tip(realDevice, "CAPTURED", TestData.uniqueId("ORDER"), START);

        clock.pin(START.plus(Duration.ofHours(72)));
        job.deleteExpired();

        assertThat(count("SELECT count(*) FROM owner WHERE id = ?", expiring.ownerId()))
                .isZero();
        assertThat(count("SELECT count(*) FROM device WHERE owner_id = ?", expiring.ownerId()))
                .isZero();
        assertThat(count("SELECT count(*) FROM tip WHERE device_id = ?", expiring.deviceId()))
                .isZero();
        assertThat(count("SELECT count(*) FROM reaction_delivery WHERE device_id = ?", expiring.deviceId()))
                .isZero();
        assertThat(count("SELECT count(*) FROM owner_digest WHERE owner_id = ?", expiring.ownerId()))
                .isZero();
        assertThat(count("SELECT count(*) FROM resume_token WHERE owner_id = ?", expiring.ownerId()))
                .isZero();
        assertThat(count("SELECT count(*) FROM tip WHERE device_id = ?", staying.deviceId()))
                .isPositive();
        assertThat(data.tipStatus(realTip)).isEqualTo("CAPTURED");
        assertThat(output.getOut())
                .contains("sandbox_expired owner=" + expiring.ownerId() + " label=judges")
                .doesNotContain("sandbox_expired owner=" + staying.ownerId());

        String eventId = TestData.uniqueId("WH");
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        mvc.perform(post("/webhooks/paypal")
                        .headers(WebhookEvents.transmissionHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WebhookEvents.capture(
                                eventId,
                                "PAYMENT.CAPTURE.COMPLETED",
                                TestData.uniqueId("CAPTURE"),
                                paidTip.toString(),
                                TestData.uniqueId("ORDER"))))
                .andExpect(status().isOk());
        assertThat(data.webhookEvents(eventId)).isOne();
        mvc.perform(post("/api/public/tips/{id}/capture", paidTip).with(Judges.remoteAddr(TestData.uniquePublicIp())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TIP_NOT_FOUND"));
    }

    @Test
    void moreExpiredSandboxesThanOneBatchAreAllDeletedInOneRun() throws Exception {
        clock.pin(START.minus(Duration.ofDays(10)));
        List<UUID> owners = new ArrayList<>();
        for (int i = 0; i < SandboxCleanupJob.BATCH_SIZE + 3; i++) {
            owners.add(judges.sandbox(JUDGE_CODE, "UTC").ownerId());
        }

        clock.pin(START);
        job.deleteExpired();

        assertThat(owners)
                .allSatisfy(id -> assertThat(count("SELECT count(*) FROM owner WHERE id = ?", id))
                        .isZero());
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param(id).query(Long.class).single();
    }
}
