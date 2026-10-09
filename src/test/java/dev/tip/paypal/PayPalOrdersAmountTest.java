package dev.tip.paypal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PayPalOrdersAmountTest {

    @Test
    void formatsMinorUnitsAsPayPalDecimalStrings() {
        assertThat(PayPalOrders.formatAmount(250, "GBP")).isEqualTo("2.50");
        assertThat(PayPalOrders.formatAmount(100, "GBP")).isEqualTo("1.00");
        assertThat(PayPalOrders.formatAmount(10000, "EUR")).isEqualTo("100.00");
        assertThat(PayPalOrders.formatAmount(500, "JPY")).isEqualTo("500");
    }
}
