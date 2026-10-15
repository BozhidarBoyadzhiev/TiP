package dev.tip.tipping;

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

class TipPageTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void tagUrlServesTheTipPageWithItsSecurityPolicy() throws Exception {
        mvc.perform(get("/t/demo-panda"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("/js/tip.js")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'self'")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(result -> {
                    String policy = result.getResponse().getHeader("Content-Security-Policy");
                    String nonce = policy.replaceAll(".*script-src 'self' 'nonce-([A-Za-z0-9_-]+)'.*", "$1");
                    assertThat(nonce).hasSizeGreaterThan(20);
                    assertThat(result.getResponse().getContentAsString()).contains("nonce=\"" + nonce + "\"");
                });
    }

    @Test
    void tipPageCallsTheApiOnItsOwnOrigin() throws Exception {
        String script = mvc.perform(get("/js/tip.js")).andReturn().getResponse().getContentAsString();

        assertThat(script)
                .contains("fetch(\"/api/public/devices/\"", "fetch(\"/api/public/tips/\"")
                .doesNotContain("localhost", "ngrok", "http://");
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
    void messageBoxInvitesAnyKindOfMessageUpTo280Characters() throws Exception {
        mvc.perform(get("/t/demo-panda"))
                .andExpect(content().string(containsString("<label for=\"message\">Leave a message")))
                .andExpect(content().string(containsString("maxlength=\"280\"")))
                .andExpect(content().string(containsString("0 / 280")))
                .andExpect(content()
                        .string(containsString("placeholder=\"Say something nice, ask for a song, or just tip.\"")));
    }

    @Test
    void pageHasTheBrandAndNoInlineStyles() throws Exception {
        String page =
                mvc.perform(get("/t/demo-panda")).andReturn().getResponse().getContentAsString();

        assertThat(page)
                .contains("<button type=\"button\" id=\"brand\" class=\"brand\" aria-expanded=\"false\"")
                .contains("aria-label=\"TiP, Tip integrated Panda\"")
                .contains("href=\"/fonts/fonts.css\"", "href=\"/css/brand.css\"", "src=\"/js/brand.js\"")
                .contains("id=\"device-name\"", "inputmode=\"decimal\"", "id=\"paypal-buttons\"")
                .doesNotContain(" style=", "<style");
        assertThat(page.indexOf("/js/brand.js")).isLessThan(page.indexOf("/js/tip.js"));
    }

    @Test
    void securityPolicyHasNoUnsafeInline() throws Exception {
        mvc.perform(get("/t/demo-panda"))
                .andExpect(header().string("Content-Security-Policy", not(containsString("unsafe-inline"))))
                .andExpect(header().string("Content-Security-Policy", containsString("https://*.paypal.com")));
    }

    @Test
    void pageAndAssetsNameNoOutsideOriginButThePayPalSdk() throws Exception {
        // The preview tags name our own public base URL, http://localhost:8080 in the test profile.
        Pattern outside =
                Pattern.compile("https?://(?!www\\.w3\\.org/2000/svg\"|www\\.paypal\\.com/sdk/js\\?|localhost:8080/)");
        for (String path :
                new String[] {"/t/demo-panda", "/css/tip.css", "/css/brand.css", "/js/tip.js", "/js/brand.js"}) {
            String body = mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(outside.matcher(body).find()).as(path).isFalse();
        }
        mvc.perform(get("/js/tip.js"))
                .andExpect(content().string(containsString("\"https://www.paypal.com/sdk/js?\" + params.toString()")));
    }

    @Test
    void payPalButtonsAreDisabledThroughTheSdkWhileTheAmountIsInvalid() throws Exception {
        String script = mvc.perform(get("/js/tip.js")).andReturn().getResponse().getContentAsString();

        assertThat(script)
                .contains("onInit: (data, actions) => {", "state.buttons = actions;")
                .contains("state.buttons.enable();", "state.buttons.disable();")
                .contains("if (validMinor() === null) {", "return actions.reject();")
                .contains("layout: \"vertical\"", "color: \"gold\"", "borderRadius: 26")
                .contains("amountMinor: amountMinor,");
    }

    /** PayPal's buttons offer the card guest checkout unless a funding source is turned off or one is forced. */
    @Test
    void payPalButtonsLeaveCardGuestCheckoutOn() throws Exception {
        String script = mvc.perform(get("/js/tip.js")).andReturn().getResponse().getContentAsString();

        assertThat(script)
                .contains("components: \"buttons\"")
                .doesNotContain("disable-funding")
                .doesNotContain("enable-funding")
                .doesNotContain("fundingSource")
                .doesNotContain("FUNDING");
    }

    @Test
    void cancellingAfterADeclineKeepsTheDeclineNoteUntilTheNextClick() throws Exception {
        String script = mvc.perform(get("/js/tip.js")).andReturn().getResponse().getContentAsString();

        assertThat(script)
                .containsPattern("state\\.declined = true;\\s+formError\\(ERROR_MESSAGES\\.INSTRUMENT_DECLINED\\);")
                .containsPattern("onClick: \\(data, actions\\) => \\{\\s+state\\.declined = false;")
                .containsPattern(
                        "onCancel: \\(\\) => \\{\\s+clearOpening\\(\\);\\s+formError\\(\\s+state\\.declined\\s+"
                                + "\\? ERROR_MESSAGES\\.INSTRUMENT_DECLINED\\s+"
                                + ": \"Payment cancelled\\. You have not been charged\\.\",");
    }

    @Test
    void aResolvedClickSaysPayPalIsOpeningUntilItOpensComesBackOrFiveSecondsPass() throws Exception {
        String page =
                mvc.perform(get("/t/demo-panda")).andReturn().getResponse().getContentAsString();
        String script = mvc.perform(get("/js/tip.js")).andReturn().getResponse().getContentAsString();

        assertThat(page)
                .containsPattern("<div id=\"paypal-buttons\" class=\"pay\"></div>\\s*"
                        + "<p id=\"paypal-opening\" class=\"opening\" aria-live=\"polite\"></p>");
        assertThat(script)
                .containsPattern("const resolved = actions\\.resolve\\(\\);\\s+showOpening\\(\\);\\s+return resolved;")
                .contains("const OPENING_MS = 5000;", "setTimeout(clearOpening, OPENING_MS)")
                .contains("el(\"paypal-opening\").textContent = \"Opening PayPal")
                .contains("new ResizeObserver(", ".observe(el(\"paypal-buttons\"));")
                .containsPattern("onApprove: \\(data, actions\\) => \\{\\s+clearOpening\\(\\);")
                .containsPattern("onError: \\(\\) => \\{\\s+clearOpening\\(\\);");
    }

    @Test
    void amountParserNeverGoesThroughFloatingPoint() throws Exception {
        String script = mvc.perform(get("/js/tip.js")).andReturn().getResponse().getContentAsString();

        assertThat(script)
                .contains("Number(whole + fraction)", ".replace(\",\", \".\")", "match[2].length > digits")
                .doesNotContain("parseFloat", "toFixed", "Math.round(");
    }

    @Test
    void deviceNameFollowsTheBrandRuleAndIsSetAsText() throws Exception {
        String script = mvc.perform(get("/js/tip.js")).andReturn().getResponse().getContentAsString();

        assertThat(script)
                .contains("if (!window.TipBrand.isBrandName(config.displayName)) {")
                .contains("el(\"device-name\").textContent = config.displayName.trim();")
                .contains("count.classList.toggle(\"near\", message.value.length > MESSAGE_NEAR);")
                .contains("const MESSAGE_NEAR = 250;");
    }

    @Test
    void unknownSlugGetsTheNotFoundPageInsteadOfTheTipPage() throws Exception {
        mvc.perform(get("/t/no-such-panda").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("This panda wandered off")))
                .andExpect(content().string(not(containsString("/js/tip.js"))));
        mvc.perform(get("/t/Not_A_Slug")).andExpect(status().isNotFound());
    }

    @Test
    void pageAssetsArePublic() throws Exception {
        mvc.perform(get("/js/tip.js")).andExpect(status().isOk());
        mvc.perform(get("/js/brand.js")).andExpect(status().isOk());
        mvc.perform(get("/css/tip.css")).andExpect(status().isOk());
        mvc.perform(get("/css/brand.css")).andExpect(status().isOk());
    }

    @Test
    void pageNeverUsesInnerHtml() throws Exception {
        for (String script : new String[] {"/js/tip.js", "/js/brand.js", "/js/owner-login.js"}) {
            mvc.perform(get(script))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("innerHTML"))));
        }
    }
}
