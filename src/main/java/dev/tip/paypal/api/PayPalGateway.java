package dev.tip.paypal.api;

import java.util.UUID;

/** The PayPal calls TiP needs. Every PayPal failure comes back as a typed result, never as an exception. */
public interface PayPalGateway {

    /** Creates a CAPTURE order for one tip. Sends {@code PayPal-Request-Id: create-<tipId>}. */
    CreateOrderResult createOrder(CreateOrderCommand command);

    /** Captures an approved order. Sends {@code PayPal-Request-Id: capture-<tipId>}. */
    CaptureResult captureOrder(UUID tipId, String orderId);

    /** Reads an order and reports the state of its capture, used to reconcile an already captured order. */
    CaptureResult lookupOrder(String orderId);

    /** Fully refunds a capture. Sends {@code PayPal-Request-Id: refund-<tipId>}. */
    RefundResult refundCapture(UUID tipId, String captureId);

    /** Verifies a webhook signature against the exact raw body PayPal sent. */
    WebhookVerification verifyWebhook(WebhookTransmission transmission, String rawBody);
}
