package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
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
    void tipSuccessScreenLinksToThePanda() throws Exception {
        mvc.perform(get("/t/demo-panda")).andExpect(content().string(containsString("Watch the panda")));
        mvc.perform(get("/js/tip.js")).andExpect(content().string(containsString("\"/p/\" + encodeURIComponent")));
    }
}
