package dev.tip.devices;

import org.springframework.test.context.TestPropertySource;

/** Cloudflare's {@code CF-Connecting-IP}. */
@TestPropertySource(properties = "TIP_CLIENT_IP_HEADER=CF-Connecting-IP")
class CfConnectingIpHeaderTest extends ClientIpHeaderTest {

    @Override
    String header() {
        return "CF-Connecting-IP";
    }
}
