package dev.tip;

import java.util.UUID;
import org.springframework.http.HttpHeaders;

/** Builds PayPal webhook bodies and transmission headers for tests. */
public final class WebhookEvents {

    private WebhookEvents() {}

    public static HttpHeaders transmissionHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("PAYPAL-AUTH-ALGO", "SHA256withRSA");
        headers.add("PAYPAL-CERT-URL", "https://api.sandbox.paypal.com/v1/notifications/certs/CERT-TEST");
        headers.add("PAYPAL-TRANSMISSION-ID", UUID.randomUUID().toString());
        headers.add("PAYPAL-TRANSMISSION-SIG", "dGVzdC1zaWduYXR1cmU=");
        headers.add("PAYPAL-TRANSMISSION-TIME", "2026-10-09T10:00:00Z");
        return headers;
    }

    /** A capture event. {@code customId} and {@code orderId} may be null to test the fallbacks. */
    public static String capture(String eventId, String eventType, String captureId, String customId, String orderId) {
        String custom = customId == null ? "" : ",\"custom_id\":\"" + customId + "\"";
        String related =
                orderId == null ? "" : ",\"supplementary_data\":{\"related_ids\":{\"order_id\":\"" + orderId + "\"}}";
        return "{\"id\":\"" + eventId + "\",\"event_version\":\"1.0\",\"create_time\":\"2026-10-09T10:00:00Z\","
                + "\"resource_type\":\"capture\",\"event_type\":\"" + eventType + "\","
                + "\"summary\":\"Payment capture event\",\"resource\":{\"id\":\"" + captureId + "\","
                + "\"status\":\"COMPLETED\",\"amount\":{\"currency_code\":\"GBP\",\"value\":\"2.00\"}"
                + custom + related + "}}";
    }

    /** A refund event whose up link points at the refunded capture. */
    public static String refund(String eventId, String refundId, String captureId, String customId) {
        String custom = customId == null ? "" : ",\"custom_id\":\"" + customId + "\"";
        return "{\"id\":\"" + eventId + "\",\"event_version\":\"1.0\",\"resource_type\":\"refund\","
                + "\"event_type\":\"PAYMENT.CAPTURE.REFUNDED\",\"resource\":{\"id\":\"" + refundId + "\","
                + "\"status\":\"COMPLETED\",\"amount\":{\"currency_code\":\"GBP\",\"value\":\"2.00\"}" + custom
                + ",\"links\":[{\"href\":\"https://api.sandbox.paypal.com/v2/payments/refunds/" + refundId
                + "\",\"rel\":\"self\",\"method\":\"GET\"},{\"href\":\"https://api.sandbox.paypal.com/v2/payments/captures/"
                + captureId + "\",\"rel\":\"up\",\"method\":\"GET\"}]}}";
    }
}
