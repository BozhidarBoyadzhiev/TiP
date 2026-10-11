package dev.tip.dashboard;

import dev.tip.shared.DomainException;
import org.springframework.http.HttpStatus;

/** Failures of the dashboard feature, each mapped to problem details by the shared advice. */
final class DashboardExceptions {

    private DashboardExceptions() {}

    /** The detail names the parameter and never repeats the value that was sent. */
    static final class InvalidParameter extends DomainException {
        InvalidParameter(String detail) {
            super(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", detail);
        }
    }

    /** Also used for another owner's tip and a tip in a hidden status, so tip ids cannot be probed. */
    static final class TipNotFound extends DomainException {
        TipNotFound() {
            super(HttpStatus.NOT_FOUND, "TIP_NOT_FOUND", "No tip with this id exists.");
        }
    }
}
