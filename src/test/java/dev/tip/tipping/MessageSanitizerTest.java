package dev.tip.tipping;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MessageSanitizerTest {

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(MessageSanitizer.sanitize("  lovely music  ")).isEqualTo("lovely music");
    }

    @Test
    void nullAndBlankBecomeNull() {
        assertThat(MessageSanitizer.sanitize(null)).isNull();
        assertThat(MessageSanitizer.sanitize("")).isNull();
        assertThat(MessageSanitizer.sanitize(" \t\n ")).isNull();
        assertThat(MessageSanitizer.sanitize("\u0000\u0007")).isNull();
    }

    @Test
    void lineBreaksAndTabsBecomeOneSpace() {
        assertThat(MessageSanitizer.sanitize("thank\r\nyou\tpanda")).isEqualTo("thank you panda");
        assertThat(MessageSanitizer.sanitize("a\u2028b")).isEqualTo("a b");
    }

    @Test
    void removesControlCharacters() {
        assertThat(MessageSanitizer.sanitize("be\u0000ep\u0007\u001b[31m")).isEqualTo("beep[31m");
        assertThat(MessageSanitizer.sanitize("del\u007fete")).isEqualTo("delete");
    }

    @Test
    void removesInvisibleFormatCharactersSuchAsBidiOverrides() {
        assertThat(MessageSanitizer.sanitize("evil\u202Etxt.exe")).isEqualTo("eviltxt.exe");
        assertThat(MessageSanitizer.sanitize("zero\u200Bwidth")).isEqualTo("zerowidth");
    }

    @Test
    void keepsOrdinaryUnicodeTextAndEmoji() {
        assertThat(MessageSanitizer.sanitize("Merci beaucoup 🐼 <3")).isEqualTo("Merci beaucoup 🐼 <3");
        assertThat(MessageSanitizer.sanitize("family 👨\u200D👩\u200D👧")).isEqualTo("family 👨\u200D👩\u200D👧");
    }
}
