package dev.tip.shared;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every failure to RFC 9457 problem details. No stack trace or exception message of an unexpected error reaches a client. */
@RestControllerAdvice
class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);
    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    private final NotFoundPage notFoundPage;

    ProblemDetailsAdvice(NotFoundPage notFoundPage) {
        this.notFoundPage = notFoundPage;
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomain(DomainException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setProperty("code", e.code());
        return ResponseEntity.status(e.status()).body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException e) {
        List<Map<String, String>> errors = e.getConstraintViolations().stream()
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .toList();
        return ResponseEntity.badRequest().body(validationProblem(errors));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        if (LogSafe.isDataError(e)) {
            log.error("Unexpected database error: {}", LogSafe.describe(e));
        } else {
            log.error("Unexpected error", e);
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again.");
        problem.setProperty("code", "INTERNAL_ERROR");
        return ResponseEntity.internalServerError().body(problem);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(ProblemDetailsAdvice::fieldError)
                .toList();
        return ResponseEntity.badRequest().body(validationProblem(errors));
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.of(
                                "field",
                                String.valueOf(result.getMethodParameter().getParameterName()),
                                "message",
                                String.valueOf(error.getDefaultMessage()))))
                .toList();
        return ResponseEntity.badRequest().body(validationProblem(errors));
    }

    /** A body {@link RequestSizeLimit} cut off is too large, not malformed: the 413 of a declared length. */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof RequestSizeLimit.TooLarge) {
                return createResponseEntity(
                        ProblemDetail.forStatus(RequestSizeLimit.STATUS), headers, RequestSizeLimit.STATUS, request);
            }
        }
        return super.handleHttpMessageNotReadable(e, headers, status, request);
    }

    /** A browser asking for a page that does not exist gets the 404 page. */
    @Override
    protected @Nullable ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (request instanceof ServletWebRequest servlet && NotFoundPage.wantedBy(servlet.getRequest())) {
            ResponseEntity<String> page = notFoundPage.response(servlet.getRequest());
            return new ResponseEntity<>(page.getBody(), page.getHeaders(), page.getStatusCode());
        }
        return super.handleNoResourceFoundException(e, headers, status, request);
    }

    /** Errors the framework raised get a fixed detail, so its wording about internals never reaches a client. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getProperties() == null) {
            HttpStatus status = HttpStatus.valueOf(statusCode.value());
            problem.setDetail(GenericProblems.detail(status));
            problem.setProperty("code", GenericProblems.code(status));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request is not valid.");
        problem.setProperty("code", VALIDATION_FAILED);
        problem.setProperty("errors", errors);
        return problem;
    }

    private static Map<String, String> fieldError(FieldError error) {
        return Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage()));
    }
}
