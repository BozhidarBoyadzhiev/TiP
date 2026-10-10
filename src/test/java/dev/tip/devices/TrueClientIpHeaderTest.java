package dev.tip.devices;

import org.springframework.test.context.TestPropertySource;

/** Cloudflare's {@code True-Client-IP}, which Render support recommends for the client address. */
@TestPropertySource(properties = "TIP_CLIENT_IP_HEADER=True-Client-IP")
class TrueClientIpHeaderTest extends ClientIpHeaderTest {

    @Override
    String header() {
        return "True-Client-IP";
    }
}
