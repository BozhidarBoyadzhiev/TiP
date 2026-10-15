package dev.tip.owners;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Each part of the test payment details is shown only when all of its values are set. */
class JudgePaymentPropertiesTest {

    @Test
    void theCardNeedsItsNumberExpiryAndCvc() {
        assertThat(new JudgePaymentProperties("number", "expiry", "cvc", null, null).card())
                .isEqualTo(new TestPaymentController.Card("number", "expiry", "cvc"));
        assertThat(new JudgePaymentProperties(null, "expiry", "cvc", null, null).card())
                .isNull();
        assertThat(new JudgePaymentProperties("number", " ", "cvc", null, null).card())
                .isNull();
        assertThat(new JudgePaymentProperties("number", "expiry", "", null, null).card())
                .isNull();
    }

    @Test
    void theAccountNeedsItsEmailAndPassword() {
        assertThat(new JudgePaymentProperties(null, null, null, "buyer@example.com", "words").paypal())
                .isEqualTo(new TestPaymentController.PayPalAccount("buyer@example.com", "words"));
        assertThat(new JudgePaymentProperties(null, null, null, "buyer@example.com", null).paypal())
                .isNull();
        assertThat(new JudgePaymentProperties(null, null, null, "  ", "words").paypal())
                .isNull();
    }

    @Test
    void valuesAreTrimmedAndNeverInTheStringForm() {
        JudgePaymentProperties properties =
                new JudgePaymentProperties(" number ", "expiry", "cvc", "buyer@example.com", "words");

        assertThat(properties.card().number()).isEqualTo("number");
        assertThat(properties.toString())
                .doesNotContain("number", "expiry", "cvc", "buyer", "words")
                .isEqualTo("JudgePaymentProperties[card=true, paypal=true]");
        assertThat(new TestPaymentController.TestPayment(
                                properties.card(), properties.paypal(), TestPaymentController.BILLING)
                        .toString())
                .doesNotContain("number", "expiry", "cvc", "buyer", "words");
    }
}
