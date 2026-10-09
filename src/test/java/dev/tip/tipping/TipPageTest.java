package dev.tip.tipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class TipPageTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void tagUrlServesTheTipPageWithItsSecurityPolicy() throws Exception {
        mvc.perform(get("/t/demo-panda"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("/js/tip.js")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(result -> {
                    String policy = result.getResponse().getHeader("Content-Security-Policy");
                    String nonce = policy.replaceAll(".*script-src 'self' 'nonce-([A-Za-z0-9_-]+)'.*", "$1");
                    assertThat(nonce).hasSizeGreaterThan(20);
                    assertThat(result.getResponse().getContentAsString()).contains("nonce=\"" + nonce + "\"");
                });
    }

    @Test
    void everyPageLoadGetsANewNonce() throws Exception {
        String first =
                mvc.perform(get("/t/demo-panda")).andReturn().getResponse().getHeader("Content-Security-Policy");
        String second =
                mvc.perform(get("/t/demo-panda")).andReturn().getResponse().getHeader("Content-Security-Policy");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void pageAssetsArePublic() throws Exception {
        mvc.perform(get("/js/tip.js")).andExpect(status().isOk());
        mvc.perform(get("/css/tip.css")).andExpect(status().isOk());
    }

    @Test
    void pageNeverUsesInnerHtml() throws Exception {
        mvc.perform(get("/js/tip.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("innerHTML"))));
    }
}
