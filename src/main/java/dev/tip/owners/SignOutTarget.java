package dev.tip.owners;

import dev.tip.owners.api.OwnerKind;
import dev.tip.owners.api.OwnerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.web.util.UriUtils;

/**
 * Where a browser goes after signing out. A sandbox goes back to the access code page, the public demo to the real
 * panda's tip page ({@code TIP_DEMO_DEVICE_SLUG}) or, without one, the sign in page, and a real owner to the sign
 * in page.
 */
final class SignOutTarget implements LogoutSuccessHandler {

    static final String SIGNED_OUT = OwnerSecurityConfig.LOGIN_PATH + "?signedOut";

    private final String demoTarget;

    SignOutTarget(String realPandaSlug) {
        demoTarget = realPandaSlug.isBlank()
                ? SIGNED_OUT
                : "/t/" + UriUtils.encodePathSegment(realPandaSlug.strip(), StandardCharsets.UTF_8);
    }

    String targetFor(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof OwnerPrincipal owner) {
            if (owner.kind() == OwnerKind.SANDBOX) {
                return OwnerSecurityConfig.JUDGES_PATH;
            }
            if (owner.kind() == OwnerKind.PUBLIC_DEMO) {
                return demoTarget;
            }
        }
        return SIGNED_OUT;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        response.sendRedirect(request.getContextPath() + targetFor(authentication));
    }
}
