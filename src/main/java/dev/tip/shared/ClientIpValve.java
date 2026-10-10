package dev.tip.shared;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

/**
 * Runs in front of Tomcat's {@code RemoteIpValve}. The {@code RemoteIpValve} splits any client IP header on
 * commas and takes whatever is left of it as the client address, without checking that it is an address. For
 * a single value header such as {@code True-Client-IP} this valve therefore drops the header unless it is sent
 * once and holds exactly one IP literal, so a list, a hostname or garbage is never a rate limit key and the
 * {@code RemoteIpValve} keeps the connection address. {@code X-Forwarded-For} is a list by design and passes
 * unchanged.
 *
 * <p>With diagnostics on it also records the connection address and the raw proxy headers for
 * {@link ClientIpDiagnostics}, because the {@code RemoteIpValve} rewrites the header it reads.
 */
final class ClientIpValve extends ValveBase {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4 = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");
    private static final Pattern IPV6_CHARACTERS = Pattern.compile("[0-9A-Fa-f:.]+");

    private final String header;
    private final boolean diagnostics;

    ClientIpValve(String header, boolean diagnostics) {
        super(true);
        this.header = header;
        this.diagnostics = diagnostics;
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        if (diagnostics) {
            request.setAttribute(ClientIpDiagnostics.ATTRIBUTE, ClientIpDiagnostics.Snapshot.of(request));
        }
        if (!header.equalsIgnoreCase(FORWARDED_FOR)
                && !isSingleIpLiteral(Collections.list(request.getHeaders(header)))) {
            request.getCoyoteRequest().getMimeHeaders().removeHeader(header);
        }
        getNext().invoke(request, response);
    }

    private static boolean isSingleIpLiteral(List<String> values) {
        return values.size() == 1 && isIpLiteral(values.getFirst().strip());
    }

    /** Never resolves a name: a value with a colon is parsed as an IPv6 literal, anything else must be IPv4. */
    static boolean isIpLiteral(String value) {
        if (IPV4.matcher(value).matches()) {
            return true;
        }
        if (!value.contains(":") || !IPV6_CHARACTERS.matcher(value).matches()) {
            return false;
        }
        try {
            InetAddress.getByName(value);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
