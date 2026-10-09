package dev.tip.owners;

import dev.tip.shared.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs in the owner security chain just before the login filter and answers 429 problem details when
 * the client IP or the email has used up its attempts. Not a bean, so it is not registered for every
 * request as a servlet filter.
 */
final class LoginRateLimitFilter extends OncePerRequestFilter {

    private final RequestMatcher loginRequest;
    private final LoginRateLimiter limiter;
    private final ProblemWriter problems;

    LoginRateLimitFilter(String loginPath, LoginRateLimiter limiter, ProblemWriter problems) {
        this.loginRequest = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, loginPath);
        this.limiter = limiter;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (loginRequest.matches(request)
                && !limiter.tryAcquire(request.getRemoteAddr(), request.getParameter(OwnerSecurityConfig.EMAIL))) {
            problems.write(
                    request,
                    response,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMITED",
                    "Too many sign in attempts. Please wait a minute and try again.");
            return;
        }
        chain.doFilter(request, response);
    }
}
