package dev.tip;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;

/** WireMock stubs shaped like PayPal sandbox responses. */
public final class PayPalStubs {

    public static final String CREATE_ORDER_PATH = "/v2/checkout/orders";
    public static final String VERIFY_WEBHOOK_PATH = "/v1/notifications/verify-webhook-signature";

    private PayPalStubs() {}

    public static String refundPath(String captureId) {
        return "/v2/payments/captures/" + captureId + "/refund";
    }

    public static void refund(WireMockServer server, String captureId, String refundId, String status) {
        server.stubFor(post(urlPathEqualTo(refundPath(captureId))).willReturn(json(201, refundBody(refundId, status))));
    }

    public static void refundWithJitter(
            WireMockServer server, String captureId, String refundId, String status, int maxDelayMillis) {
        server.stubFor(post(urlPathEqualTo(refundPath(captureId)))
                .willReturn(json(201, refundBody(refundId, status)).withUniformRandomDelay(0, maxDelayMillis)));
    }

    public static void refundError(WireMockServer server, String captureId, int status, String name, String issue) {
        server.stubFor(post(urlPathEqualTo(refundPath(captureId))).willReturn(json(status, error(name, issue))));
    }

    public static String capturePath(String orderId) {
        return "/v2/checkout/orders/" + orderId + "/capture";
    }

    public static void token(WireMockServer server) {
        server.stubFor(post(urlPathEqualTo("/v1/oauth2/token")).willReturn(json(200, """
                        {"scope":"https://uri.paypal.com/services/payments/payment","access_token":"test-access-token",
                         "token_type":"Bearer","app_id":"APP-TEST","expires_in":32400,"nonce":"n"}
                        """)));
    }

    public static void createOrder(WireMockServer server, String orderId) {
        server.stubFor(post(urlPathEqualTo(CREATE_ORDER_PATH)).willReturn(json(200, """
                        {"id":"%s","status":"PAYER_ACTION_REQUIRED","links":[]}
                        """.formatted(orderId))));
    }

    public static void createOrderFails(WireMockServer server, int status) {
        server.stubFor(post(urlPathEqualTo(CREATE_ORDER_PATH)).willReturn(json(status, """
                        {"name":"INTERNAL_SERVER_ERROR","message":"An internal server error occurred.","debug_id":"dbg1"}
                        """)));
    }

    /** A completed capture of 2.00 GBP, the amount of {@link TestData#tip}. */
    public static void captureCompleted(WireMockServer server, String orderId, String captureId) {
        captureCompleted(server, orderId, captureId, "2.00", "GBP");
    }

    public static void captureCompleted(
            WireMockServer server, String orderId, String captureId, String amountValue, String currency) {
        server.stubFor(post(urlPathEqualTo(capturePath(orderId)))
                .willReturn(json(201, order(orderId, "COMPLETED", captureId, "COMPLETED", amountValue, currency))));
    }

    /** A completed capture of 2.00 GBP whose answer waits for the test, see {@link HeldResponses}. */
    public static void captureCompletedHeld(WireMockServer server, String orderId, String captureId) {
        server.stubFor(post(urlPathEqualTo(capturePath(orderId)))
                .willReturn(json(201, order(orderId, "COMPLETED", captureId, "COMPLETED", "2.00", "GBP"))
                        .withTransformers(HeldResponses.NAME)));
    }

    /** A pending capture of 2.00 GBP. */
    public static void capturePending(WireMockServer server, String orderId, String captureId) {
        capturePending(server, orderId, captureId, "2.00", "GBP");
    }

    public static void capturePending(
            WireMockServer server, String orderId, String captureId, String amountValue, String currency) {
        server.stubFor(post(urlPathEqualTo(capturePath(orderId)))
                .willReturn(json(201, order(orderId, "COMPLETED", captureId, "PENDING", amountValue, currency))));
    }

    public static void captureError(WireMockServer server, String orderId, int status, String name, String issue) {
        server.stubFor(post(urlPathEqualTo(capturePath(orderId))).willReturn(json(status, error(name, issue))));
    }

    /** A capture error whose answer waits for the test, see {@link HeldResponses}. */
    public static void captureErrorHeld(WireMockServer server, String orderId, int status, String name, String issue) {
        server.stubFor(post(urlPathEqualTo(capturePath(orderId)))
                .willReturn(json(status, error(name, issue)).withTransformers(HeldResponses.NAME)));
    }

    /** The order with one capture of 2.00 GBP in {@code captureStatus}. */
    public static void getOrder(WireMockServer server, String orderId, String captureId, String captureStatus) {
        getOrder(server, orderId, captureId, captureStatus, "2.00", "GBP");
    }

    public static void getOrder(
            WireMockServer server,
            String orderId,
            String captureId,
            String captureStatus,
            String amountValue,
            String currency) {
        server.stubFor(get(urlPathEqualTo("/v2/checkout/orders/" + orderId))
                .willReturn(json(200, order(orderId, "COMPLETED", captureId, captureStatus, amountValue, currency))));
    }

    public static void getOrderError(WireMockServer server, String orderId, int status, String name, String issue) {
        server.stubFor(
                get(urlPathEqualTo("/v2/checkout/orders/" + orderId)).willReturn(json(status, error(name, issue))));
    }

    /** Like {@link #captureCompleted} with a random response delay, so racing callers finish in any order. */
    public static void captureCompletedWithJitter(
            WireMockServer server, String orderId, String captureId, int maxDelayMillis) {
        server.stubFor(post(urlPathEqualTo(capturePath(orderId)))
                .willReturn(json(201, order(orderId, "COMPLETED", captureId, "COMPLETED", "2.00", "GBP"))
                        .withUniformRandomDelay(0, maxDelayMillis)));
    }

    public static void verifyWebhookWithJitter(WireMockServer server, int maxDelayMillis) {
        server.stubFor(post(urlPathEqualTo(VERIFY_WEBHOOK_PATH))
                .willReturn(
                        json(200, "{\"verification_status\":\"SUCCESS\"}").withUniformRandomDelay(0, maxDelayMillis)));
    }

    public static void verifyWebhook(WireMockServer server, String verificationStatus) {
        server.stubFor(post(urlPathEqualTo(VERIFY_WEBHOOK_PATH))
                .willReturn(json(200, "{\"verification_status\":\"" + verificationStatus + "\"}")));
    }

    public static String error(String name, String issue) {
        return """
                {"name":"%s","details":[{"issue":"%s","description":"stubbed"}],
                 "message":"stubbed","debug_id":"dbg1","links":[]}
                """.formatted(name, issue);
    }

    private static String refundBody(String refundId, String status) {
        return """
                {"id":"%s","status":"%s","amount":{"currency_code":"GBP","value":"2.00"},"links":[]}
                """.formatted(refundId, status);
    }

    private static String order(
            String orderId,
            String orderStatus,
            String captureId,
            String captureStatus,
            String amountValue,
            String currency) {
        return """
                {"id":"%s","status":"%s","purchase_units":[{"reference_id":"default",
                  "payments":{"captures":[{"id":"%s","status":"%s",
                    "amount":{"currency_code":"%s","value":"%s"},"final_capture":true}]}}],
                 "links":[]}
                """.formatted(orderId, orderStatus, captureId, captureStatus, currency, amountValue);
    }

    public static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
