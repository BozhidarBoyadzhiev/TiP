package dev.tip.owners;

import dev.tip.owners.api.OwnerKind;
import dev.tip.owners.api.OwnerPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The PayPal sandbox test card and buyer account for a judge's sandbox, so a judge can pay a tip without making a
 * sandbox account. Only a sandbox session gets them; any other owner gets the 404 of a path that does not exist.
 * Nothing here logs a value.
 */
@RestController
class TestPaymentController {

    static final String PATH = "/api/owner/test-payment";

    /** Harmless sample billing details: a fictional name, a well known public postcode and reserved test contacts. */
    static final Billing BILLING = new Billing("Test Judge", "SW1A 1AA", "07700 900123", "judge@example.com");

    private final JudgePaymentProperties properties;

    TestPaymentController(JudgePaymentProperties properties) {
        this.properties = properties;
    }

    /** {@code card} and {@code paypal} are null unless every one of their values is configured. */
    @GetMapping(PATH)
    ResponseEntity<TestPayment> testPayment(@AuthenticationPrincipal OwnerPrincipal owner) {
        if (owner.kind() != OwnerKind.SANDBOX) {
            throw new OwnerExceptions.SandboxOnly();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new TestPayment(properties.card(), properties.paypal(), BILLING));
    }

    /** Its string form names no value, so a debug line about the response shows none. */
    record TestPayment(Card card, PayPalAccount paypal, Billing billing) {
        @Override
        public String toString() {
            return "TestPayment[card=" + (card != null) + ", paypal=" + (paypal != null) + "]";
        }
    }

    record Card(String number, String expiry, String cvc) {
        @Override
        public String toString() {
            return "Card[]";
        }
    }

    record PayPalAccount(String email, String password) {
        @Override
        public String toString() {
            return "PayPalAccount[]";
        }
    }

    record Billing(String name, String postcode, String mobile, String email) {}
}
