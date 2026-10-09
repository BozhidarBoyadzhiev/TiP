package dev.tip.shared;

import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * Settings every security filter chain shares: the CSP nonce, security headers, and problem details
 * for every 401 and 403 that Spring Security raises before a controller runs.
 */
public final class SecurityDefaults {

    private static final String PAYPAL_SOURCES = "https://*.paypal.com https://*.paypalobjects.com https://*.venmo.com";

    private SecurityDefaults() {}

    /**
     * Our origin plus the domains PayPal lists for its JS SDK. Inline scripts and styles run only with
     * the per request nonce, which the SDK applies to what it injects.
     */
    static String contentSecurityPolicy(String nonce) {
        String nonceSource = "'nonce-" + nonce + "'";
        return String.join(
                "; ",
                "default-src 'self'",
                "script-src 'self' " + nonceSource + " " + PAYPAL_SOURCES,
                "style-src 'self' " + nonceSource + " " + PAYPAL_SOURCES,
                "img-src 'self' data: " + PAYPAL_SOURCES,
                "connect-src 'self' " + PAYPAL_SOURCES,
                "frame-src " + PAYPAL_SOURCES,
                "child-src " + PAYPAL_SOURCES,
                "object-src 'none'",
                "base-uri 'self'",
                "form-action 'self'",
                "frame-ancestors 'none'");
    }

    /** Applies the shared headers and the problem details access denied handler. */
    public static HttpSecurity apply(HttpSecurity http, ProblemWriter problems) throws Exception {
        return http.addFilterBefore(new CspNonce(), HeaderWriterFilter.class)
                .headers(headers -> headers.addHeaderWriter((request, response) -> response.setHeader(
                                "Content-Security-Policy", contentSecurityPolicy(CspNonce.of(request))))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .httpStrictTransportSecurity(
                                hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(accessDeniedHandler(problems)));
    }

    /** 401 problem details with code {@code UNAUTHORIZED}. */
    public static AuthenticationEntryPoint unauthorized(ProblemWriter problems) {
        return (request, response, e) -> problems.write(
                request, response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Sign in to use this resource.");
    }

    /** 403 problem details with code {@code FORBIDDEN}, for paths nobody may use whether signed in or not. */
    static AuthenticationEntryPoint forbidden(ProblemWriter problems) {
        return (request, response, e) -> problems.write(
                request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access to this resource is denied.");
    }

    static AccessDeniedHandler accessDeniedHandler(ProblemWriter problems) {
        return (request, response, e) -> {
            if (e instanceof CsrfException) {
                problems.write(
                        request,
                        response,
                        HttpStatus.FORBIDDEN,
                        "CSRF_TOKEN_INVALID",
                        "The security token is missing or expired. Reload the page and try again.");
            } else {
                problems.write(
                        request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access to this resource is denied.");
            }
        };
    }
}
