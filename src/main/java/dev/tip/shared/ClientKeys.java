package dev.tip.shared;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The key a per client limit counts a client IP under. An IPv4 address is its own key. An IPv6 address is keyed by
 * its /64 prefix, because one home connection usually holds a whole /64 and could otherwise pick a fresh address for
 * every request. An IPv4 mapped IPv6 address counts as its IPv4 address.
 */
public final class ClientKeys {

    private static final int PREFIX_BYTES = 8;

    private ClientKeys() {}

    /** The limit key of a client IP as Tomcat resolved it ({@code getRemoteAddr()}), always an IP literal. */
    public static String of(String clientIp) {
        if (clientIp == null || clientIp.indexOf(':') < 0) {
            return clientIp;
        }
        InetAddress address;
        try {
            // In brackets the text can only be an IPv6 literal: parsed, never looked up.
            address = InetAddress.getByName("[" + clientIp + "]");
        } catch (UnknownHostException e) {
            return clientIp;
        }
        if (!(address instanceof Inet6Address)) {
            return address.getHostAddress();
        }
        byte[] bytes = address.getAddress();
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < PREFIX_BYTES; i += 2) {
            key.append(Integer.toHexString(((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff)))
                    .append(':');
        }
        return key.append(":/64").toString();
    }
}
