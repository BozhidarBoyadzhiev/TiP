package dev.tip.ai.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReactionRepeatsTest {

    @ParameterizedTest(name = "{0} pence plays {1}")
    @CsvSource({
        "0,1",
        "49,1",
        "50,1",
        "149,1",
        "150,2",
        "249,2",
        "250,3",
        "449,3",
        "450,4",
        "949,4",
        "950,5",
        "100000,5"
    })
    void scalesWithTheAmountRoundedHalfUp(long amountMinor, int repeats) {
        assertThat(ReactionRepeats.of(amountMinor, "GBP", false)).isEqualTo(repeats);
    }

    @ParameterizedTest
    @CsvSource({"0", "100", "2000", "100000"})
    void flaggedTipAlwaysPlaysOnce(long amountMinor) {
        assertThat(ReactionRepeats.of(amountMinor, "GBP", true)).isOne();
    }

    @Test
    void usesTheMinorUnitsOfTheTipsCurrency() {
        assertThat(ReactionRepeats.of(5, "JPY", false)).as("5 yen").isEqualTo(4);
        assertThat(ReactionRepeats.of(2_500, "BHD", false)).as("2.500 dinar").isEqualTo(3);
    }
}
