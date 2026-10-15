package dev.tip.paypal.api;

import java.math.BigDecimal;
import java.util.Currency;

/** PayPal writes amounts as decimal strings in major units, for example "2.50" for 250 GBP minor units. */
public final class PayPalAmounts {

    private PayPalAmounts() {}

    /** True when PayPal's {@code value} in {@code valueCurrency} is exactly {@code amountMinor} of {@code currency}. */
    public static boolean equal(String value, String valueCurrency, long amountMinor, String currency) {
        if (value == null || currency == null || !currency.equals(valueCurrency)) {
            return false;
        }
        try {
            int digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
            return new BigDecimal(value).compareTo(BigDecimal.valueOf(amountMinor, digits)) == 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
