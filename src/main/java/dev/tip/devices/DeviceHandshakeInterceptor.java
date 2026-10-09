package dev.tip.devices;

import dev.tip.devices.api.DeviceSecretVerifier;
import dev.tip.devices.api.DeviceSummary;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Authenticates the device channel handshake: {@code /ws/device/{deviceId}} with {@code Authorization:
 * Bearer <deviceSecret>}. A malformed id, a missing header, an unknown device and a wrong secret all
 * get the same empty 401, and all go through the verifier so they take the same time.
 */
@Component
class DeviceHandshakeInterceptor implements HandshakeInterceptor {

    static final String DEVICE = DeviceHandshakeInterceptor.class.getName() + ".device";

    private static final Logger log = LoggerFactory.getLogger(DeviceHandshakeInterceptor.class);

    private static final String BEARER = "bearer ";

    private final DeviceSecretVerifier verifier;
    private final HandshakeFailureLimiter limiter;

    DeviceHandshakeInterceptor(DeviceSecretVerifier verifier, HandshakeFailureLimiter limiter) {
        this.verifier = verifier;
        this.limiter = limiter;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Map<String, Object> attributes) {
        String clientIp = ClientIps.of(request);
        if (limiter.blocked(clientIp)) {
            response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return false;
        }
        Optional<DeviceSummary> device =
                verifier.verify(deviceId(request.getURI().getPath()), bearerToken(request.getHeaders()));
        if (device.isEmpty()) {
            limiter.recordFailure(clientIp);
            log.debug("Rejected a device handshake");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(DEVICE, device.get());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {}

    /** The id from the last path segment when it is a canonical lowercase uuid, otherwise null. */
    private static UUID deviceId(String path) {
        return Uuids.parse(path.substring(path.lastIndexOf('/') + 1));
    }

    private static String bearerToken(HttpHeaders headers) {
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.length() <= BEARER.length()) {
            return null;
        }
        boolean bearer = authorization.regionMatches(true, 0, BEARER, 0, BEARER.length());
        return bearer ? authorization.substring(BEARER.length()).strip() : null;
    }
}
