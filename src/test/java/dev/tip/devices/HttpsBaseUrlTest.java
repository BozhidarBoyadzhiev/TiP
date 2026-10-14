package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The app with the public base URL it gets on Render: HTTPS, no port, written with a trailing slash. */
@TestPropertySource(properties = "TIP_PUBLIC_BASE_URL=https://tip-demo.onrender.com/")
class HttpsBaseUrlTest extends IntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestData data;

    @Autowired
    MockMvc mvc;

    @Test
    void previewTagsUseTheBaseUrlWithoutItsTrailingSlash() throws Exception {
        for (String path : new String[] {"/t/demo-panda", "/p/demo-panda"}) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getContentAsString())
                    .as(path)
                    .contains("<meta property=\"og:url\" content=\"https://tip-demo.onrender.com" + path + "\">")
                    .contains(
                            "<meta property=\"og:image\" content=\"https://tip-demo.onrender.com/images/og-image-panda.png\">");
        }
    }

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
