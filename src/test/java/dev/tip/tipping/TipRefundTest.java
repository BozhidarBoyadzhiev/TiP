package dev.tip.tipping;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.TestData;
import dev.tip.WebhookEvents;
import dev.tip.owners.api.OwnerPrincipal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class TipRefundTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestData data;

    OwnerPrincipal owner;
    UUID deviceId;
    String captureId;
    UUID tipId;

    @BeforeEach
    void capturedTipOfAnOwner() {
        UUID ownerId = data.owner();
        owner = new OwnerPrincipal(ownerId, "owner@example.com", "Owner", null);
        deviceId = data.device(ownerId, TestData.uniqueSlug(), "Owner Panda");
        captureId = TestData.uniqueId("CAPTURE");
        tipId = data.tip(deviceId, "CAPTURED", TestData.uniqueId("ORDER"), Instant.now());
        data.setCaptureId(tipId, captureId);
    }

    @Test
    void ownerRefundsTheirCapturedTip() throws Exception {
        PayPalStubs.refund(PAYPAL, captureId, TestData.uniqueId("REFUND"), "COMPLETED");

        refund(tipId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundStatus").value("COMPLETED"));

        assertThat(data.tipStatus(tipId)).isEqualTo("REFUNDED");
        PAYPAL.verify(1, postRequestedFor(urlPathMatching("/v2/payments/captures/.*/refund")));
        PAYPAL.verify(postRequestedFor(urlPathEqualTo(PayPalStubs.refundPath(captureId)))
                .withHeader("PayPal-Request-Id", equalTo("refund-" + tipId))
                .withHeader("Authorization", equalTo("Bearer test-access-token")));
    }

    @Test
    void tipOfAnotherOwnerIsNotFoundAndPayPalIsNotCalled() throws Exception {
        OwnerPrincipal other = new OwnerPrincipal(data.owner(), "other@example.com", "Other", null);

        String otherOwnersTip = refund(tipId, other)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TIP_NOT_FOUND"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unknownTip = refund(UUID.randomUUID(), other)
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(otherOwnersTip.replaceAll("[0-9a-f-]{36}", "ID"))
                .isEqualTo(unknownTip.replaceAll("[0-9a-f-]{36}", "ID"));
        assertThat(data.tipStatus(tipId)).isEqualTo("CAPTURED");
        PAYPAL.verify(0, postRequestedFor(urlPathMatching("/v2/payments/captures/.*/refund")));
    }

    @Test
    void tipThatIsNotCapturedIsNotRefundable() throws Exception {
        UUID created = data.tip(deviceId, "CREATED", TestData.uniqueId("ORDER"), Instant.now());
        UUID refunded = data.tip(deviceId, "REFUNDED", TestData.uniqueId("ORDER"), Instant.now());

        refund(created, owner)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("TIP_NOT_REFUNDABLE"));
        refund(refunded, owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TIP_NOT_REFUNDABLE"));

        PAYPAL.verify(0, postRequestedFor(urlPathMatching("/v2/payments/captures/.*/refund")));
    }

    @Test
    void pendingRefundReturns202AndTheWebhookCompletesIt() throws Exception {
        String refundId = TestData.uniqueId("REFUND");
        PayPalStubs.refund(PAYPAL, captureId, refundId, "PENDING");
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");

        refund(tipId, owner)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.refundStatus").value("PENDING"));
        assertThat(data.tipStatus(tipId)).isEqualTo("CAPTURED");

        mvc.perform(MockMvcRequestBuilders.post("/webhooks/paypal")
                        .headers(WebhookEvents.transmissionHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WebhookEvents.refund(TestData.uniqueId("WH"), refundId, captureId, null)))
                .andExpect(status().isOk());

        assertThat(data.tipStatus(tipId)).isEqualTo("REFUNDED");
    }

    @Test
    void captureAlreadyRefundedAtPayPalCompletesTheRefund() throws Exception {
        PayPalStubs.refundError(PAYPAL, captureId, 422, "UNPROCESSABLE_ENTITY", "CAPTURE_FULLY_REFUNDED");

        refund(tipId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        assertThat(data.tipStatus(tipId)).isEqualTo("REFUNDED");
    }

    @Test
    void payPalFailureReturns502AndKeepsTheTipCaptured() throws Exception {
        PayPalStubs.refundError(PAYPAL, captureId, 500, "INTERNAL_SERVER_ERROR", "INTERNAL_SERVICE_ERROR");

        refund(tipId, owner)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYPAL_REFUND_FAILED"));

        assertThat(data.tipStatus(tipId)).isEqualTo("CAPTURED");
    }

    @Test
    void signedInOwnerPostWithoutCsrfTokenIs403() throws Exception {
        mvc.perform(post("/api/owner/tips/{tipId}/refund", tipId).with(user(owner)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        assertThat(data.tipStatus(tipId)).isEqualTo("CAPTURED");
        PAYPAL.verify(0, postRequestedFor(urlPathMatching("/v2/payments/captures/.*/refund")));
    }

    private ResultActions refund(UUID id, OwnerPrincipal as) throws Exception {
        return mvc.perform(
                post("/api/owner/tips/{tipId}/refund", id).with(user(as)).with(csrf()));
    }
}
