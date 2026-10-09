package dev.tip.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A fresh random nonce per request, used in the Content Security Policy and on the page's script tags.
 * The PayPal JS SDK reads it from {@code data-csp-nonce} to mark its own inline scripts and styles.
 */
public final class CspNonce extends OncePerRequestFilter {

    private static final String ATTRIBUTE = CspNonce.class.getName();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The nonce of the current request. */
    public static String of(HttpServletRequest request) {
        Object nonce = request.getAttribute(ATTRIBUTE);
        if (nonce == null) {
            throw new IllegalStateException("CspNonce filter did not run for this request");
        }
        return (String) nonce;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        request.setAttribute(ATTRIBUTE, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        chain.doFilter(request, response);
    }
}
