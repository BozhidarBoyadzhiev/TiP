package dev.tip.owners;

import dev.tip.shared.DomainException;
import org.springframework.http.HttpStatus;

/** Failures of the owners feature, each mapped to problem details by the shared advice. */
final class OwnerExceptions {

    private OwnerExceptions() {}

    /** The same status and code as a wrong password, for a wrong and an expired code alike. */
    static final class CodeRejected extends DomainException {
        CodeRejected() {
            super(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "The access code is not valid.");
        }
    }

    /** One answer for a malformed, unknown, used or expired resume token, and one whose sandbox expired. */
    static final class ResumeFailed extends DomainException {
        ResumeFailed() {
            super(HttpStatus.UNAUTHORIZED, "RESUME_FAILED", "The link was already used or has run out.");
        }
    }

    static final class RateLimited extends DomainException {
        RateLimited() {
            super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts. Please wait and try again.");
        }
    }

    /**
     * A resume link is only for sandbox sessions. Any other owner gets what a path that does not exist answers, so
     * the endpoint tells them nothing.
     */
    static final class SandboxOnly extends DomainException {
        SandboxOnly() {
            super(HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested resource was not found.");
        }
    }

    /** The public demo owner is missing, which only happens before startup finished. */
    static final class DemoUnavailable extends DomainException {
        DemoUnavailable() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "DEMO_UNAVAILABLE", "The demo is not ready. Please try again.");
        }
    }
}
