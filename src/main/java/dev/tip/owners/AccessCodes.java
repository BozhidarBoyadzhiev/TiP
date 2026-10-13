package dev.tip.owners;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The access codes in {@code TIP_ACCESS_CODES}: entries {@code label:sha256hex:expiresAt} separated by {@code ;},
 * the expiry ISO 8601 with an offset. Parsed once at startup; a malformed entry stops the app, naming the entry by
 * its position only. Empty or unset means no codes. A code is checked against every entry with {@link
 * MessageDigest#isEqual} and no early exit, and an expired code fails exactly like a wrong one.
 */
@Component
class AccessCodes {

    static final Pattern LABEL = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{64}");

    private final List<Entry> entries;

    AccessCodes(@Value("${tip.access-codes:}") String configured) {
        this.entries = parse(configured);
    }

    static List<Entry> parse(String configured) {
        List<Entry> entries = new ArrayList<>();
        if (configured == null || configured.isBlank()) {
            return entries;
        }
        Set<String> labels = new HashSet<>();
        String[] parts = configured.split(";");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].strip();
            if (part.isEmpty()) {
                continue;
            }
            String[] fields = part.split(":", 3);
            if (fields.length != 3) {
                throw malformed(i, "expected label:sha256hex:expiresAt");
            }
            String label = fields[0].strip();
            String hash = fields[1].strip();
            if (!LABEL.matcher(label).matches()) {
                throw malformed(i, "the label must match [a-z0-9-]{1,32}");
            }
            if (!labels.add(label)) {
                throw malformed(i, "the label " + label + " is used twice");
            }
            if (!HASH.matcher(hash).matches()) {
                throw malformed(i, "the hash must be 64 hex characters");
            }
            Instant expiresAt;
            try {
                expiresAt = OffsetDateTime.parse(fields[2].strip()).toInstant();
            } catch (DateTimeParseException e) {
                throw malformed(i, "expiresAt must be ISO 8601 with an offset, such as 2026-12-31T23:59:59Z");
            }
            entries.add(new Entry(label, HexFormat.of().parseHex(hash), expiresAt));
        }
        return List.copyOf(entries);
    }

    /** The entry the code belongs to, while it has not expired. Every entry is compared, whatever matches. */
    Optional<Entry> match(String code, Instant now) {
        byte[] presented = AccessCode.hash(code);
        Entry found = null;
        for (Entry entry : entries) {
            boolean equal = MessageDigest.isEqual(presented, entry.hash());
            if (equal && now.isBefore(entry.expiresAt())) {
                found = entry;
            }
        }
        return Optional.ofNullable(found);
    }

    private static IllegalStateException malformed(int index, String reason) {
        return new IllegalStateException("TIP_ACCESS_CODES entry " + (index + 1) + " is malformed: " + reason);
    }

    record Entry(String label, byte[] hash, Instant expiresAt) {}
}
