package dev.tip.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.catalina.connector.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Temporary check, on only with {@code TIP_DEBUG_CLIENT_IP=true}: logs one line per public API request with
 * the connection address, the resolved client address and the raw proxy headers, so the owner can see which
 * header a client cannot forge on a new host. It writes client IPs to the log, so it stays off otherwise.
 */
final class ClientIpDiagnostics extends OncePerRequestFilter {

    static final String ATTRIBUTE = ClientIpDiagnostics.class.getName();
    static final List<String> HEADERS = List.of("X-Forwarded-For", "X-Real-IP", "True-Client-IP", "CF-Connecting-IP");
    static final int MAX_VALUE_LENGTH = 200;

    private static final Logger log = LoggerFactory.getLogger(ClientIpDiagnostics.class);

    /**
     * What the request looked like before Tomcat resolved the client, taken by {@link ClientIpValve}. A header
     * value is {@code null} when the header is absent.
     */
    record Snapshot(String connectionAddress, List<String> headerValues) {

        static Snapshot of(Request request) {
            List<String> values = new ArrayList<>();
            for (String name : HEADERS) {
                List<String> raw = Collections.list(request.getHeaders(name));
                values.add(raw.isEmpty() ? null : String.join(", ", raw));
            }
            return new Snapshot(request.getPeerAddr(), Collections.unmodifiableList(values));
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getAttribute(ATTRIBUTE) instanceof Snapshot snapshot) {
            StringBuilder line = new StringBuilder("Client IP check: connection=")
                    .append(truncate(snapshot.connectionAddress()))
                    .append(" resolved=")
                    .append(truncate(request.getRemoteAddr()));
            for (int i = 0; i < HEADERS.size(); i++) {
                String value = snapshot.headerValues().get(i);
                line.append(' ')
                        .append(HEADERS.get(i))
                        .append('=')
                        .append(value == null ? "-" : "\"" + truncate(value) + "\"");
            }
            log.info("{}", line);
        }
        chain.doFilter(request, response);
    }

    private static String truncate(String value) {
        return value.length() <= MAX_VALUE_LENGTH ? value : value.substring(0, MAX_VALUE_LENGTH);
    }
}
