package dev.tip.devices;

import dev.tip.devices.api.DeviceDirectory;
import dev.tip.devices.api.DeviceSlug;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Public observer handshake, {@code /ws/observe/{deviceSlug}}: 404 for a malformed or unknown slug, 429
 * for a sixth open connection from one IP. The origin check runs before this, see {@link WebSocketConfig}.
 */
@Component
class ObserverHandshakeInterceptor implements HandshakeInterceptor {

    static final String SLUG = ObserverHandshakeInterceptor.class.getName() + ".slug";
    static final String RESERVATION = ObserverHandshakeInterceptor.class.getName() + ".reservation";

    private static final Pattern SLUG_PATTERN = Pattern.compile(DeviceSlug.PATTERN);

    private final DeviceDirectory devices;
    private final ObserverConnectionLimiter limiter;

    ObserverHandshakeInterceptor(DeviceDirectory devices, ObserverConnectionLimiter limiter) {
        this.devices = devices;
        this.limiter = limiter;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        String slug = path.substring(path.lastIndexOf('/') + 1);
        if (!SLUG_PATTERN.matcher(slug).matches() || devices.findBySlug(slug).isEmpty()) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        ObserverConnectionLimiter.Reservation reservation = limiter.tryReserve(ClientIps.of(request));
        if (reservation == null) {
            response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return false;
        }
        attributes.put(SLUG, slug);
        attributes.put(RESERVATION, reservation);
        // afterHandshake gets no attributes map, so it finds the reservation on the request.
        servletRequest(request).setAttribute(RESERVATION, reservation);
        return true;
    }

    /** Gives the place back when the upgrade did not happen; an open session gives it back on close. */
    @Override
    public void afterHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {
        int status = ((ServletServerHttpResponse) response).getServletResponse().getStatus();
        if (exception != null || status != HttpStatus.SWITCHING_PROTOCOLS.value()) {
            if (servletRequest(request).getAttribute(RESERVATION)
                    instanceof ObserverConnectionLimiter.Reservation reservation) {
                reservation.release();
            }
        }
    }

    private static HttpServletRequest servletRequest(ServerHttpRequest request) {
        return ((ServletServerHttpRequest) request).getServletRequest();
    }
}
