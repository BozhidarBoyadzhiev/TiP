package dev.tip.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Tab icons, page titles and the link preview tags crawlers read from the server rendered HTML. */
class LinkPreviewTest extends IntegrationTest {

    /** {@code TIP_PUBLIC_BASE_URL} in the test profile. */
    private static final String BASE_URL = "http://localhost:8080";

    private static final Map<String, String> TITLES = Map.of(
            "/t/demo-panda", "TiP | Leave a tip",
            "/p/demo-panda", "TiP | Panda",
            "/nope", "TiP | Not found",
            "/owner/login", "TiP | Owner login");

    private static final String[] ICON_TAGS = {
        "<link rel=\"icon\" href=\"/favicon.ico\" sizes=\"48x48\">",
        "<link rel=\"icon\" href=\"/icons/favicon.svg\" type=\"image/svg+xml\">",
        "<link rel=\"apple-touch-icon\" href=\"/icons/apple-touch-icon.png\">",
        "<meta name=\"theme-color\" content=\"#120c09\">"
    };

    @Autowired
    MockMvc mvc;

    @LocalServerPort
    int port;

    @Test
    void iconsAndPreviewImageAreServedWithoutASessionAndCachedForThirtyDays() throws Exception {
        Map<String, String> types = Map.of(
                "/favicon.ico", "image/x-icon",
                "/icons/favicon.svg", "image/svg+xml",
                "/icons/apple-touch-icon.png", "image/png",
                "/icons/icon-512.png", "image/png",
                "/images/og-image.png", "image/png");
        for (Map.Entry<String, String> each : types.entrySet()) {
            mvc.perform(get(each.getKey()).accept(MediaType.TEXT_HTML, MediaType.ALL))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(each.getValue()))
                    .andExpect(header().string("Cache-Control", "max-age=2592000, public"))
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andExpect(r -> assertThat(r.getResponse().getContentAsByteArray())
                            .as(each.getKey())
                            .isNotEmpty());
        }
    }

    @Test
    void everyPageHasTheIconsThemeColourAndItsTitle() throws Exception {
        for (Map.Entry<String, String> each : TITLES.entrySet()) {
            String page = page(each.getKey());

            assertThat(page)
                    .as(each.getKey())
                    .contains("<title>" + each.getValue() + "</title>")
                    .contains(ICON_TAGS);
        }
    }

    @Test
    void scriptsLeaveTheTitleAlone() throws Exception {
        for (String script :
                new String[] {"/js/tip.js", "/js/panda.js", "/js/brand.js", "/js/owner.js", "/js/owner-login.js"}) {
            assertThat(mvc.perform(get(script)).andReturn().getResponse().getContentAsString())
                    .as(script)
                    .doesNotContain("document.title");
        }
    }

    @Test
    void notFoundAndLoginPagesHaveNoPreviewTags() throws Exception {
        for (String path : new String[] {"/nope", "/t/no-such-panda", "/owner/login"}) {
            assertThat(page(path)).as(path).doesNotContain("og:", "twitter:", "{{OG_");
        }
    }

    @Test
    void tipPageHasItsPreviewTagsWithAbsoluteUrlsFromTheBaseUrl() throws Exception {
        String page = page("/t/demo-panda");

        assertThat(page)
                .contains(meta("og:type", "website"), meta("og:site_name", "TiP"))
                .contains(meta("og:title", "TiP | Leave a tip"))
                .contains(meta("og:description", "Tap the coin, leave a note, and watch the panda react."))
                .contains(meta("og:url", BASE_URL + "/t/demo-panda"))
                .contains(previewImageTags())
                .contains("<meta name=\"twitter:card\" content=\"summary_large_image\">");
    }

    @Test
    void pandaPageHasItsPreviewTagsWithAbsoluteUrlsFromTheBaseUrl() throws Exception {
        String page = page("/p/demo-panda");

        assertThat(page)
                .contains(meta("og:type", "website"), meta("og:site_name", "TiP"))
                .contains(meta("og:title", "TiP | Panda"))
                .contains(meta("og:description", "A cardboard panda that lights up for every tip."))
                .contains(meta("og:url", BASE_URL + "/p/demo-panda"))
                .contains(previewImageTags())
                .contains("<meta name=\"twitter:card\" content=\"summary_large_image\">");
    }

    @Test
    void forgedHostAndScriptInTheQueryNeverReachThePage() throws Exception {
        for (String path : new String[] {"/t/demo-panda", "/p/demo-panda"}) {
            String response = rawGet(path + "?q=%3Cscript%3Ealert(1)%3C%2Fscript%3E");

            assertThat(response).as(path).startsWith("HTTP/1.1 200");
            String body = response.substring(response.indexOf("\r\n\r\n"));
            assertThat(body)
                    .as(path)
                    .contains(meta("og:url", BASE_URL + path))
                    .contains(meta("og:image", BASE_URL + "/images/og-image.png"))
                    .doesNotContain("evil.example", "alert(", "%3C", "&lt;script", "{{");
        }
    }

    private String page(String path) throws Exception {
        return mvc.perform(get(path).accept(MediaType.TEXT_HTML))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** A real request whose {@code Host} and forwarded host name another site. */
    private String rawGet(String target) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + target + " HTTP/1.1\r\n"
                            + "Host: evil.example\r\n"
                            + "X-Forwarded-Host: evil.example\r\n"
                            + "Accept: text/html\r\n"
                            + "Connection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String[] previewImageTags() {
        return new String[] {
            meta("og:image", BASE_URL + "/images/og-image.png"),
            meta("og:image:width", "1200"),
            meta("og:image:height", "630"),
            meta("og:image:type", "image/png"),
            meta("og:image:alt", "A cardboard panda holding a gold TiP coin")
        };
    }

    private static String meta(String property, String value) {
        return "<meta property=\"" + property + "\" content=\"" + value + "\">";
    }
}
