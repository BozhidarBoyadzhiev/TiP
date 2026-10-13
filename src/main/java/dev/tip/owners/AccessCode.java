package dev.tip.owners;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The access code format: 20 Crockford base32 characters (100 bits), shown as {@code TIP-XXXXX-XXXXX-XXXXX-XXXXX}.
 * Only the SHA-256 of the normalised code is ever configured. Public for the {@code newAccessCode} tool only, so
 * it generates and hashes codes exactly as the app checks them.
 */
public final class AccessCode {

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int LENGTH = 20;
    static final int GROUP = 5;
    static final String PREFIX = "TIP";

    private AccessCode() {}

    /**
     * Trims, uppercases, drops spaces, dashes and the {@code TIP} prefix, and maps the letters people mistake for
     * digits: {@code O} to {@code 0}, {@code I} and {@code L} to {@code 1}. The prefix goes only when something is
     * left beyond a full code, so a code is never cut. Null becomes empty.
     */
    public static String normalise(String input) {
        if (input == null) {
            return "";
        }
        String compact = input.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        if (compact.startsWith(PREFIX) && compact.length() > LENGTH) {
            compact = compact.substring(PREFIX.length());
        }
        return compact.replace('O', '0').replace('I', '1').replace('L', '1');
    }

    /** SHA-256 of the normalised code's UTF-8 bytes. */
    public static byte[] hash(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(normalise(input).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static String hashHex(String input) {
        return HexFormat.of().formatHex(hash(input));
    }

    /** A new code from {@code random}: 20 characters of 5 bits each. */
    public static String generate(SecureRandom random) {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** {@code TIP-XXXXX-XXXXX-XXXXX-XXXXX} for a normalised code. */
    public static String display(String code) {
        StringBuilder shown = new StringBuilder(PREFIX);
        for (int i = 0; i < code.length(); i += GROUP) {
            shown.append('-').append(code, i, Math.min(code.length(), i + GROUP));
        }
        return shown.toString();
    }
}
