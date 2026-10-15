package dev.tip.owners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.owners.api.OwnerPrincipal;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The side by side view of a judge's sandbox: {@code /owner/live} for sandboxes only, and the per path rule
 * that lets our own origin frame the tip page, the panda page and the dashboard, and nothing else.
 */
class SideBySideTest extends IntegrationTest {

    private static final Pattern NONCE = Pattern.compile("script-src 'self' 'nonce-([A-Za-z0-9_-]+)'");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    private Judges.Sandbox sandbox;

    @BeforeEach
    void signIn() throws Exception {
        sandbox = new Judges(mvc, jdbc).sandbox(JUDGE_CODE, "UTC");
    }

    @Test
    void aSandboxGetsTheLivePageWithItsPolicyAndNothingAboutItsOwner() throws Exception {
        MockHttpServletResponse response = mvc.perform(html(get("/owner/live").session(sandbox.session())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        String page = response.getContentAsString();
        String policy = response.getHeader("Content-Security-Policy");
        Matcher nonce = NONCE.matcher(policy);

        assertThat(nonce.find()).isTrue();
        assertThat(policy).doesNotContain("unsafe-inline").contains("frame-src 'self' https://*.paypal.com");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getContentType()).startsWith(MediaType.TEXT_HTML_VALUE);
        assertThat(page)
                .contains("<title>TiP | Side by side</title>")
                .contains("<script src=\"/js/live.js\" defer nonce=\"" + nonce.group(1) + "\"></script>")
                .contains("href=\"/css/live.css\"", "href=\"/css/brand.css\"", "href=\"/fonts/fonts.css\"")
                .contains("Leave a tip", "Watch the panda react", "See it on your dashboard")
                .contains("id=\"frame-tip\"", "id=\"frame-panda\"", "id=\"frame-dash\"")
                .doesNotContain(sandbox.slug(), sandbox.ownerId().toString(), "Judge", "{{", " src=\"/t/", " style=");
        assertThat(Pattern.compile("<iframe[^>]*\\ssrc=").matcher(page).find())
                .as("frame source in the markup")
                .isFalse();
        assertThat(Pattern.compile("<script(?![^>]*\\ssrc=\"/js/)")
                        .matcher(page)
                        .find())
                .as("inline script")
                .isFalse();
        assertThat(Pattern.compile("\\son[a-z]+=").matcher(page).find())
                .as("inline handler")
                .isFalse();
    }

    @Test
    void aRealOwnerGetsTheAnswerOfAPathThatDoesNotExist() throws Exception {
        OwnerPrincipal owner = new OwnerPrincipal(UUID.randomUUID(), "owner@example.com", "Owner", null);

        assertSameAsUnknownPath(request -> request.with(user(owner)));
    }

    @Test
    void thePublicDemoGetsTheAnswerOfAPathThatDoesNotExist() throws Exception {
        MockHttpSession demo = new Judges(mvc, jdbc).demo();

        assertSameAsUnknownPath(request -> request.session(demo));
    }

    @Test
    void signedOutItSendsTheBrowserToSignInLikeTheDashboard() throws Exception {
        mvc.perform(get("/owner")).andExpect(status().isFound()).andExpect(redirectedUrl("/owner/login"));
        mvc.perform(get("/owner/live")).andExpect(status().isFound()).andExpect(redirectedUrl("/owner/login"));
    }

    @Test
    void ourOwnOriginMayFrameTheTipPageThePandaAndTheDashboard() throws Exception {
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {
            get("/t/" + sandbox.slug() + "?embed"),
            get("/p/" + sandbox.slug() + "?embed"),
            get("/owner?embed").session(sandbox.session()),
            get("/owner").session(sandbox.session()),
            get("/t/demo-panda")
        }) {
            MockHttpServletResponse response =
                    mvc.perform(html(request)).andReturn().getResponse();
            String path = request.buildRequest(new MockServletContext()).getRequestURI();

            assertThat(response.getStatus()).as(path).isEqualTo(200);
            assertThat(response.getHeader("Content-Security-Policy"))
                    .as(path)
                    .contains("frame-ancestors 'self'")
                    .doesNotContain("frame-ancestors 'none'", "frame-src 'self'");
            assertThat(response.getHeaders("X-Frame-Options")).as(path).containsExactly("SAMEORIGIN");
        }
    }

    @Test
    void everyOtherResponseStaysUnframeable() throws Exception {
        assertNotFrameable(html(get("/owner/live").session(sandbox.session())), 200);
        assertNotFrameable(html(get("/judges")), 200);
        assertNotFrameable(html(get("/owner/login")), 200);
        assertNotFrameable(html(get("/no-such-page")), 404);
        assertNotFrameable(html(get("/owner/no-such-page").session(sandbox.session())), 404);
        assertNotFrameable(get("/api/public/devices/" + sandbox.slug()), 200);
        assertNotFrameable(get("/api/owner/me").session(sandbox.session()), 200);
        assertNotFrameable(get("/js/live.js"), 200);
    }

    @Test
    void theLiveScriptAsksTheApiForThePandaAndEmbedsTheThreePages() throws Exception {
        String script = body("/js/live.js");

        assertThat(script)
                .contains("fetch(\"/api/owner/devices\"")
                .contains("\"/t/\" + slug + \"?embed\"", "\"/p/\" + slug + \"?embed\"", "\"/owner?embed\"")
                .contains("encodeURIComponent(device.slug)")
                .contains("matchMedia(\"(min-width: 1100px)\")", "addEventListener(\"change\"")
                .contains("window.location.replace(\"/owner\")", "\"/judges?sandboxExpired\"")
                .contains("devices = await response.json();\n        } catch (e) {\n"
                        + "            el(\"load-error\").hidden = false;")
                .doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "postMessage");
    }

    @Test
    void theGapsBetweenTheColumnsAreSeparatorsThatResizeThem() throws Exception {
        String page = mvc.perform(html(get("/owner/live").session(sandbox.session())))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String script = body("/js/live.js");
        String styles = body("/css/live.css");

        assertThat(page)
                .containsPattern("<section class=\"col tip\"[\\s\\S]*id=\"handle-tip\" role=\"separator\" "
                        + "aria-orientation=\"vertical\" tabindex=\"0\"[\\s\\S]*<section class=\"col panda\"")
                .containsPattern("<section class=\"col panda\"[\\s\\S]*id=\"handle-dash\" role=\"separator\" "
                        + "aria-orientation=\"vertical\" tabindex=\"0\"[\\s\\S]*<section class=\"col dash\"")
                .contains("<div class=\"drag-cover\" id=\"drag-cover\" hidden></div>");
        assertThat(script)
                .contains("const MIN_TIP = 340;", "const MIN_PANDA = 280;", "const MIN_DASH = 400;")
                .contains("const DEFAULT_TIP = 390;", "const DEFAULT_DASH = 460;", "const KEY_STEP = 16;")
                .contains(
                        "event.key === \"ArrowLeft\" || event.key === \"ArrowRight\"", "addEventListener(\"dblclick\"")
                .contains("handle.setPointerCapture(event.pointerId);", "el(\"drag-cover\").hidden = false;")
                .contains("el(\"drag-cover\").hidden = true;", "stage.style.setProperty(\"--tip\"")
                .doesNotContain("localStorage", "sessionStorage", "document.cookie");
        assertThat(styles)
                .contains("grid-template-columns: var(--tip) 14px minmax(280px, 1fr) 14px var(--dash);")
                .contains(".drag-cover {\n    position: fixed;\n    inset: 0;\n    z-index: 10;");
    }

    @Test
    void embeddedPagesOnlyChangeWhatTheyShow() throws Exception {
        String owner = body("/js/owner.js");
        String ownerPage = mvc.perform(get("/owner").session(sandbox.session()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String ownerStyles = body("/css/owner.css");

        assertThat(ownerPage)
                .containsPattern(
                        "id=\"open-sheet\"[^>]*>Continue on another device</button>\\s*"
                                + "<label class=\"split-switch\">\\s*<input type=\"checkbox\" role=\"switch\" id=\"split-toggle\">");
        assertThat(owner)
                .contains("const LEAVE = EMBEDDED ? window.top.location : window.location;")
                .contains("LEAVE.assign(\"/judges?sandboxExpired\")", "LEAVE.assign(\"/owner/login\")")
                .contains("el(\"sign-out\").target = \"_top\";")
                .contains("window.location.assign(\"/owner/live\")", "window.top.location.assign(\"/owner\")")
                .contains("el(\"split-toggle\").checked = EMBEDDED;");
        assertThat(ownerStyles)
                .contains("@media (min-width: 1100px) {\n    .split-switch {\n        display: inline-flex;")
                .contains(
                        "html.embedded #sandbox-tip,\nhtml.embedded #watch,\nhtml.embedded .demo-tip {\n    display: none;");
        assertThat(owner)
                .contains("make(\"a\", \"ghost demo-tip\", \"Tip your panda\")")
                .contains("make(\"span\", null, \"Demo tip. Send a real tip to try refunds.\"), link");

        assertThat(body("/js/tip.js"))
                .contains("el(\"tip-again\").addEventListener(\"click\", () => window.location.reload());");
        assertThat(body("/css/tip.css"))
                .contains("html.embedded #watch-panda,\nhtml.embedded .foot {\n    display: none;");
        assertThat(body("/js/panda.js")).contains("\"Send a tip on the left and watch this one react.\"");
        for (String path : new String[] {"/js/tip.js", "/js/panda.js", "/js/owner.js"}) {
            assertThat(body(path)).as(path).contains("new URLSearchParams(window.location.search).has(\"embed\")");
        }
    }

    private void assertSameAsUnknownPath(UnaryOperator<MockHttpServletRequestBuilder> as) throws Exception {
        MockHttpServletResponse live =
                mvc.perform(as.apply(html(get("/owner/live")))).andReturn().getResponse();
        MockHttpServletResponse unknown =
                mvc.perform(as.apply(html(get("/owner/livf")))).andReturn().getResponse();

        assertThat(live.getStatus()).isEqualTo(404).isEqualTo(unknown.getStatus());
        assertThat(live.getContentType()).isEqualTo(unknown.getContentType());
        assertThat(withoutNonce(live)).isEqualTo(withoutNonce(unknown)).contains("Not found");

        MockHttpServletResponse liveJson = mvc.perform(
                        as.apply(get("/owner/live").accept(MediaType.APPLICATION_JSON)))
                .andReturn()
                .getResponse();
        MockHttpServletResponse unknownJson = mvc.perform(
                        as.apply(get("/owner/livf").accept(MediaType.APPLICATION_JSON)))
                .andReturn()
                .getResponse();

        assertThat(liveJson.getStatus()).isEqualTo(404).isEqualTo(unknownJson.getStatus());
        assertThat(liveJson.getContentAsString().replace("/owner/live", "/owner/livf"))
                .isEqualTo(unknownJson.getContentAsString());
    }

    private void assertNotFrameable(MockHttpServletRequestBuilder request, int status) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        String path = request.buildRequest(new MockServletContext()).getRequestURI();

        assertThat(response.getStatus()).as(path).isEqualTo(status);
        assertThat(response.getHeader("Content-Security-Policy"))
                .as(path)
                .contains("frame-ancestors 'none'")
                .doesNotContain("frame-ancestors 'self'");
        assertThat(response.getHeaders("X-Frame-Options")).as(path).containsExactly("DENY");
    }

    private static String withoutNonce(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString().replaceAll("nonce=\"[A-Za-z0-9_-]+\"", "nonce=\"\"");
    }

    private static MockHttpServletRequestBuilder html(MockHttpServletRequestBuilder request) {
        return request.accept(MediaType.TEXT_HTML);
    }

    private String body(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
