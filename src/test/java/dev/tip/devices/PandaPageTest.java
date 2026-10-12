package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class PandaPageTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void servesThePandaPageWithItsNonce() throws Exception {
        mvc.perform(get("/p/demo-panda"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("/js/panda.js")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(result -> {
                    String policy = result.getResponse().getHeader("Content-Security-Policy");
                    String nonce = policy.replaceAll(".*script-src 'self' 'nonce-([A-Za-z0-9_-]+)'.*", "$1");
                    assertThat(nonce).hasSizeGreaterThan(20);
                    assertThat(result.getResponse().getContentAsString()).contains("nonce=\"" + nonce + "\"");
                });
    }

    @Test
    void securityPolicyLetsThePageOpenItsOwnWebSocket() throws Exception {
        mvc.perform(get("/p/demo-panda"))
                .andExpect(header().string(
                                "Content-Security-Policy", containsString("connect-src 'self' ws://localhost ")));

        mvc.perform(get("/p/demo-panda").header("Host", "localhost:8081"))
                .andExpect(header().string(
                                "Content-Security-Policy", containsString("connect-src 'self' ws://localhost:8081 ")));
    }

    @Test
    void pageAssetsArePublicAndNeverUseInnerHtml() throws Exception {
        mvc.perform(get("/css/panda.css")).andExpect(status().isOk());
        mvc.perform(get("/js/panda.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("innerHTML"))));
    }

    @Test
    void pandaOpensItsWebSocketOnThePagesOwnOriginAndWssOverHttps() throws Exception {
        mvc.perform(get("/js/panda.js"))
                .andExpect(content()
                        .string(containsString("window.location.protocol === \"https:\" ? \"wss:\" : \"ws:\"")))
                .andExpect(
                        content().string(containsString("scheme + \"//\" + window.location.host + \"/ws/observe/\"")))
                .andExpect(content().string(not(containsString("localhost"))));
    }

    @Test
    void pageShowsTheBearWithTheLightOnTheCoinAndTheBrand() throws Exception {
        String page =
                mvc.perform(get("/p/demo-panda")).andReturn().getResponse().getContentAsString();

        assertThat(page)
                .contains("<title>TiP | Panda</title>")
                .contains("id=\"panda-coin-glow\"", "id=\"panda-coin-light\"", "id=\"panda-coin-sparkle\"")
                .contains("<h1>", "<button type=\"button\" id=\"brand\"", "aria-expanded=\"false\"")
                .contains("aria-label=\"TiP, Tip integrated Panda\"")
                .contains("id=\"device-name\"", "id=\"caption\"", "id=\"status\"")
                .contains("href=\"/fonts/fonts.css\"")
                .doesNotContain("led-glow", "led-core", " style=", "<style");
    }

    @Test
    void pandaLightsTheCoinInTheReactionsColour() throws Exception {
        String script =
                mvc.perform(get("/js/panda.js")).andReturn().getResponse().getContentAsString();

        assertThat(script)
                .contains("\"#ff7fb0\"", "\"#ffd54a\"", "\"#6fb6ff\"", "\"#fff4e0\"")
                .contains("el(\"panda-coin-glow\").setAttribute(\"opacity\", String(level))")
                .contains("el(\"panda-coin-light\").setAttribute(\"opacity\", String(level * COIN_TINT))")
                .contains("el(\"panda-coin-sparkle\").setAttribute(\"opacity\", String(level))")
                .contains("const COIN_TINT = 0.35;", "style.setProperty(\"--light\", colour)")
                .contains("setLed(reducedMotion.matches ? 1 : levelAt(state.playing.steps, elapsed))")
                .contains("el(\"device-name\").textContent = name")
                .contains("window.TipBrand.isBrandName(name)");
    }

    @Test
    void brandScriptIsSharedAndHidesNamesThatRepeatTheBrand() throws Exception {
        String page =
                mvc.perform(get("/p/demo-panda")).andReturn().getResponse().getContentAsString();
        String brand = mvc.perform(get("/js/brand.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("innerHTML"))))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(page).contains("href=\"/css/brand.css\"", "src=\"/js/brand.js\"");
        assertThat(page.indexOf("/js/brand.js")).isLessThan(page.indexOf("/js/panda.js"));
        assertThat(brand)
                .contains("[\"tip\", \"tipintegratedpanda\"]", "name.replace(/\\s+/g, \"\").toLowerCase()")
                .contains("parentElement.style.setProperty(\"--w\", part.scrollWidth + \"px\")")
                .contains("document.fonts.ready.then(measure)");
    }

    @Test
    void unknownSlugGetsTheNotFoundPage() throws Exception {
        mvc.perform(get("/p/no-such-panda"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("This panda wandered off")))
                .andExpect(content().string(not(containsString("/js/panda.js"))));
    }

    @Test
    void pageAndFontsReferenceNoOutsideOrigin() throws Exception {
        // The preview tags name our own public base URL, http://localhost:8080 in the test profile.
        Pattern outside = Pattern.compile("https?://(?!www\\.w3\\.org/2000/svg\"|localhost:8080/)");
        String page =
                mvc.perform(get("/p/demo-panda")).andReturn().getResponse().getContentAsString();
        String fonts = mvc.perform(get("/fonts/fonts.css"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String styles =
                mvc.perform(get("/css/panda.css")).andReturn().getResponse().getContentAsString();

        assertThat(outside.matcher(page).find()).as("panda.html").isFalse();
        assertThat(outside.matcher(fonts).find()).as("fonts.css").isFalse();
        String brandStyles =
                mvc.perform(get("/css/brand.css")).andReturn().getResponse().getContentAsString();

        assertThat(outside.matcher(styles).find()).as("panda.css").isFalse();
        assertThat(outside.matcher(brandStyles).find()).as("brand.css").isFalse();
        assertThat(fonts).contains("font-display: swap", "unicode-range", "fredoka/fredoka-latin-700-normal.woff2");
    }

    @Test
    void fontFilesArePublicWoff2UnderTheSamePolicy() throws Exception {
        mvc.perform(get("/fonts/fredoka/fredoka-latin-400-normal.woff2"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("font/woff2"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", not(containsString("font-src"))));
        mvc.perform(get("/fonts/fredoka/OFL.txt")).andExpect(status().isOk());

        mvc.perform(get("/p/demo-panda"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", not(containsString("unsafe-inline"))))
                .andExpect(header().string("Content-Security-Policy", not(containsString("font-src"))));
    }

    @Test
    void pandaPlaysTheFourReactionsWithTheirCaptions() throws Exception {
        String script =
                mvc.perform(get("/js/panda.js")).andReturn().getResponse().getContentAsString();

        assertThat(script)
                .contains("WARM:", "PLAYFUL:", "REQUEST:", "PLAIN:")
                .contains("\"Warm message\"", "\"Playful message\"", "\"Request noted\"", "\"Tip received\"")
                .contains("[255, 140, 230, 110, 255, 170]")
                .contains("baseUnits: 3", "baseUnits: 2", "baseUnits: 1", "gapMs: 250", "gapMs: 300")
                .contains("repeatsOf(message.repeats)")
                .doesNotContain("POSITIVE", "NEGATIVE", "NEUTRAL", "Rude message");
    }

    @Test
    void pandaFollowsTheClientPolicyLikeTheFirmware() throws Exception {
        String script =
                mvc.perform(get("/js/panda.js")).andReturn().getResponse().getContentAsString();

        assertThat(script)
                .contains("const QUEUE_SIZE = 8;")
                .contains("const BACKOFF_SECONDS = [1, 2, 4, 8, 16, 32, 60];")
                .contains("const STABLE_MS = 60000;")
                .contains("(1 - Math.random() * JITTER)")
                .contains("performance.now() - state.openedAt >= STABLE_MS");
    }

    @Test
    void tipSuccessScreenLinksToThePanda() throws Exception {
        mvc.perform(get("/t/demo-panda")).andExpect(content().string(containsString("Watch the panda react")));
        mvc.perform(get("/js/tip.js")).andExpect(content().string(containsString("\"/p/\" + encodeURIComponent")));
    }
}
