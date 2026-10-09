package dev.tip.devices;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

/**
 * Device secrets are 32 random bytes, base64url encoded. Only the SHA-256 of the decoded bytes is
 * stored. SHA-256 without a salt is enough because the secret has full entropy and cannot be guessed.
 */
final class DeviceSecrets {

    static final int SECRET_BYTES = 32;

    static final int MIN_DISTINCT_BYTES = 16;

    private DeviceSecrets() {}

    /** The decoded secret, or empty when it is not base64url or not exactly 32 bytes. */
    static Optional<byte[]> decode(String secret) {
        if (secret == null) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(secret.strip());
            return bytes.length == SECRET_BYTES ? Optional.of(bytes) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * False when the decoded secret is clearly not random: every byte the same, or fewer than {@link
     * #MIN_DISTINCT_BYTES} distinct byte values. 32 random bytes almost always hold about 30 distinct
     * values, so this only catches broken generators, such as a command that printed 32 zero bytes.
     */
    static boolean looksRandom(byte[] secret) {
        return distinctValues(secret) >= MIN_DISTINCT_BYTES;
    }

    static boolean allBytesIdentical(byte[] secret) {
        return distinctValues(secret) == 1;
    }

    private static long distinctValues(byte[] secret) {
        boolean[] seen = new boolean[256];
        long distinct = 0;
        for (byte b : secret) {
            if (!seen[b & 0xff]) {
                seen[b & 0xff] = true;
                distinct++;
            }
        }
        return distinct;
    }

    static byte[] hash(byte[] secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }
}
