package dev.tip.owners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.owners.api.OwnerKind;
import dev.tip.owners.api.OwnerPrincipal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The owner sign in page, the access code page, the sample opener and the dashboard shell: their heads, security
 * policy and what their scripts may do.
 */
class OwnerPageTest extends IntegrationTest {

    /** Any absolute URL except the SVG namespace and our own public base URL from the test profile. */
    private static final Pattern OUTSIDE = Pattern.compile("https?://(?!www\\.w3\\.org/2000/svg\"|localhost:8080/)");

    private static final Pattern NONCE = Pattern.compile("script-src 'self' 'nonce-([A-Za-z0-9_-]+)'");

    private static final String[] ICON_TAGS = {
        "<link rel=\"icon\" href=\"/favicon.ico\" sizes=\"48x48\">",
        "<link rel=\"icon\" href=\"/icons/favicon-panda.svg\" type=\"image/svg+xml\">",
        "<link rel=\"apple-touch-icon\" href=\"/icons/apple-touch-icon-panda.png\">",
        "<meta name=\"theme-color\" content=\"#120c09\">"
    };

    @Autowired
    MockMvc mvc;

    @Test
    void loginPageHasItsHeadPolicyAndToken() throws Exception {
        MockHttpServletResponse response = page(get("/owner/login"));
        String html = response.getContentAsString();

        assertPage(response, "TiP | Owner login", "/js/owner-login.js");
        assertThat(html)
                .contains("<form id=\"login-form\" method=\"post\" action=\"/owner/login\"")
                .contains("<input type=\"hidden\" name=\"_csrf\" value=\"")
                .contains("id=\"signed-out\"", "id=\"login-error\"", "class=\"peek\"", "class=\"paws\"")
                .doesNotContain("value=\"\"");
    }

    @Test
    void dashboardIsTheShellWithItsHeadPolicyAndToken() throws Exception {
        MockHttpServletResponse response = page(get("/owner").with(user(owner())));
        String html = response.getContentAsString();

        assertPage(response, "TiP | Dashboard", "/js/owner.js");
        assertThat(html)
                .contains("<meta name=\"csrf-header\" content=\"X-CSRF-TOKEN\">")
                .containsPattern("<meta name=\"csrf-token\" content=\"[A-Za-z0-9_-]{20,}\">")
                .containsPattern("<form method=\"post\" action=\"/owner/logout\" id=\"sign-out\">\\s*"
                        + "<input type=\"hidden\" name=\"_csrf\" value=\"[A-Za-z0-9_-]{20,}\">")
                .contains("aria-pressed=\"true\" data-filter=\"ALL\"", "aria-live=\"polite\" id=\"toast-region\"")
                .contains("<meta name=\"real-panda\" content=\"/t/demo-panda\">")
                .contains("id=\"sandbox-strip\" hidden", "id=\"sample-strip\" hidden", "id=\"resting\" hidden")
                .contains("id=\"scrim\" hidden", "role=\"dialog\" aria-modal=\"true\"", "id=\"i-LOCK\"")
                .doesNotContain("Owner Name");
    }

    @Test
    void judgesPageHasItsHeadPolicyTokenAndStates() throws Exception {
        MockHttpServletResponse response = page(get("/judges"));
        String html = response.getContentAsString();

        assertPage(response, "TiP | Judge access", "/js/judges.js");
        assertThat(html)
                .contains("<form id=\"code-form\" method=\"post\" action=\"/judges/session\"")
                .containsPattern("<input type=\"hidden\" name=\"_csrf\" value=\"[A-Za-z0-9_-]{20,}\">")
                .contains("name=\"timeZone\"", "name=\"code\"", "autocomplete=\"off\"")
                .contains("id=\"n-ended\"", "id=\"n-bad\"", "id=\"n-slow\"")
                .contains("<a href=\"/demo\">Look at a sample dashboard</a>")
                .contains("<a id=\"real-panda\" href=\"/t/demo-panda\">tip the real panda</a>");
    }

    @Test
    void judgesPageExplainsAnEndedSandbox() throws Exception {
        String script = body("/js/judges.js");

        assertThat(script)
                .contains("query.has(\"sandboxExpired\") ? \"n-ended\" : null")
                .contains("response.status === 429", "LOGIN_FAILED", "redirect: \"manual\"")
                .contains("toUpperCase().replace(/[^0-9A-Z]/g, \"\")", "code.startsWith(\"TIP\")")
                .contains("Intl.DateTimeFormat().resolvedOptions().timeZone");
    }

    @Test
    void resumePageIsGenericAndCarriesItsToken() throws Exception {
        MockHttpServletResponse response = page(get("/judges/resume").param("t", "anything"));
        String html = response.getContentAsString();

        assertPolicy(response, "TiP | Judge access", "/js/resume.js");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(html)
                .contains("<meta name=\"csrf-header\" content=\"X-CSRF-TOKEN\">")
                .containsPattern("<meta name=\"csrf-token\" content=\"[A-Za-z0-9_-]{20,}\">")
                .contains("<meta name=\"referrer\" content=\"no-referrer\">")
                .contains("<h1>Continue to your sandbox</h1>", "id=\"ready\" hidden", "id=\"failed\" hidden")
                .contains("<button type=\"button\" class=\"gold-btn\" id=\"resume-btn\">")
                .contains("That link was already used or has run out.", "<a class=\"gold-btn\" href=\"/judges\">")
                .doesNotContain("anything", "<form");
    }

    @Test
    void resumeScriptReadsTheFragmentAndPostsOnlyOnATap() throws Exception {
        String script = body("/js/resume.js");

        assertThat(script)
                .contains("new URLSearchParams(window.location.hash.slice(1)).get(\"t\")")
                .contains("history.replaceState(null, \"\", window.location.pathname)")
                .contains("el(\"resume-btn\").addEventListener(\"click\", () => resume(token))")
                .contains("body: JSON.stringify({ token })", "headers[meta(\"csrf-header\")]")
                .contains("window.location.replace(\"/owner\")", "response.status === 429")
                .doesNotContain("location.search", "submit");
        // One fetch, inside resume(), which only the button's click calls.
        assertThat(Pattern.compile("fetch\\(").matcher(script).results().count())
                .isOne();
        assertThat(Pattern.compile("=> resume\\(").matcher(script).results().count())
                .isOne();
    }

    @Test
    void demoPageOpensTheSampleWithAFormPostAndItsToken() throws Exception {
        MockHttpServletResponse response = page(get("/demo"));
        String html = response.getContentAsString();

        assertPage(response, "TiP | Sample dashboard", "/js/demo.js");
        assertThat(html)
                .contains("<form id=\"demo-form\" method=\"post\" action=\"/demo/session\">")
                .containsPattern("<input type=\"hidden\" name=\"_csrf\" value=\"[A-Za-z0-9_-]{20,}\">");
        assertThat(body("/js/demo.js")).contains("redirect: \"manual\"", "window.location.replace(\"/owner\")");
    }

    @Test
    void aSignedInOwnerFollowingTheSampleLinkStaysOnTheirOwnDashboard() throws Exception {
        OwnerPrincipal sandbox = new OwnerPrincipal(
                UUID.randomUUID(),
                null,
                "Judge",
                null,
                OwnerKind.SANDBOX,
                clock.instant().plusSeconds(3600));
        OwnerPrincipal expired = new OwnerPrincipal(
                UUID.randomUUID(),
                null,
                "Judge",
                null,
                OwnerKind.SANDBOX,
                clock.instant().minusSeconds(1));

        mvc.perform(get("/demo").with(user(owner())))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/owner"));
        mvc.perform(get("/demo").with(user(sandbox)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/owner"));
        mvc.perform(get("/demo").with(user(expired))).andExpect(status().isOk());
    }

    @Test
    void theTipCardLinksToTheSampleDashboard() throws Exception {
        assertThat(body("/t/demo-panda"))
                .contains(
                        "<p class=\"foot\">Curious what the owner sees? <a href=\"/demo\">Look at a sample dashboard</a>.</p>");
    }

    @Test
    void ownerScriptServesSandboxesAndTheReadOnlySample() throws Exception {
        String script = body("/js/owner.js");

        assertThat(script)
                .contains("closing.reason === \"sandbox_expired\"", "LEAVE.assign(\"/judges?sandboxExpired\")")
                .contains("=== \"SANDBOX_EXPIRED\"", "LEAVE.assign(\"/judges\")")
                .contains("if (!state.readOnly && !state.leaving && state.socket === null")
                .contains("request(\"/api/owner/resume-link\", { method: \"POST\", headers })")
                .contains("view.aiStatus === \"UNAVAILABLE\"", "\"Not read\"", "\"Demo\"")
                .contains("\"Demo tip. Send a real tip to try refunds.\"", "\"Read only\"")
                .contains("node.disabled = true;");
    }

    @Test
    void testPaymentDetailsAreASandboxDialogWithCopyButtons() throws Exception {
        String html = page(get("/owner").with(user(owner()))).getContentAsString();
        String script = body("/js/owner.js");

        assertThat(html)
                .contains("id=\"open-pay\" aria-haspopup=\"dialog\" hidden>Test payment details</button>")
                .contains("id=\"pay-scrim\" hidden", "id=\"pay-sheet\" role=\"dialog\" aria-modal=\"true\"")
                .contains("These are PayPal sandbox test details. No real money moves.")
                .contains(">Card</h3>", ">Billing details</h3>", ">PayPal account</h3>");
        assertThat(script)
                .contains("if (state.kind === \"SANDBOX\"", "quietly(loadTestPayment());")
                .contains("getJson(\"/api/owner/test-payment\")", "if (!card && !paypal) {")
                .contains("el(\"open-pay\").hidden = false;")
                .contains("make(\"span\", \"pay-value\", value)", "navigator.clipboard.writeText(value)")
                .contains("range.selectNodeContents(text);", "copy.textContent = \"Copied\";")
                .contains("copy.setAttribute(\"aria-label\", \"Copy \" + name);", "\"PayPal password\"")
                .contains("return { sheet: el(\"pay-sheet\"), close: closePay };");
    }

    @Test
    void dashboardRedirectsToLoginWhenSignedOut() throws Exception {
        mvc.perform(get("/owner")).andExpect(status().isFound()).andExpect(redirectedUrl("/owner/login"));
    }

    @Test
    void stylesAndScriptsNameNoOutsideOrigin() throws Exception {
        for (String path : List.of(
                "/css/owner.css",
                "/css/brand.css",
                "/fonts/fonts.css",
                "/js/owner.js",
                "/js/owner-login.js",
                "/js/judges.js",
                "/js/demo.js")) {
            String body = mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(OUTSIDE.matcher(body).find()).as(path).isFalse();
            assertThat(body).as(path).doesNotContain("fonts.googleapis", "fonts.gstatic");
        }
    }

    @Test
    void pageScriptsNeverWriteMarkup() throws Exception {
        for (String path : List.of("/js/judges.js", "/js/demo.js", "/js/resume.js")) {
            assertThat(body(path))
                    .as(path)
                    .doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write")
                    .contains("textContent");
        }
    }

    @Test
    void ownerScriptNeverWritesMarkup() throws Exception {
        String script = body("/js/owner.js");

        assertThat(script).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write");
        assertThat(script)
                .contains("textContent", "document.createElement(", "document.createTextNode(")
                .contains("style.setProperty(\"--h\"");
    }

    @Test
    void ownerScriptTalksOnlyToItsOwnOriginWithTheTokenAndLeavesOn401() throws Exception {
        String script = body("/js/owner.js");

        assertThat(script)
                .contains("scheme + \"//\" + window.location.host + \"/ws/owner\"")
                .contains("window.location.protocol === \"https:\" ? \"wss:\" : \"ws:\"")
                .contains("credentials: \"same-origin\"")
                .contains("headers[csrf.header] = csrf.token;")
                .contains("response.status === 401", "LEAVE.assign(\"/owner/login\")")
                .doesNotContain("document.title", "localhost", "XMLHttpRequest");
        Matcher fetches =
                Pattern.compile("(?:request|getJson)\\(\\s*\"([^\"]*)\"").matcher(script);
        int count = 0;
        while (fetches.find()) {
            count++;
            assertThat(fetches.group(1)).startsWith("/api/");
        }
        assertThat(count).isGreaterThan(5);
    }

    @Test
    void ownerScriptFollowsTheChannelsClientPolicy() throws Exception {
        String script = body("/js/owner.js");

        assertThat(script)
                .contains("const BACKOFF_SECONDS = [1, 2, 4, 8, 16, 32, 60];", "const JITTER = 0.2;")
                .contains("const STABLE_MS = 60000;", "const POLL_MS = 30000;")
                .contains("closing.reason === \"signed_out\" || closing.reason === \"session_expired\"")
                .contains("closing.reason === \"replaced\"");
    }

    @Test
    void ownerScriptFormatsEveryTimeInTheOwnersZone() throws Exception {
        String script = body("/js/owner.js");

        // One helper builds every date format, with the owner's zone; the second constructor only checks /me's zone.
        assertThat(script)
                .contains(
                        "return new Intl.DateTimeFormat(locale, Object.assign({}, options, { timeZone: state.zone }));")
                .contains(
                        "new Intl.DateTimeFormat(undefined, { timeZone: me.timeZone });", "state.zone = me.timeZone;");
        assertThat(Pattern.compile("Intl\\.DateTimeFormat\\(")
                        .matcher(script)
                        .results()
                        .count())
                .isEqualTo(2);
        assertThat(Pattern.compile("timeZone: ").matcher(script).results().count())
                .isEqualTo(2);
        // Nothing reads a time in the browser's own zone.
        assertThat(script)
                .doesNotContain("toLocaleString", "toLocaleTimeString", "toLocaleDateString", "getHours(", "getDate(");
        // The busiest hour uses the hour the server counted in the owner's zone.
        assertThat(script).contains("hourRange(busiest.hour)", "hourLabel(today.hours[0].hour)");
        // The zone is known before anything with a time is shown.
        assertThat(script).containsPattern("await loadMe\\(\\);\\s+await refreshDevices\\(\\);");
    }

    @Test
    void phonesShowTipsRightAfterTodaysNumbers() throws Exception {
        String html = body("/owner", user(owner()));
        String styles = body("/css/owner.css");
        String script = body("/js/owner.js");

        assertThat(html.indexOf("id=\"tips-panel\""))
                .isGreaterThan(html.indexOf("aria-label=\"Today\""))
                .isLessThan(html.indexOf("id=\"notes\""));
        assertThat(html.indexOf("id=\"notes\""))
                .isLessThan(html.indexOf("id=\"req-panel\""))
                .isLessThan(html.indexOf("id=\"flag-panel\""));
        assertThat(styles)
                .contains(".grid > .side {\n        grid-column: 1;", ".grid > .tips-panel {\n        grid-column: 2;");
        assertThat(script).contains("\"No tips yet today\"");
    }

    @Test
    void ownerStylesTurnOffMotionWhenAsked() throws Exception {
        String styles = body("/css/owner.css");

        assertThat(styles.substring(styles.indexOf("@media (prefers-reduced-motion: reduce)")))
                .contains(".tip.new", ".toast", ".empty .eyes", "animation: none");
        assertThat(styles).doesNotContain("--accent", "--surface");
    }

    private void assertPage(MockHttpServletResponse response, String title, String script) throws Exception {
        assertPolicy(response, title, script);
        assertThat(response.getContentAsString()).contains("name=\"_csrf\"");
    }

    private void assertPolicy(MockHttpServletResponse response, String title, String script) throws Exception {
        String html = response.getContentAsString();
        String policy = response.getHeader("Content-Security-Policy");
        Matcher nonce = NONCE.matcher(policy);

        assertThat(nonce.find()).isTrue();
        assertThat(policy).doesNotContain("unsafe-inline");
        assertThat(response.getContentType()).startsWith(MediaType.TEXT_HTML_VALUE);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(html)
                .contains("<title>" + title + "</title>")
                .contains(ICON_TAGS)
                .contains("<script src=\"" + script + "\" defer nonce=\"" + nonce.group(1) + "\"></script>")
                .contains("href=\"/fonts/fonts.css\"", "href=\"/css/brand.css\"", "href=\"/css/owner.css\"")
                .doesNotContain("{{", " style=", "<style", "og:", "twitter:", "/css/tip.css");
        assertThat(Pattern.compile("<script(?![^>]*\\ssrc=\"/js/)")
                        .matcher(html)
                        .find())
                .as("inline script")
                .isFalse();
        assertThat(Pattern.compile("\\son[a-z]+=").matcher(html).find())
                .as("inline handler")
                .isFalse();
        assertThat(OUTSIDE.matcher(html).find()).as("outside origin").isFalse();
    }

    private MockHttpServletResponse page(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
    }

    private String body(String path) throws Exception {
        return body(path, request -> request);
    }

    private String body(String path, RequestPostProcessor with) throws Exception {
        return mvc.perform(get(path).with(with))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static OwnerPrincipal owner() {
        return new OwnerPrincipal(UUID.randomUUID(), "owner@example.com", "Owner Name", null);
    }
}
