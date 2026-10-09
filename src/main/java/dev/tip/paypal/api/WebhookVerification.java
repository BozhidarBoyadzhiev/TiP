package dev.tip.paypal.api;

public enum WebhookVerification {
    /** PayPal confirmed the signature for our webhook id. */
    VERIFIED,
    /** The signature, headers or body are not valid. */
    REJECTED,
    /** PayPal could not be asked. The sender should retry later. */
    UNAVAILABLE
}
