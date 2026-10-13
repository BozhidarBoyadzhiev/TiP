package dev.tip.shared;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Values that may go into a log line without being personal data. A client IP is logged only as an HMAC under a
 * random key made when the process starts and never stored, so lines of one process can be matched to each other
 * but not to an address, and a restart unlinks them.
 */
public final class LogSafe {

    private static final String ALGORITHM = "HmacSHA256";
    private static final SecretKeySpec KEY = new SecretKeySpec(randomKey(), ALGORITHM);

    private LogSafe() {}

    /** 16 hex characters of the HMAC of {@code clientIp}, or {@code -} without one. */
    public static String ip(String clientIp) {
        if (clientIp == null) {
            return "-";
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(KEY);
            byte[] digest = mac.doFinal(clientIp.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
