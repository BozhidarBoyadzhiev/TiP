package dev.tip.paypal;

import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.exceptions.ApiException;
import dev.tip.paypal.api.WebhookTransmission;
import dev.tip.paypal.api.WebhookVerification;
import io.apimatic.core.exceptions.AuthValidationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Calls {@code POST /v1/notifications/verify-webhook-signature}, which the SDK does not cover. The raw
 * body is embedded verbatim as {@code webhook_event}, so PayPal checks the exact bytes it signed.
 */
@Component
class WebhookSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureVerifier.class);
    private static final String VERIFY_PATH = "/v1/notifications/verify-webhook-signature";

    private final RestClient restClient;
    private final PaypalServerSdkClient sdkClient;
    private final PayPalAccessTokens tokens;
    private final PayPalProperties properties;
    private final JsonMapper jsonMapper;

    WebhookSignatureVerifier(
            RestClient payPalRestClient,
            PaypalServerSdkClient sdkClient,
            PayPalAccessTokens tokens,
            PayPalProperties properties,
            JsonMapper jsonMapper) {
        this.restClient = payPalRestClient;
        this.sdkClient = sdkClient;
        this.tokens = tokens;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    WebhookVerification verify(WebhookTransmission transmission, String rawBody) {
        if (transmission == null || !transmission.complete() || !isSingleJsonObject(rawBody)) {
            return WebhookVerification.REJECTED;
        }
        String accessToken;
        try {
            accessToken = tokens.accessToken(sdkClient);
        } catch (ApiException | IOException | AuthValidationException e) {
            log.warn(
                    "Could not get a PayPal access token for webhook verification: {}",
                    e.getClass().getSimpleName());
            return WebhookVerification.UNAVAILABLE;
        }
        try {
            return restClient
                    .post()
                    .uri(VERIFY_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .body(requestBody(transmission, rawBody))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401) {
                            tokens.invalidate();
                            log.warn("PayPal rejected the access token during webhook verification");
                            return WebhookVerification.UNAVAILABLE;
                        }
                        if (response.getStatusCode().is5xxServerError() || status == 429) {
                            log.warn("PayPal webhook verification unavailable: status={}", status);
                            return WebhookVerification.UNAVAILABLE;
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            log.warn("PayPal webhook verification refused: status={}", status);
                            return WebhookVerification.REJECTED;
                        }
                        return verificationStatus(
                                new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                    });
        } catch (RestClientException e) {
            log.warn("PayPal webhook verification failed: {}", e.getClass().getSimpleName());
            return WebhookVerification.UNAVAILABLE;
        }
    }

    /**
     * Embedding a string that is not exactly one JSON object would let a sender splice extra fields, such
     * as a second {@code webhook_id}, into the verification request. This only checks the shape; the
     * event is parsed for use after verification succeeds.
     */
    private boolean isSingleJsonObject(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return false;
        }
        try {
            JsonNode node = jsonMapper
                    .readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(rawBody);
            return node != null && node.isObject();
        } catch (JacksonException e) {
            return false;
        }
    }

    private String requestBody(WebhookTransmission transmission, String rawBody) {
        ObjectNode fields = jsonMapper.createObjectNode();
        fields.put("auth_algo", transmission.authAlgo());
        fields.put("cert_url", transmission.certUrl());
        fields.put("transmission_id", transmission.transmissionId());
        fields.put("transmission_sig", transmission.transmissionSig());
        fields.put("transmission_time", transmission.transmissionTime());
        fields.put("webhook_id", properties.webhookId());
        String head = jsonMapper.writeValueAsString(fields);
        return head.substring(0, head.length() - 1) + ",\"webhook_event\":" + rawBody + "}";
    }

    private WebhookVerification verificationStatus(String body) {
        try {
            String status =
                    jsonMapper.readTree(body).path("verification_status").asString("");
            return "SUCCESS".equals(status) ? WebhookVerification.VERIFIED : WebhookVerification.REJECTED;
        } catch (JacksonException e) {
            log.warn("PayPal webhook verification returned an unreadable body");
            return WebhookVerification.UNAVAILABLE;
        }
    }
}
