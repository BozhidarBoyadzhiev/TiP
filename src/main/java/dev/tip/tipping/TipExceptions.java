package dev.tip.tipping;

import dev.tip.shared.DomainException;
import org.springframework.http.HttpStatus;

/** Failures of the tipping feature, each mapped to problem details by the shared advice. */
final class TipExceptions {

    private TipExceptions() {}

    static final class InvalidTip extends DomainException {
        InvalidTip(String code, String detail) {
            super(HttpStatus.BAD_REQUEST, code, detail);
        }
    }

    static final class TipNotFound extends DomainException {
        TipNotFound() {
            super(HttpStatus.NOT_FOUND, "TIP_NOT_FOUND", "No tip with this id exists.");
        }
    }

    static final class TipNotReady extends DomainException {
        TipNotReady() {
            super(HttpStatus.CONFLICT, "TIP_NOT_READY", "This tip has no PayPal order yet.");
        }
    }

    static final class TipClosed extends DomainException {
        TipClosed() {
            super(HttpStatus.CONFLICT, "TIP_CLOSED", "This tip can no longer be completed. Please start again.");
        }
    }

    static final class TipNotRefundable extends DomainException {
        TipNotRefundable() {
            super(HttpStatus.CONFLICT, "TIP_NOT_REFUNDABLE", "Only captured tips can be refunded.");
        }
    }

    /** A sample tip was never paid through PayPal, so there is nothing to refund. */
    static final class DemoTip extends DomainException {
        DemoTip() {
            super(HttpStatus.CONFLICT, "DEMO_TIP", "This is a sample tip. There is no payment to refund.");
        }
    }

    static final class InstrumentDeclined extends DomainException {
        InstrumentDeclined() {
            super(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "INSTRUMENT_DECLINED",
                    "The payment method was declined. Please choose another one.");
        }
    }

    static final class PayPalUnavailable extends DomainException {
        PayPalUnavailable(String code) {
            super(HttpStatus.BAD_GATEWAY, code, "PayPal could not process this tip. Please try again.");
        }
    }

    static final class WebhookNotVerified extends DomainException {
        WebhookNotVerified() {
            super(HttpStatus.BAD_REQUEST, "WEBHOOK_NOT_VERIFIED", "The webhook signature could not be verified.");
        }
    }

    static final class WebhookMalformed extends DomainException {
        WebhookMalformed() {
            super(HttpStatus.BAD_REQUEST, "WEBHOOK_MALFORMED", "The webhook event is missing required fields.");
        }
    }

    static final class WebhookVerificationUnavailable extends DomainException {
        WebhookVerificationUnavailable() {
            super(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "WEBHOOK_VERIFICATION_UNAVAILABLE",
                    "The webhook could not be verified right now. Please retry.");
        }
    }

    /**
     * A refund or reversal arrived before the capture it undoes was recorded. Answered 503 and not stored, so PayPal
     * delivers it again once the capture has landed.
     */
    static final class WebhookBeforeCapture extends DomainException {
        WebhookBeforeCapture() {
            super(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "WEBHOOK_BEFORE_CAPTURE",
                    "The payment this event undoes is not recorded yet. Please retry.");
        }
    }

    static final class RateLimited extends DomainException {
        RateLimited() {
            super(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMITED",
                    "Too many tips from this network. Please wait a minute.");
        }
    }
}
