package dev.tip.shared;

import org.springframework.http.HttpStatus;

/**
 * Fixed {@code detail} and {@code code} for errors the framework raises, such as an unknown path or a
 * wrong method. The framework's own detail text names internals ("No static resource ..."), so it never
 * reaches a client.
 */
final class GenericProblems {

    private GenericProblems() {}

    static String detail(HttpStatus status) {
        if (status.is5xxServerError()) {
            return "Something went wrong. Please try again.";
        }
        return switch (status) {
            case BAD_REQUEST -> "The request is not valid.";
            case NOT_FOUND -> "The requested resource was not found.";
            case METHOD_NOT_ALLOWED -> "This method is not allowed for the requested resource.";
            case NOT_ACCEPTABLE -> "No acceptable representation is available.";
            case UNSUPPORTED_MEDIA_TYPE -> "This content type is not supported.";
            case PAYLOAD_TOO_LARGE -> "The request is too large.";
            default -> status.getReasonPhrase() + ".";
        };
    }

    static String code(HttpStatus status) {
        return status.is5xxServerError() ? "INTERNAL_ERROR" : status.name();
    }
}
