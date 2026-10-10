package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/** The app with the public base URL it gets on Render: HTTPS, no port, written with a trailing slash. */
@TestPropertySource(properties = "TIP_PUBLIC_BASE_URL=https://tip-demo.onrender.com/")
class HttpsBaseUrlTest extends IntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestData data;

    @Test
    void observerAcceptsTheHttpsOriginOfTheBaseUrl() throws Exception {
        String slug = TestData.uniqueSlug();
        data.device(slug, "Panda");

        try (RawWebSocket observer = observe(slug, "https://tip-demo.onrender.com")) {
            assertThat(observer.status).isEqualTo(101);
        }
    }

    @Test
    void observerRejectsThePlainHttpAndLocalOrigins() throws Exception {
        String slug = TestData.uniqueSlug();
        data.device(slug, "Panda");

        for (String origin : new String[] {"http://tip-demo.onrender.com", "http://localhost:8080"}) {
            try (RawWebSocket observer = observe(slug, origin)) {
                assertThat(observer.status).as(origin).isEqualTo(403);
            }
        }
    }

    private RawWebSocket observe(String slug, String origin) throws Exception {
        return RawWebSocket.handshake(
                port,
                "/ws/observe/" + slug,
                Map.of("Origin", origin, "X-Forwarded-For", TestData.uniquePublicIp(), "X-Forwarded-Proto", "https"));
    }
}
