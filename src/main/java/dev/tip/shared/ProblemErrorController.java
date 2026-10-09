package dev.tip.shared;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Spring Boot's error controller, so errors the servlet container forwards to {@code /error}
 * (for example a request the firewall rejects) are problem details too, without timestamps or paths.
 */
@RestController
class ProblemErrorController implements ErrorController {

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        HttpStatus status = statusOf(request);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, GenericProblems.detail(status));
        problem.setProperty("code", GenericProblems.code(status));
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (uri instanceof String path) {
            try {
                problem.setInstance(URI.create(path));
            } catch (IllegalArgumentException e) {
                // A path the firewall rejected may not be a valid URI; the problem is still useful without it.
            }
        }
        return ResponseEntity.status(status).body(problem);
    }

    private static HttpStatus statusOf(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer value) {
            HttpStatus status = HttpStatus.resolve(value);
            if (status != null && status.isError()) {
                return status;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
