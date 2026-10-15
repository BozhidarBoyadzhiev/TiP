package dev.tip.paypal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.paypal.api.CaptureResult;
import dev.tip.paypal.api.CreateOrderCommand;
import dev.tip.paypal.api.CreateOrderResult;
import dev.tip.paypal.api.PayPalGateway;
import dev.tip.paypal.api.WebhookTransmission;
import dev.tip.paypal.api.WebhookVerification;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PayPalGatewayTest extends IntegrationTest {

    private static final WebhookTransmission TRANSMISSION = new WebhookTransmission(
            "SHA256withRSA",
            "https://api.sandbox.paypal.com/v1/notifications/certs/CERT-1",
            "tx-1",
            "c2lnbmF0dXJl",
            "2026-10-09T10:00:00Z");

    @Autowired
    PayPalGateway gateway;

    @Test
    void createOrderSendsOneCaptureUnitForTheTip() {
        UUID tipId = UUID.randomUUID();
        PayPalStubs.createOrder(PAYPAL, "ORDER-1");

        CreateOrderResult result =
                gateway.createOrder(new CreateOrderCommand(tipId, 250, "GBP", "Tip for Busker Panda"));

        assertThat(result).isEqualTo(new CreateOrderResult.Created("ORDER-1"));
        PAYPAL.verify(postRequestedFor(urlPathEqualTo(PayPalStubs.CREATE_ORDER_PATH))
                .withHeader("PayPal-Request-Id", equalTo("create-" + tipId))
                .withHeader("Authorization", equalTo("Bearer test-access-token"))
                .withRequestBody(matchingJsonPath("$.intent", equalTo("CAPTURE")))
                .withRequestBody(matchingJsonPath("$.purchase_units.length()", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].custom_id", equalTo(tipId.toString())))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].description", equalTo("Tip for Busker Panda")))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].amount.currency_code", equalTo("GBP")))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].amount.value", equalTo("2.50")))
                .withRequestBody(matchingJsonPath(
                        "$.payment_source.paypal.experience_context.shipping_preference", equalTo("NO_SHIPPING")))
                .withRequestBody(matchingJsonPath(
                        "$.payment_source.paypal.experience_context.user_action", equalTo("PAY_NOW"))));
    }

    @Test
    void createOrderReportsPayPalErrorsAsFailed() {
        PayPalStubs.createOrderFails(PAYPAL, 500);

        CreateOrderResult result =
                gateway.createOrder(new CreateOrderCommand(UUID.randomUUID(), 100, "GBP", "Tip for Panda"));

        assertThat(result).isInstanceOf(CreateOrderResult.Failed.class);
    }

    @Test
    void captureSendsIdempotencyKeyAndMapsCompleted() {
        UUID tipId = UUID.randomUUID();
        PayPalStubs.captureCompleted(PAYPAL, "ORDER-2", "CAPTURE-2");

        CaptureResult result = gateway.captureOrder(tipId, "ORDER-2");

        assertThat(result).isEqualTo(new CaptureResult.Completed("CAPTURE-2", "2.00", "GBP"));
        PAYPAL.verify(postRequestedFor(urlPathEqualTo(PayPalStubs.capturePath("ORDER-2")))
                .withHeader("PayPal-Request-Id", equalTo("capture-" + tipId)));
    }

    @Test
    void captureMapsPendingCapture() {
        PayPalStubs.capturePending(PAYPAL, "ORDER-3", "CAPTURE-3");

        assertThat(gateway.captureOrder(UUID.randomUUID(), "ORDER-3"))
                .isEqualTo(new CaptureResult.Pending("CAPTURE-3", "2.00", "GBP"));
    }

    @Test
    void captureMapsPayPalErrorsToTypedResults() {
        PayPalStubs.captureError(PAYPAL, "DECLINED", 422, "UNPROCESSABLE_ENTITY", "INSTRUMENT_DECLINED");
        PayPalStubs.captureError(PAYPAL, "DONE", 422, "UNPROCESSABLE_ENTITY", "ORDER_ALREADY_CAPTURED");
        PayPalStubs.captureError(PAYPAL, "MISSING", 404, "RESOURCE_NOT_FOUND", "INVALID_RESOURCE_ID");
        PayPalStubs.captureError(PAYPAL, "BROKEN", 500, "INTERNAL_SERVER_ERROR", "INTERNAL_SERVICE_ERROR");

        assertThat(gateway.captureOrder(UUID.randomUUID(), "DECLINED")).isEqualTo(new CaptureResult.Declined());
        assertThat(gateway.captureOrder(UUID.randomUUID(), "DONE")).isEqualTo(new CaptureResult.AlreadyCaptured());
        assertThat(gateway.captureOrder(UUID.randomUUID(), "MISSING")).isEqualTo(new CaptureResult.OrderNotFound());
        assertThat(gateway.captureOrder(UUID.randomUUID(), "BROKEN")).isInstanceOf(CaptureResult.Failed.class);
    }

    @Test
    void lookupOrderReportsTheCaptureState() {
        PayPalStubs.getOrder(PAYPAL, "ORDER-4", "CAPTURE-4", "COMPLETED");

        assertThat(gateway.lookupOrder("ORDER-4")).isEqualTo(new CaptureResult.Completed("CAPTURE-4", "2.00", "GBP"));
    }

    @Test
    void verifyWebhookSendsTheRawBodyVerbatimWithOurWebhookId() {
        PayPalStubs.verifyWebhook(PAYPAL, "SUCCESS");
        String rawBody = "{\"id\":\"WH-1\",  \"event_type\":\"PAYMENT.CAPTURE.COMPLETED\",\"resource\":{\"id\":\"C\"}}";

        WebhookVerification result = gateway.verifyWebhook(TRANSMISSION, rawBody);

        assertThat(result).isEqualTo(WebhookVerification.VERIFIED);
        PAYPAL.verify(postRequestedFor(urlPathEqualTo(PayPalStubs.VERIFY_WEBHOOK_PATH))
                .withHeader("Authorization", equalTo("Bearer test-access-token"))
                .withRequestBody(containing("\"webhook_event\":" + rawBody + "}"))
                .withRequestBody(equalToJson("""
                        {"auth_algo":"SHA256withRSA",
                         "cert_url":"https://api.sandbox.paypal.com/v1/notifications/certs/CERT-1",
                         "transmission_id":"tx-1","transmission_sig":"c2lnbmF0dXJl",
                         "transmission_time":"2026-10-09T10:00:00Z","webhook_id":"TESTWEBHOOKID",
                         "webhook_event":{"id":"WH-1","event_type":"PAYMENT.CAPTURE.COMPLETED","resource":{"id":"C"}}}
                        """)));
    }

    @Test
    void verifyWebhookRejectsFailedSignature() {
        PayPalStubs.verifyWebhook(PAYPAL, "FAILURE");

        assertThat(gateway.verifyWebhook(TRANSMISSION, "{\"id\":\"WH-2\"}")).isEqualTo(WebhookVerification.REJECTED);
    }

    @Test
    void verifyWebhookRejectsMissingHeadersWithoutCallingPayPal() {
        WebhookTransmission missingSignature =
                new WebhookTransmission("SHA256withRSA", "https://cert", "tx-1", null, "2026-10-09T10:00:00Z");

        assertThat(gateway.verifyWebhook(missingSignature, "{\"id\":\"WH-3\"}"))
                .isEqualTo(WebhookVerification.REJECTED);
        PAYPAL.verify(0, postRequestedFor(urlPathEqualTo(PayPalStubs.VERIFY_WEBHOOK_PATH)));
    }

    @Test
    void verifyWebhookRejectsBodiesThatAreNotOneJsonObject() {
        assertThat(gateway.verifyWebhook(TRANSMISSION, "{\"id\":\"WH-4\"},\"webhook_id\":\"ATTACKER\""))
                .isEqualTo(WebhookVerification.REJECTED);
        assertThat(gateway.verifyWebhook(TRANSMISSION, "[1,2]")).isEqualTo(WebhookVerification.REJECTED);
        assertThat(gateway.verifyWebhook(TRANSMISSION, "not json")).isEqualTo(WebhookVerification.REJECTED);
        PAYPAL.verify(0, postRequestedFor(urlPathEqualTo(PayPalStubs.VERIFY_WEBHOOK_PATH)));
    }

    @Test
    void verifyWebhookReportsPayPalOutageAsUnavailable() {
        PAYPAL.stubFor(post(urlPathEqualTo(PayPalStubs.VERIFY_WEBHOOK_PATH))
                .willReturn(aResponse().withStatus(503)));

        assertThat(gateway.verifyWebhook(TRANSMISSION, "{\"id\":\"WH-5\"}")).isEqualTo(WebhookVerification.UNAVAILABLE);
    }
}
