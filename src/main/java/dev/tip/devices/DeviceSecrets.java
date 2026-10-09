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

    static byte[] hash(byte[] secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }
}
