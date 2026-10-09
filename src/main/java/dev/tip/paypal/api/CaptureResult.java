package dev.tip.paypal.api;

/** What PayPal says about the capture of an order. */
public sealed interface CaptureResult {

    record Completed(String captureId) implements CaptureResult {}

    record Pending(String captureId) implements CaptureResult {}

    /** The payer's funding instrument was declined. The payer can pick another one and retry. */
    record Declined() implements CaptureResult {}

    /** The order was captured before, for example by an earlier attempt whose response was lost. */
    record AlreadyCaptured() implements CaptureResult {}

    record OrderNotFound() implements CaptureResult {}

    /** Any other failure. {@code reason} is safe to log. */
    record Failed(String reason) implements CaptureResult {}
}
