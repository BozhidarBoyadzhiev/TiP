package dev.tip.paypal.api;

/** What PayPal says about the capture of an order. */
public sealed interface CaptureResult {

    /**
     * A capture PayPal reports, with the amount it captured as PayPal wrote it ({@code amountValue} in major units,
     * either may be null when PayPal left it out).
     */
    sealed interface Captured extends CaptureResult {

        String captureId();

        String amountValue();

        String currency();

        /** True when the capture is exactly {@code amountMinor} of {@code currency}. */
        default boolean charges(long amountMinor, String currency) {
            return PayPalAmounts.equal(amountValue(), currency(), amountMinor, currency);
        }
    }

    record Completed(String captureId, String amountValue, String currency) implements Captured {}

    record Pending(String captureId, String amountValue, String currency) implements Captured {}

    /** The payer's funding instrument was declined. The payer can pick another one and retry. */
    record Declined() implements CaptureResult {}

    /** The order was captured before, for example by an earlier attempt whose response was lost. */
    record AlreadyCaptured() implements CaptureResult {}

    record OrderNotFound() implements CaptureResult {}

    /** Any other failure. {@code reason} is safe to log. */
    record Failed(String reason) implements CaptureResult {}
}
