package dev.tip.devices;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;

/**
 * The client IP of a handshake: the address Tomcat resolved from the proxy's {@code X-Forwarded-For}, the
 * same one every HTTP rate limit uses. See {@code server.forward-headers-strategy}.
 */
final class ClientIps {

    private ClientIps() {}

    static String of(ServerHttpRequest request) {
        return ((ServletServerHttpRequest) request).getServletRequest().getRemoteAddr();
    }
}
