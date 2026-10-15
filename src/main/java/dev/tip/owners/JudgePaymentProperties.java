package dev.tip.owners;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PayPal sandbox test payment details shown to judges in their sandbox, bound from {@code TIP_JUDGE_CARD_NUMBER},
 * {@code TIP_JUDGE_CARD_EXPIRY}, {@code TIP_JUDGE_CARD_CVC}, {@code TIP_JUDGE_PAYPAL_EMAIL} and {@code
 * TIP_JUDGE_PAYPAL_PASSWORD}. All optional; a blank value counts as unset. They are sandbox values that move no real
 * money, but they still never appear in a log line, so {@link #toString()} leaves them out.
 */
@ConfigurationProperties("tip.judge-payment")
record JudgePaymentProperties(
        String cardNumber, String cardExpiry, String cardCvc, String paypalEmail, String paypalPassword) {

    JudgePaymentProperties {
        cardNumber = blankToNull(cardNumber);
        cardExpiry = blankToNull(cardExpiry);
        cardCvc = blankToNull(cardCvc);
        paypalEmail = blankToNull(paypalEmail);
        paypalPassword = blankToNull(paypalPassword);
    }

    /** The card, only when its number, expiry and CVC are all set. */
    TestPaymentController.Card card() {
        if (cardNumber == null || cardExpiry == null || cardCvc == null) {
            return null;
        }
        return new TestPaymentController.Card(cardNumber, cardExpiry, cardCvc);
    }

    /** The sandbox buyer account, only when its email and password are both set. */
    TestPaymentController.PayPalAccount paypal() {
        if (paypalEmail == null || paypalPassword == null) {
            return null;
        }
        return new TestPaymentController.PayPalAccount(paypalEmail, paypalPassword);
    }

    @Override
    public String toString() {
        return "JudgePaymentProperties[card=" + (card() != null) + ", paypal=" + (paypal() != null) + "]";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
