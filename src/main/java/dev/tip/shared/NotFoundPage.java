package dev.tip.shared;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * The static 404 page for browsers, with this request's CSP nonce. It never contains anything from the
 * request, so a path or query cannot be echoed into it.
 */
@Component
public class NotFoundPage {

    private static final String NONCE_PLACEHOLDER = "{{CSP_NONCE}}";
    private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);
    /** Machine clients: these always answer problem details, whatever they accept. */
    private static final List<String> API_PREFIXES = List.of("/api", "/webhooks", "/ws", "/actuator");

    private final String template;

    NotFoundPage() {
        try {
            template = new ClassPathResource("static/404.html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("404 page template missing", e);
        }
    }

    /** The page as a 404 response, for controllers. */
    public ResponseEntity<String> response(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(HTML)
                .cacheControl(CacheControl.noStore())
                .body(body(request));
    }

    /** Writes the page as a 404 response, for code outside Spring MVC such as the security filters. */
    void write(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        response.setContentType(HTML.toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.getWriter().write(body(request));
    }

    /**
     * True for a browser {@code GET} outside the machine paths, whose {@code Accept} prefers HTML: it lists
     * {@code text/html} and no JSON type with a higher quality.
     */
    static boolean wantedBy(HttpServletRequest request) {
        // Without a nonce the page could not match its policy; such a request gets problem details.
        if (!"GET".equals(request.getMethod()) || !CspNonce.isSet(request)) {
            return false;
        }
        // On an error dispatch the request URI is the error path; the original one decides.
        String uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String original
                ? original
                : request.getRequestURI();
        String path = uri.substring(request.getContextPath().length());
        if (API_PREFIXES.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"))) {
            return false;
        }
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept == null) {
            return false;
        }
        List<MediaType> types;
        try {
            types = MediaType.parseMediaTypes(accept);
        } catch (InvalidMediaTypeException e) {
            return false;
        }
        double html = quality(types, MediaType.TEXT_HTML);
        double json = Math.max(
                quality(types, MediaType.APPLICATION_JSON), quality(types, MediaType.APPLICATION_PROBLEM_JSON));
        return html > 0 && html >= json;
    }

    private static double quality(List<MediaType> types, MediaType wanted) {
        return types.stream()
                .filter(type -> type.getType().equals(wanted.getType())
                        && type.getSubtype().equals(wanted.getSubtype()))
                .mapToDouble(MediaType::getQualityValue)
                .max()
                .orElse(0);
    }

    private String body(HttpServletRequest request) {
        return template.replace(NONCE_PLACEHOLDER, CspNonce.of(request));
    }
}
