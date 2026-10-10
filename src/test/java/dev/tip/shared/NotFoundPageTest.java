package dev.tip.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class NotFoundPageTest extends IntegrationTest {

    private static final String BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    @Autowired
    MockMvc mvc;

    @LocalServerPort
    int port;

    @Test
    void unknownSlugOnTheTipAndPandaPagesIsTheNotFoundPageWithANonce() throws Exception {
        for (String path : new String[] {"/t/no-such-panda", "/p/no-such-panda"}) {
            expectNotFoundPage(mvc.perform(get(path)));
        }
    }

    @Test
    void knownSlugStillServesItsPage() throws Exception {
        mvc.perform(get("/t/demo-panda").header("Accept", BROWSER_ACCEPT))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/js/tip.js")));
        mvc.perform(get("/p/demo-panda").header("Accept", BROWSER_ACCEPT))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/js/panda.js")));
    }

    @Test
    void browserGetForAnUnknownPathIsTheNotFoundPage() throws Exception {
        expectNotFoundPage(mvc.perform(get("/nope").header("Accept", BROWSER_ACCEPT)));
        expectNotFoundPage(mvc.perform(get("/nope").accept(MediaType.TEXT_HTML)));
        // Under a public prefix the path passes security and ends in a missing resource.
        expectNotFoundPage(mvc.perform(get("/css/nope.css").header("Accept", BROWSER_ACCEPT)));
        expectNotFoundPage(mvc.perform(get("/t/owner/login").header("Accept", BROWSER_ACCEPT)));
    }

    @Test
    void apiClientsKeepTheirProblemDetails() throws Exception {
        expectProblem(mvc.perform(get("/nope").accept(MediaType.APPLICATION_JSON)), 403, "FORBIDDEN");
        expectProblem(mvc.perform(get("/nope")), 403, "FORBIDDEN");
        expectProblem(
                mvc.perform(get("/nope").header("Accept", "text/html;q=0.5, application/json")), 403, "FORBIDDEN");
        mvc.perform(post("/nope").header("Accept", BROWSER_ACCEPT))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void machinePathsAlwaysAnswerProblemDetails() throws Exception {
        expectProblem(
                mvc.perform(get("/api/public/devices/no-such-panda").header("Accept", BROWSER_ACCEPT)),
                404,
                "DEVICE_NOT_FOUND");
        expectProblem(
                mvc.perform(get("/api/public/no-such-endpoint").header("Accept", BROWSER_ACCEPT)), 404, "NOT_FOUND");
        expectProblem(mvc.perform(get("/actuator/env").header("Accept", BROWSER_ACCEPT)), 403, "FORBIDDEN");
        expectProblem(mvc.perform(get("/webhooks/nope").header("Accept", BROWSER_ACCEPT)), 403, "FORBIDDEN");
    }

    @Test
    void notFoundPageNeverEchoesThePathOrQuery() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                        + "/nope-%3Cscript%3Ealert(1)%3C/script%3E"
                                        + "?q=%3Cscript%3Ealert(2)%3C%2Fscript%3E&slug=evil-marker"))
                                .header("Accept", BROWSER_ACCEPT)
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> unknownSlug = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                        + "/t/evil-marker-slug?q=%3Cscript%3Ealert(3)%3C%2Fscript%3E"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        for (HttpResponse<String> each : List.of(response, unknownSlug)) {
            assertThat(each.statusCode()).isEqualTo(404);
            assertThat(each.body())
                    .contains("This panda wandered off")
                    .doesNotContain("alert(", "evil-marker", "nope-", "%3C", "&lt;script");
        }
    }

    private static void expectNotFoundPage(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("This panda wandered off")))
                .andExpect(content().string(containsString("href=\"/css/brand.css\"")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Content-Security-Policy", not(containsString("unsafe-inline"))))
                .andExpect(r -> {
                    String policy = r.getResponse().getHeader("Content-Security-Policy");
                    String nonce = policy.replaceAll(".*script-src 'self' 'nonce-([A-Za-z0-9_-]+)'.*", "$1");
                    assertThat(nonce).hasSizeGreaterThan(20);
                    assertThat(r.getResponse().getContentAsString())
                            .contains("nonce=\"" + nonce + "\"")
                            .doesNotContain("{{CSP_NONCE}}", " style=", "<style");
                });
    }

    private static void expectProblem(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }
}
