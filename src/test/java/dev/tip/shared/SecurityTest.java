package dev.tip.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class SecurityTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @LocalServerPort
    int port;

    @Test
    void unknownPathsAreDenied() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().isForbidden());
        mvc.perform(post("/api/private/things")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
    }

    @Test
    void deniedPathIsProblemDetailsForAnApiClient() throws Exception {
        expectProblem(mvc.perform(get("/admin").accept(MediaType.APPLICATION_JSON)), 403, "FORBIDDEN")
                .andExpect(jsonPath("$.instance").value("/admin"));
    }

    @Test
    void unknownPublicPathIsProblemDetails404() throws Exception {
        expectProblem(mvc.perform(get("/api/public/no-such-endpoint")), 404, "NOT_FOUND");
    }

    @Test
    void notFoundDetailIsFixedWhateverRaisedIt() throws Exception {
        String body = expectProblem(mvc.perform(get("/t/owner/login")), 404, "NOT_FOUND")
                .andExpect(jsonPath("$.detail").value("The requested resource was not found."))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContainIgnoringCase("static resource");
    }

    @Test
    void unknownPathOverRealHttpHasNoFrameworkWording() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/t/owner/login"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body())
                .contains("\"detail\":\"The requested resource was not found.\"")
                .contains("\"code\":\"NOT_FOUND\"")
                .doesNotContainIgnoringCase("static")
                .doesNotContainIgnoringCase("No resource")
                .doesNotContain("Exception");
    }

    @Test
    void methodNotAllowedDetailIsFixed() throws Exception {
        String body = expectProblem(
                        mvc.perform(get("/api/public/tips/{tipId}/capture", UUID.randomUUID())),
                        405,
                        "METHOD_NOT_ALLOWED")
                .andExpect(jsonPath("$.detail").value("This method is not allowed for the requested resource."))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("Request method").doesNotContain("'GET'");
    }

    @Test
    void unauthenticatedOwnerApiIsProblemDetails401() throws Exception {
        expectProblem(
                mvc.perform(post("/api/owner/tips/{tipId}/refund", UUID.randomUUID())
                        .with(csrf())),
                401,
                "UNAUTHORIZED");
    }

    @Test
    void requestRejectedByTheContainerIsProblemDetailsToo() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(
                                        "http://localhost:" + port + "/api/public/devices/demo-panda;jsessionid=x"))
                                .header("Accept", "text/html")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        assertThat(response.body())
                .contains("\"code\":\"BAD_REQUEST\"")
                .doesNotContain("timestamp")
                .doesNotContain("\"path\"")
                .doesNotContain("Exception");
    }

    @Test
    void springCreatesNoInMemoryUserWithAGeneratedPassword() {
        assertThat(context.getBeansOfType(InMemoryUserDetailsManager.class)).isEmpty();
        assertThat(context.getBeansOfType(UserDetailsService.class).values())
                .singleElement()
                .satisfies(service -> assertThat(service.getClass().getName()).startsWith("dev.tip.owners."));
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        mvc.perform(get("/api/public/devices/demo-panda").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self' 'nonce-")))
                .andExpect(header().string("Content-Security-Policy", containsString("https://*.paypal.com")))
                .andExpect(header().string(
                                "Content-Security-Policy", org.hamcrest.Matchers.not(containsString("unsafe-inline"))))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")));
    }

    @Test
    void ownerPagesCarrySecurityHeadersToo() throws Exception {
        mvc.perform(get("/owner/login").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")));
    }

    private static ResultActions expectProblem(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }
}
