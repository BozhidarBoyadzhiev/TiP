package dev.tip.tipping;

import dev.tip.paypal.api.CaptureResult;
import dev.tip.paypal.api.PayPalGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Asks PayPal what became of a tip's order, for the jobs that settle tips whose answer or webhook never came. Called
 * outside any transaction. A capture that does not charge exactly the tip's amount and currency counts as no capture,
 * with a WARN, as for the capture answer and the webhook.
 */
@Component
class OrderLookup {

    private static final Logger log = LoggerFactory.getLogger(OrderLookup.class);

    private final PayPalGateway payPal;

    OrderLookup(PayPalGateway payPal) {
        this.payPal = payPal;
    }

    CaptureResult lookup(Tip tip) {
        CaptureResult result = payPal.lookupOrder(tip.paypalOrderId());
        if (result instanceof CaptureResult.Captured captured && !captured.charges(tip.amountMinor(), tip.currency())) {
            log.warn("Tip {}: PayPal's capture does not charge the tip's amount, left the tip unchanged", tip.id());
            return new CaptureResult.Failed("capture of another amount");
        }
        return result;
    }
}
