package dev.tip.owners;

import dev.tip.shared.CspNonce;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * Serves the owner pages from static templates. Every substituted value is HTML escaped, and each page
 * gets the request's CSP nonce and CSRF token.
 */
@RestController
class OwnerPageController {

    private final String loginTemplate;
    private final String homeTemplate;

    OwnerPageController() {
        loginTemplate = load("static/owner-login.html");
        homeTemplate = load("static/owner.html");
    }

    @GetMapping(OwnerSecurityConfig.LOGIN_PATH)
    ResponseEntity<String> login(HttpServletRequest request, CsrfToken csrf) {
        return page(fill(loginTemplate, request, csrf));
    }

    /** The dashboard shell. Nothing about the owner goes into it; {@code owner.js} loads it all from the API. */
    @GetMapping(OwnerSecurityConfig.HOME_PATH)
    ResponseEntity<String> home(HttpServletRequest request, CsrfToken csrf) {
        return page(fill(homeTemplate, request, csrf));
    }

    private static String fill(String template, HttpServletRequest request, CsrfToken csrf) {
        return template.replace("{{CSP_NONCE}}", HtmlUtils.htmlEscape(CspNonce.of(request)))
                .replace("{{CSRF_PARAMETER}}", HtmlUtils.htmlEscape(csrf.getParameterName()))
                .replace("{{CSRF_HEADER}}", HtmlUtils.htmlEscape(csrf.getHeaderName()))
                .replace("{{CSRF_TOKEN}}", HtmlUtils.htmlEscape(csrf.getToken()));
    }

    private static ResponseEntity<String> page(String html) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .body(html);
    }

    private static String load(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Owner page template missing: " + path, e);
        }
    }
}
