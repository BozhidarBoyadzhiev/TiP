package dev.tip.owners;

import dev.tip.owners.api.OwnerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * Signs a browser in as an owner without a password, for a sandbox, a resume link and the public demo. The same
 * session the form login makes: the session id changes and the CSRF token is renewed, as on a password sign in, and
 * the security context is stored in the HTTP session. A sandbox session's idle timeout never reaches past the
 * sandbox's expiry; {@link OwnerAccessFilter} enforces the expiry itself on every request.
 */
@Component
class OwnerSignIn {

    private final SessionAuthenticationStrategy sessionStrategy = new CompositeSessionAuthenticationStrategy(List.of(
            new ChangeSessionIdAuthenticationStrategy(),
            new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository())));
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final Clock clock;

    OwnerSignIn(Clock clock) {
        this.clock = clock;
    }

    void signIn(OwnerPrincipal owner, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(owner, null, owner.getAuthorities());
        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        HttpSession session = request.getSession();
        if (owner.expiresAt() != null) {
            long left = Math.max(
                    1, Duration.between(clock.instant(), owner.expiresAt()).toSeconds());
            int idle = session.getMaxInactiveInterval();
            session.setMaxInactiveInterval((int) Math.min(idle > 0 ? idle : Integer.MAX_VALUE, left));
        }
    }
}
