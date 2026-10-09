package dev.tip.shared;

import org.springframework.http.HttpStatus;

/**
 * Base for feature specific failures. {@link ProblemDetailsAdvice} turns one into RFC 9457 problem
 * details with the given status, a stable {@code code} and {@code detail}, which must be safe to show
 * to any client.
 */
public abstract class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected DomainException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
