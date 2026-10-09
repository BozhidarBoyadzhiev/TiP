package dev.tip.devices;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;

/** The client IP of a handshake, after the forwarded headers filter applied the proxy's headers. */
final class ClientIps {

    private ClientIps() {}

    static String of(ServerHttpRequest request) {
        return ((ServletServerHttpRequest) request).getServletRequest().getRemoteAddr();
    }
}
