package dev.tip.shared;

import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * Spring's handshake handler, except that a request without a proper upgrade gets problem details with
 * a fixed detail instead of the framework's own wording.
 */
@Component
public class ProblemHandshakeHandler extends DefaultHandshakeHandler {

    private final ProblemWriter problems;

    public ProblemHandshakeHandler(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    protected void handleInvalidUpgradeHeader(ServerHttpRequest request, ServerHttpResponse response)
            throws IOException {
        badRequest(request, response);
    }

    @Override
    protected void handleInvalidConnectHeader(ServerHttpRequest request, ServerHttpResponse response)
            throws IOException {
        badRequest(request, response);
    }

    private void badRequest(ServerHttpRequest request, ServerHttpResponse response) throws IOException {
        problems.write(
                ((ServletServerHttpRequest) request).getServletRequest(),
                ((ServletServerHttpResponse) response).getServletResponse(),
                HttpStatus.BAD_REQUEST,
                "BAD_REQUEST",
                "The request is not valid.");
    }
}
