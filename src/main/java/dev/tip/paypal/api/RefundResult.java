package dev.tip.paypal.api;

/** What PayPal says about a refund of a capture. */
public sealed interface RefundResult {

    record Completed(String refundId) implements RefundResult {}

    /** PayPal accepted the refund but has not finished it. {@code PAYMENT.CAPTURE.REFUNDED} follows. */
    record Pending(String refundId) implements RefundResult {}

    /** The capture was refunded before, for example by an earlier attempt whose response was lost. */
    record AlreadyRefunded() implements RefundResult {}

    /** PayPal refused or could not be reached. {@code reason} is safe to log. */
    record Failed(String reason) implements RefundResult {}
}
