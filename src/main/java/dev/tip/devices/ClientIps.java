package dev.tip.devices;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;

/**
 * The client IP of a handshake: the address Tomcat resolved from the configured client IP header, the same
 * one every HTTP rate limit uses. See {@code server.tomcat.remoteip.remote-ip-header}.
 */
final class ClientIps {

    private ClientIps() {}

    static String of(ServerHttpRequest request) {
        return ((ServletServerHttpRequest) request).getServletRequest().getRemoteAddr();
    }
}
