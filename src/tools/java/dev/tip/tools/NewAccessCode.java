package dev.tip.tools;

import dev.tip.owners.AccessCode;
import java.io.PrintStream;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Makes a judge access code: {@code ./gradlew newAccessCode -Plabel=judges -Pexpires=2026-12-31T23:59:59Z}. Prints
 * the code once and the {@code TIP_ACCESS_CODES} entry with its hash. Writes nothing to a file: the code goes only
 * into Devpost's private Additional info field, the entry only into the host's environment.
 */
public final class NewAccessCode {

    private static final Pattern LABEL = Pattern.compile("[a-z0-9-]{1,32}");

    private NewAccessCode() {}

    public static void main(String[] args) {
        System.exit(run(args, new SecureRandom(), System.out, System.err));
    }

    static int run(String[] args, SecureRandom random, PrintStream out, PrintStream err) {
        String label = option(args, "--label=");
        String expires = option(args, "--expires=");
        if (label == null || !LABEL.matcher(label).matches()) {
            err.println("Pass -Plabel=<label>, lowercase letters, digits and hyphens, at most 32 characters.");
            return 2;
        }
        OffsetDateTime expiresAt;
        try {
            expiresAt = OffsetDateTime.parse(expires == null ? "" : expires);
        } catch (DateTimeParseException e) {
            err.println("Pass -Pexpires=<ISO 8601 time with an offset>, such as 2026-12-31T23:59:59Z.");
            return 2;
        }
        String code = AccessCode.generate(random);
        out.println("Access code (shown once, give it only through Devpost's private Additional info field):");
        out.println("  " + AccessCode.display(code));
        out.println("TIP_ACCESS_CODES entry (append with ; to any existing entries):");
        out.println("  " + label + ":" + AccessCode.hashHex(code) + ":" + expiresAt);
        return 0;
    }

    private static String option(String[] args, String prefix) {
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                String value = arg.substring(prefix.length()).strip();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
