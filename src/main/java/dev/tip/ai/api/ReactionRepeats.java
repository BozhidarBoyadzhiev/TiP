package dev.tip.ai.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * How many times the panda repeats a reaction's unit beyond its base pattern, from the amount charged.
 * The amount in the currency's major unit is rounded half up to a whole number: 1 or less plays once,
 * 2 twice, 3 to 4 three times, 5 to 9 four times, 10 and up five times. A flagged tip always plays once,
 * so an abusive message cannot buy a bigger reaction. Public so the dev seeder stores the repeats the app
 * would have sent.
 */
public final class ReactionRepeats {

    private ReactionRepeats() {}

    public static int of(long amountMinor, String currency, boolean flagged) {
        if (flagged) {
            return TipReacted.MIN_REPEATS;
        }
        int fractionDigits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        BigDecimal major = BigDecimal.valueOf(amountMinor, fractionDigits).setScale(0, RoundingMode.HALF_UP);
        if (major.compareTo(BigDecimal.valueOf(10)) >= 0) {
            return 5;
        }
        int whole = major.intValueExact();
        if (whole >= 5) {
            return 4;
        }
        if (whole >= 3) {
            return 3;
        }
        if (whole == 2) {
            return 2;
        }
        return TipReacted.MIN_REPEATS;
    }
}
