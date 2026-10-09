package dev.tip.owners;

import java.util.Locale;

final class OwnerEmails {

    private OwnerEmails() {}

    /** Emails are stored and compared lowercase, so signing in does not depend on how the owner types it. */
    static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
