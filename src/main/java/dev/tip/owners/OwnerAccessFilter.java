package dev.tip.owners;

import dev.tip.owners.api.OwnerPrincipal;
import dev.tip.shared.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The one place that enforces what an owner session may do beyond being signed in, in the owner security chain
 * after authorisation. An expired sandbox's session ends on its next request: the API and the owner channel answer
 * 401 {@code SANDBOX_EXPIRED}, a page goes to the sign in page. A read only session (the public demo) gets 403
 * {@code DEMO_READ_ONLY} for every {@code /api/owner/**} request that is not a GET, HEAD or OPTIONS, whatever the
 * endpoint, so a new one cannot forget the check. Signing out is not under {@code /api/owner} and stays possible.
 * Not a bean, so it is not registered for every request as a servlet filter.
 */
final class OwnerAccessFilter extends OncePerRequestFilter {

    static final String SANDBOX_EXPIRED = "SANDBOX_EXPIRED";
    static final String DEMO_READ_ONLY = "DEMO_READ_ONLY";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final RequestMatcher api = PathPatternRequestMatcher.withDefaults().matcher("/api/owner/**");
    private final RequestMatcher channel =
            PathPatternRequestMatcher.withDefaults().matcher(OwnerSecurityConfig.CHANNEL_PATH);
    private final RequestMatcher pages = request -> {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals(OwnerSecurityConfig.HOME_PATH)
                || (path.startsWith(OwnerSecurityConfig.HOME_PATH + "/")
                        && !path.equals(OwnerSecurityConfig.LOGIN_PATH)
                        && !path.equals(OwnerSecurityConfig.LOGOUT_PATH));
    };
    private final ProblemWriter problems;
    private final Clock clock;

    OwnerAccessFilter(ProblemWriter problems, Clock clock) {
        this.problems = problems;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof OwnerPrincipal owner) {
            boolean apiOrChannel = api.matches(request) || channel.matches(request);
            if (owner.expiredAt(clock.instant()) && (apiOrChannel || pages.matches(request))) {
                endSession(request);
                if (apiOrChannel) {
                    problems.write(
                            request, response, HttpStatus.UNAUTHORIZED, SANDBOX_EXPIRED, "This sandbox has expired.");
                } else {
                    response.sendRedirect(OwnerSecurityConfig.LOGIN_PATH + "?sandboxExpired");
                }
                return;
            }
            if (owner.readOnly() && api.matches(request) && !SAFE_METHODS.contains(request.getMethod())) {
                problems.write(
                        request, response, HttpStatus.FORBIDDEN, DEMO_READ_ONLY, "The demo dashboard is read only.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static void endSession(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
