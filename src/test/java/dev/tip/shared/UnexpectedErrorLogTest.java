package dev.tip.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.TestData;
import dev.tip.WebhookEvents;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** A database error can carry the row's values in its message, so it is logged by class and place only. */
@ExtendWith(OutputCaptureExtension.class)
class UnexpectedErrorLogTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestData data;

    @Test
    void aDatabaseErrorIsLoggedWithoutItsMessage(CapturedOutput output) throws Exception {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        UUID deviceId = data.device();
        String orderId = TestData.uniqueId("ORDER");
        data.tip(deviceId, "CREATED", orderId, Instant.now());
        String captureId = TestData.uniqueId("CAPTURE");
        UUID other = data.tip(deviceId, "CAPTURED", TestData.uniqueId("ORDER"), Instant.now());
        data.setCaptureId(other, captureId);

        mvc.perform(post("/webhooks/paypal")
                        .headers(WebhookEvents.transmissionHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WebhookEvents.capture(
                                TestData.uniqueId("WH"), "PAYMENT.CAPTURE.COMPLETED", captureId, null, orderId))
                        .with(TestData.newClient()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(output.getOut()).contains("Unexpected database error: ").doesNotContain("Key (", captureId);
    }

    /** U+0082 is how the middle byte of a UTF-8 euro sign reads in a header, which Tomcat passes through. */
    @Test
    void aHeaderValueTheFirewallRefusesIs400AndNotLogged(CapturedOutput output) throws Exception {
        String value = "transmission-" + (char) 0x82 + "-" + UUID.randomUUID();

        mvc.perform(post("/webhooks/paypal")
                        .headers(WebhookEvents.transmissionHeaders())
                        .header("PAYPAL-TRANSMISSION-ID", value)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(TestData.newClient()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertThat(output.getOut())
                .contains("Rejected a request with a header or parameter the firewall refuses")
                .doesNotContain(value.substring(value.lastIndexOf('-') + 1));
    }

    @Test
    void describeNamesTheClassesAndTheFirstFrameInTipsCode() {
        DataIntegrityViolationException error =
                new DataIntegrityViolationException("Key (id)=(secret) exists", new SQLException("Key (id)=(secret)"));

        assertThat(LogSafe.isDataError(error)).isTrue();
        assertThat(LogSafe.isDataError(new IllegalStateException("plain"))).isFalse();
        assertThat(LogSafe.describe(error))
                .startsWith("DataIntegrityViolationException <- SQLException at dev.tip.shared.UnexpectedErrorLogTest.")
                .doesNotContain("secret");
    }
}
