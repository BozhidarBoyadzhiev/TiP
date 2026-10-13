package dev.tip.tools;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.owners.AccessCode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class NewAccessCodeTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void printsTheCodeOnceAndTheEntryWithItsHash() {
        int exit = run("--label=judges", "--expires=2026-12-31T23:59:59Z");

        String printed = out.toString(StandardCharsets.UTF_8);
        Matcher code = Pattern.compile("TIP(-[0-9A-Z]{5}){4}").matcher(printed);
        assertThat(exit).isZero();
        assertThat(code.find()).isTrue();
        String shown = code.group();
        assertThat(code.find()).as("the code is printed once").isFalse();
        assertThat(printed).contains("judges:" + AccessCode.hashHex(shown) + ":2026-12-31T23:59:59Z");
    }

    @Test
    void aBadLabelOrExpiryPrintsNoCode() {
        assertThat(run("--label=Judges", "--expires=2026-12-31T23:59:59Z")).isEqualTo(2);
        assertThat(run("--label=judges", "--expires=2026-12-31")).isEqualTo(2);
        assertThat(run()).isEqualTo(2);
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("TIP-");
    }

    private int run(String... args) {
        return NewAccessCode.run(
                args,
                new SecureRandom(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }
}
