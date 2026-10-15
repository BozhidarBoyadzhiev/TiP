package dev.tip.tipping;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The parts of a verified PayPal webhook that TiP uses. For capture events the resource is the capture;
 * for {@code PAYMENT.CAPTURE.REFUNDED} it is the refund, whose {@code up} link points at the capture. The
 * resource's {@code custom_id} is not read: whoever creates an order chooses it, and anyone can create an
 * order on our merchant account with our public client id.
 */
record PayPalWebhookEvent(
        String eventId,
        String eventType,
        String resourceId,
        String relatedOrderId,
        String refundedCaptureId,
        String amountValue,
        String amountCurrency) {

    static final String CAPTURE_COMPLETED = "PAYMENT.CAPTURE.COMPLETED";
    static final String CAPTURE_PENDING = "PAYMENT.CAPTURE.PENDING";
    static final String CAPTURE_DENIED = "PAYMENT.CAPTURE.DENIED";
    static final String CAPTURE_DECLINED = "PAYMENT.CAPTURE.DECLINED";
    static final String CAPTURE_REFUNDED = "PAYMENT.CAPTURE.REFUNDED";
    static final String CAPTURE_REVERSED = "PAYMENT.CAPTURE.REVERSED";

    private static final String CAPTURES_PATH = "/captures/";

    /** Parses a body PayPal has already verified. Returns empty when the required fields are missing. */
    static Optional<PayPalWebhookEvent> parse(JsonMapper jsonMapper, String rawBody) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(rawBody);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        String eventId = text(root, "id");
        String eventType = text(root, "event_type");
        if (eventId == null || eventType == null || eventId.length() > 64 || eventType.length() > 100) {
            return Optional.empty();
        }
        JsonNode resource = root.path("resource");
        String resourceId = text(resource, "id");
        return Optional.of(new PayPalWebhookEvent(
                eventId,
                eventType,
                resourceId != null && resourceId.length() <= 64 ? resourceId : null,
                text(resource.path("supplementary_data").path("related_ids"), "order_id"),
                refundedCaptureId(resource),
                text(resource.path("amount"), "value"),
                text(resource.path("amount"), "currency_code")));
    }

    /**
     * True when the resource's amount is exactly {@code amountMinor} of {@code currency}. PayPal gives the
     * amount as a decimal string in major units, for example "2.50" for 250 GBP minor units.
     */
    boolean amountEquals(long amountMinor, String currency) {
        if (amountValue == null || !currency.equals(amountCurrency)) {
            return false;
        }
        try {
            int digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
            return new BigDecimal(amountValue).compareTo(BigDecimal.valueOf(amountMinor, digits)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String refundedCaptureId(JsonNode resource) {
        for (JsonNode link : resource.path("links")) {
            String href = text(link, "href");
            if ("up".equals(text(link, "rel")) && href != null && href.contains(CAPTURES_PATH)) {
                String id = href.substring(href.lastIndexOf(CAPTURES_PATH) + CAPTURES_PATH.length());
                return id.isEmpty() || id.contains("/") ? null : id;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
}
