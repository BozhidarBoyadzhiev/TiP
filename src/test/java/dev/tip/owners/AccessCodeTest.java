package dev.tip.owners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccessCodeTest {

    private static final String CODE = "7K2QM0BX1VH4N9RT1ZPD";

    @Test
    void aCodeIs20CrockfordCharactersShownInFourGroups() {
        String code = AccessCode.generate(new SecureRandom());

        assertThat(code).hasSize(20).matches("[0-9ABCDEFGHJKMNPQRSTVWXYZ]{20}");
        assertThat(AccessCode.display(code)).matches("TIP(-[0-9A-Z]{5}){4}");
        assertThat(AccessCode.normalise(AccessCode.display(code))).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "TIP-7K2QM-0BX1V-H4N9R-T1ZPD",
                "  tip-7k2qm-0bx1v-h4n9r-t1zpd  ",
                "7K2QM 0BX1V H4N9R T1ZPD",
                "7k2qm0bx1vh4n9rt1zpd",
                "TIP 7K2QM-OBXIV-H4N9R-TLZPD",
                "tip7k2qmobx1vh4n9rtizpd"
            })
    void typingVariationsNormaliseToTheSameCode(String typed) {
        assertThat(AccessCode.normalise(typed)).isEqualTo(CODE);
        assertThat(AccessCode.hash(typed)).isEqualTo(sha256(CODE));
    }

    @Test
    void thePrefixIsOnlyDroppedWhenSomethingIsLeftBeyondAFullCode() {
        assertThat(AccessCode.normalise("TIPAB-CDEFG-HJKMN-PQRST")).isEqualTo("T1PABCDEFGHJKMNPQRST");
        assertThat(AccessCode.normalise(null)).isEmpty();
    }

    @Test
    void entriesParseWithTheirLabelHashAndExpiry() {
        String hash = AccessCode.hashHex(CODE);
        AccessCodes codes = new AccessCodes(" judges:" + hash + ":2026-12-31T23:59:59+00:00 ; ;" + "team-2:"
                + hash.toUpperCase() + ":2026-11-01T00:00:00Z;");

        assertThat(codes.match(CODE, Instant.parse("2026-10-13T00:00:00Z")))
                .hasValueSatisfying(e -> assertThat(e.label()).isIn("judges", "team-2"));
        assertThat(AccessCodes.parse("")).isEmpty();
        assertThat(AccessCodes.parse(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "judges",
                "judges:abc:2026-12-31T23:59:59Z",
                "Judges:%s:2026-12-31T23:59:59Z",
                "a-label-that-is-far-too-long-for-us:%s:2026-12-31T23:59:59Z",
                "judges:%s:2026-12-31T23:59:59",
                "judges:%s:tomorrow",
                "judges:%s:2026-12-31T23:59:59Z;judges:%s:2026-12-31T23:59:59Z"
            })
    void aMalformedEntryStopsTheAppWithoutEchoingIt(String entry) {
        String hash = AccessCode.hashHex(CODE);
        String configured = entry.replace("%s", hash);

        assertThatThrownBy(() -> new AccessCodes(configured))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("TIP_ACCESS_CODES entry ")
                .hasMessageNotContaining(hash);
    }

    @Test
    void anExpiredCodeIsRejectedLikeAWrongOne() {
        AccessCodes codes = new AccessCodes("judges:" + AccessCode.hashHex(CODE) + ":2026-11-01T00:00:00Z");

        assertThat(codes.match(CODE, Instant.parse("2026-10-31T23:59:59Z"))).isPresent();
        assertThat(codes.match(CODE, Instant.parse("2026-11-01T00:00:00Z"))).isEmpty();
        assertThat(codes.match("TIP-00000-00000-00000-00000", Instant.parse("2026-10-01T00:00:00Z")))
                .isEmpty();
    }

    @Test
    void everyEntryIsComparedInConstantTimeWithoutAnEarlyExit() throws Exception {
        String source =
                Files.readString(Path.of("src/main/java/dev/tip/owners/AccessCodes.java"), StandardCharsets.UTF_8);
        String match = source.substring(
                source.indexOf("Optional<Entry> match("),
                source.indexOf("private static IllegalStateException malformed"));

        assertThat(match)
                .contains("MessageDigest.isEqual(")
                .doesNotContain("break")
                .doesNotContain("return found")
                .doesNotContain("Arrays.equals")
                .doesNotContain(".equals(");
        String later = AccessCode.hashHex(CODE);
        AccessCodes codes = new AccessCodes("first:" + AccessCode.hashHex("TIP-11111-11111-11111-11111")
                + ":2099-01-01T00:00:00Z;later:" + later + ":2099-01-01T00:00:00Z");
        assertThat(codes.match(CODE, Instant.parse("2026-10-13T00:00:00Z")))
                .hasValueSatisfying(e -> assertThat(e.label()).isEqualTo("later"));
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
