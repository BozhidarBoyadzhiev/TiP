package dev.tip.owners;

import dev.tip.owners.api.OwnerKind;
import dev.tip.owners.api.OwnerPrincipal;
import dev.tip.shared.LogSafe;
import dev.tip.shared.TipProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign ins without a password: a sandbox from an access code, the same sandbox on another device through a one
 * time resume link, and the public read only demo. Each is a post in the owner security chain, so
 * CSRF and the session work as for the password sign in, and each is rate limited per client IP. Codes, tokens and
 * IPs never reach a log line; IPs only as {@link LogSafe#ip}.
 */
@RestController
class SandboxController {

    static final String SESSION_PATH = "/judges/session";
    static final String RESUME_PATH = "/judges/resume";
    static final String DEMO_PATH = "/demo/session";
    static final String RESUME_LINK_PATH = "/api/owner/resume-link";

    static final Duration RESUME_LIFETIME = Duration.ofMinutes(10);
    static final int RESUME_TOKEN_BYTES = 16;
    static final int MAX_ZONE_LENGTH = 64;

    /** Well above the 22 characters of a token, so a malformed one still gets the answer of an unknown one. */
    static final int MAX_TOKEN_LENGTH = 64;

    /** {@code UTC} rather than {@code Z}, which the browser's {@code Intl} does not accept as a zone. */
    static final ZoneId FALLBACK_ZONE = ZoneId.of("UTC");

    private static final Logger log = LoggerFactory.getLogger(SandboxController.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccessCodes codes;
    private final Sandboxes sandboxes;
    private final PublicDemo publicDemo;
    private final ResumeTokens resumeTokens;
    private final AccessRateLimits limits;
    private final OwnerSignIn signIn;
    private final String baseUrl;
    private final Clock clock;

    SandboxController(
            AccessCodes codes,
            Sandboxes sandboxes,
            PublicDemo publicDemo,
            ResumeTokens resumeTokens,
            AccessRateLimits limits,
            OwnerSignIn signIn,
            TipProperties tipProperties,
            Clock clock) {
        this.codes = codes;
        this.sandboxes = sandboxes;
        this.publicDemo = publicDemo;
        this.resumeTokens = resumeTokens;
        this.limits = limits;
        this.signIn = signIn;
        this.baseUrl = tipProperties.publicBaseUrl().strip().replaceAll("/+$", "");
        this.clock = clock;
    }

    /** A wrong and an expired code get the same 401 {@code LOGIN_FAILED}. */
    @PostMapping(SESSION_PATH)
    ResponseEntity<Void> judgeSession(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String timeZone,
            HttpServletRequest request,
            HttpServletResponse response) {
        String clientIp = request.getRemoteAddr();
        if (!limits.judgeSession(clientIp)) {
            throw new OwnerExceptions.RateLimited();
        }
        Optional<AccessCodes.Entry> entry = codes.match(code, clock.instant());
        if (entry.isEmpty()) {
            log.warn("code_rejected ip={}", LogSafe.ip(clientIp));
            throw new OwnerExceptions.CodeRejected();
        }
        OwnerPrincipal owner = sandboxes.create(entry.get(), zone(timeZone));
        log.info("sandbox_created label={} owner={}", entry.get().label(), owner.id());
        signIn.signIn(owner, request, response);
        return redirect(HttpStatus.FOUND, OwnerSecurityConfig.HOME_PATH);
    }

    /** The public demo, read only. */
    @PostMapping(DEMO_PATH)
    ResponseEntity<Void> demoSession(HttpServletRequest request, HttpServletResponse response) {
        String clientIp = request.getRemoteAddr();
        if (!limits.demoSession(clientIp)) {
            throw new OwnerExceptions.RateLimited();
        }
        OwnerPrincipal owner = publicDemo.principal().orElseThrow(OwnerExceptions.DemoUnavailable::new);
        signIn.signIn(owner, request, response);
        log.info("demo_session ip={}", LogSafe.ip(clientIp));
        return redirect(HttpStatus.FOUND, OwnerSecurityConfig.HOME_PATH);
    }

    /**
     * A link that signs another browser in to this sandbox once, within 10 minutes, with its QR code. Any other owner
     * gets the 404 of a path that does not exist. Each sandbox makes a few links per 10 minutes.
     */
    @PostMapping(RESUME_LINK_PATH)
    ResponseEntity<ResumeLink> resumeLink(@AuthenticationPrincipal OwnerPrincipal owner) {
        if (owner.kind() != OwnerKind.SANDBOX) {
            throw new OwnerExceptions.SandboxOnly();
        }
        if (!limits.resumeLink(owner.id())) {
            throw new OwnerExceptions.RateLimited();
        }
        byte[] token = new byte[RESUME_TOKEN_BYTES];
        RANDOM.nextBytes(token);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant limit = now.plus(RESUME_LIFETIME);
        Instant expiresAt = owner.expiresAt().isBefore(limit) ? owner.expiresAt() : limit;
        resumeTokens.store(sha256(token), owner.id(), now, expiresAt);
        String url = baseUrl + RESUME_PATH + "#t="
                + Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ResumeLink(url, expiresAt, QrMatrix.of(url)));
    }

    /**
     * Consumes the token from the JSON body and signs the browser in; the resume page posts it on a tap, never on
     * load, so a link preview that fetches the page or runs its script spends nothing. A malformed, unknown, used or
     * expired token, and one whose sandbox expired, all get the same 401 {@code RESUME_FAILED}.
     */
    @PostMapping(RESUME_PATH)
    ResponseEntity<Void> resume(
            @Valid @RequestBody ResumeRequest body, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.setHeader("Referrer-Policy", "no-referrer");
        if (!limits.resume(request.getRemoteAddr())) {
            throw new OwnerExceptions.RateLimited();
        }
        Optional<OwnerPrincipal> owner = decode(body.token())
                .flatMap(bytes -> resumeTokens.consume(sha256(bytes), clock.instant()))
                .flatMap(sandboxes::active);
        if (owner.isEmpty()) {
            throw new OwnerExceptions.ResumeFailed();
        }
        signIn.signIn(owner.get(), request, response);
        log.info("resume_link_used owner={}", owner.get().id());
        return ResponseEntity.noContent().build();
    }

    /** The browser's zone when {@link ZoneId} knows it, otherwise UTC. */
    static ZoneId zone(String timeZone) {
        if (timeZone == null || timeZone.isBlank() || timeZone.length() > MAX_ZONE_LENGTH) {
            return FALLBACK_ZONE;
        }
        try {
            return ZoneId.of(timeZone.strip());
        } catch (DateTimeException e) {
            return FALLBACK_ZONE;
        }
    }

    private static Optional<byte[]> decode(String token) {
        if (token == null) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            return bytes.length == RESUME_TOKEN_BYTES ? Optional.of(bytes) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static byte[] sha256(byte[] token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static ResponseEntity<Void> redirect(HttpStatus status, String location) {
        return ResponseEntity.status(status).location(URI.create(location)).build();
    }

    record ResumeLink(String url, Instant expiresAt, QrMatrix qr) {}

    record ResumeRequest(@Size(max = MAX_TOKEN_LENGTH) String token) {}
}
