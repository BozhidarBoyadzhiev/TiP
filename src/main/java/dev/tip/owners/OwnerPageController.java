package dev.tip.owners;

import dev.tip.owners.api.OwnerKind;
import dev.tip.owners.api.OwnerPrincipal;
import dev.tip.shared.CspNonce;
import dev.tip.shared.SecurityDefaults;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;

/**
 * Serves the owner pages from static templates. Every substituted value is HTML escaped, and each page
 * gets the request's CSP nonce and CSRF token.
 */
@RestController
class OwnerPageController {

    private final String loginTemplate;
    private final String homeTemplate;
    private final String judgesTemplate;
    private final String demoTemplate;
    private final String resumeTemplate;
    private final String liveTemplate;
    private final String realPandaPath;
    private final Clock clock;

    /**
     * {@code realPandaSlug} is the physical panda's slug, {@code TIP_DEMO_DEVICE_SLUG}; the code page and the
     * public demo link to its tip page, and leave the link out without one.
     */
    OwnerPageController(@Value("${tip.demo-device.slug:}") String realPandaSlug, Clock clock) {
        loginTemplate = load("static/owner-login.html");
        homeTemplate = load("static/owner.html");
        judgesTemplate = load("static/judges.html");
        demoTemplate = load("static/demo.html");
        resumeTemplate = load("static/resume.html");
        liveTemplate = load("static/live.html");
        realPandaPath = realPandaSlug.isBlank()
                ? ""
                : "/t/" + UriUtils.encodePathSegment(realPandaSlug.strip(), StandardCharsets.UTF_8);
        this.clock = clock;
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

    /**
     * The side by side view of a judge's sandbox: the tip page, the panda and the dashboard in frames, which
     * {@code live.js} points at the sandbox's panda. Nothing about the owner goes into it. Any other owner gets
     * the answer of a path that does not exist.
     */
    @GetMapping(SecurityDefaults.LIVE_PATH)
    ResponseEntity<String> live(
            @AuthenticationPrincipal OwnerPrincipal owner, HttpServletRequest request, CsrfToken csrf)
            throws NoResourceFoundException {
        if (owner.kind() != OwnerKind.SANDBOX) {
            throw new NoResourceFoundException(HttpMethod.GET, request.getRequestURI(), SecurityDefaults.LIVE_PATH);
        }
        return page(fill(liveTemplate, request, csrf));
    }

    /** The access code page; {@code ?sandboxExpired} is read by its script. */
    @GetMapping(OwnerSecurityConfig.JUDGES_PATH)
    ResponseEntity<String> judges(HttpServletRequest request, CsrfToken csrf) {
        return page(fill(judgesTemplate, request, csrf));
    }

    /**
     * The resume link's page, the same for any query, and for HEAD. The token is in the fragment, which never
     * reaches the server; {@code resume.js} posts it on a tap, so fetching the page changes nothing.
     */
    @GetMapping(SandboxController.RESUME_PATH)
    ResponseEntity<String> resume(HttpServletRequest request, CsrfToken csrf) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(fill(resumeTemplate, request, csrf));
    }

    /**
     * Opens the public demo from a plain link: the page posts {@code /demo/session}. A browser already signed in to
     * an owner that has not expired goes to its own dashboard instead, so a link never swaps a judge's sandbox
     * session for the demo.
     */
    @GetMapping(OwnerSecurityConfig.DEMO_PAGE_PATH)
    ResponseEntity<String> demo(
            @AuthenticationPrincipal OwnerPrincipal owner, HttpServletRequest request, CsrfToken csrf) {
        if (owner != null && !owner.expiredAt(clock.instant())) {
            return ResponseEntity.status(302)
                    .location(URI.create(OwnerSecurityConfig.HOME_PATH))
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        return page(fill(demoTemplate, request, csrf));
    }

    private String fill(String template, HttpServletRequest request, CsrfToken csrf) {
        return template.replace("{{CSP_NONCE}}", HtmlUtils.htmlEscape(CspNonce.of(request)))
                .replace("{{CSRF_PARAMETER}}", HtmlUtils.htmlEscape(csrf.getParameterName()))
                .replace("{{CSRF_HEADER}}", HtmlUtils.htmlEscape(csrf.getHeaderName()))
                .replace("{{CSRF_TOKEN}}", HtmlUtils.htmlEscape(csrf.getToken()))
                .replace("{{REAL_PANDA_PATH}}", HtmlUtils.htmlEscape(realPandaPath));
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
