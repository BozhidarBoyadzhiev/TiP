package dev.tip.dashboard;

import dev.tip.owners.api.OwnerPrincipal;
import dev.tip.shared.TipProperties;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * The owner channel handshake, {@code /ws/owner}. The owner security chain has already turned away a
 * request without a signed in owner with 401. Here the {@code Origin} header must be exactly the origin of
 * {@code TIP_PUBLIC_BASE_URL}, or the answer is an empty 403: a page on another site cannot open the
 * channel with the owner's cookie (cross site WebSocket hijacking), and unlike Spring's own origin check a
 * missing {@code Origin} is refused too. The owner, the HTTP session and whether the session is read only go into
 * the socket's attributes. An expired sandbox never gets here: the owner chain answers 401 first.
 */
@Component
class OwnerHandshakeInterceptor implements HandshakeInterceptor {

    static final String OWNER_ID = OwnerHandshakeInterceptor.class.getName() + ".ownerId";
    static final String HTTP_SESSION = OwnerHandshakeInterceptor.class.getName() + ".httpSession";
    static final String READ_ONLY = OwnerHandshakeInterceptor.class.getName() + ".readOnly";

    private final String publicOrigin;

    OwnerHandshakeInterceptor(TipProperties tipProperties) {
        this.publicOrigin = tipProperties.publicOrigin();
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Map<String, Object> attributes) {
        List<String> origins = request.getHeaders().getOrDefault(HttpHeaders.ORIGIN, List.of());
        if (origins.size() != 1 || !publicOrigin.equals(origins.getFirst())) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        HttpSession httpSession =
                ((ServletServerHttpRequest) request).getServletRequest().getSession(false);
        if (!(request.getPrincipal() instanceof Authentication authentication)
                || !(authentication.getPrincipal() instanceof OwnerPrincipal owner)
                || httpSession == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(OWNER_ID, owner.id());
        attributes.put(HTTP_SESSION, httpSession);
        attributes.put(READ_ONLY, owner.readOnly());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {}
}
