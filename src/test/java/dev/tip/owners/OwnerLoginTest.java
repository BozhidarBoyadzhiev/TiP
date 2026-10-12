package dev.tip.owners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

class OwnerLoginTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestData data;

    String email;

    @BeforeEach
    void owner() {
        email = TestData.uniqueEmail();
        data.owner(email, "Ada Owner", TestData.OWNER_PASSWORD);
    }

    @Test
    void loginPageIsPublicAndCarriesACsrfToken() throws Exception {
        mvc.perform(get("/owner/login"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("{{"))));
    }

    @Test
    void correctCredentialsCreateASessionWithANewId() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String idBefore = session.getId();

        login(email.toUpperCase(), TestData.OWNER_PASSWORD, randomIp(), session)
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/owner"));

        assertThat(session.getId()).isNotEqualTo(idBefore);
        mvc.perform(get("/owner").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/js/owner.js")));
    }

    @Test
    void wrongPasswordAndUnknownEmailGetIdenticalResponses() throws Exception {
        String ip = randomIp();
        MockHttpServletResponse wrongPassword = login(email, "not-the-password", ip, new MockHttpSession())
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
                .andReturn()
                .getResponse();
        MockHttpServletResponse unknownEmail = login(
                        TestData.uniqueEmail(), "not-the-password", ip, new MockHttpSession())
                .andReturn()
                .getResponse();

        assertThat(unknownEmail.getStatus()).isEqualTo(wrongPassword.getStatus());
        assertThat(unknownEmail.getContentAsString()).isEqualTo(wrongPassword.getContentAsString());
    }

    @Test
    void sixthAttemptInAMinuteFromOneIpIs429() throws Exception {
        String ip = randomIp();
        for (int i = 0; i < 5; i++) {
            login(TestData.uniqueEmail(), "wrong-password", ip, new MockHttpSession())
                    .andExpect(status().isUnauthorized());
        }

        login(email, TestData.OWNER_PASSWORD, ip, new MockHttpSession())
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void sixthAttemptInAMinuteForOneEmailIs429FromAnyIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(email, "wrong-password", randomIp(), new MockHttpSession()).andExpect(status().isUnauthorized());
        }

        login(email, TestData.OWNER_PASSWORD, randomIp(), new MockHttpSession())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void loginWithoutCsrfTokenIs403() throws Exception {
        mvc.perform(post("/owner/login")
                        .param("email", email)
                        .param("password", TestData.OWNER_PASSWORD)
                        .with(remoteAddr(randomIp())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        login(email, TestData.OWNER_PASSWORD, randomIp(), session).andExpect(status().isFound());

        mvc.perform(post("/owner/logout").session(session).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/owner/login?signedOut"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void ownerPageCarriesNothingAboutTheOwner() throws Exception {
        String other = TestData.uniqueEmail();
        data.owner(other, "<b>Bold</b>", TestData.OWNER_PASSWORD);
        MockHttpSession session = new MockHttpSession();
        login(other, TestData.OWNER_PASSWORD, randomIp(), session).andExpect(status().isFound());

        MvcResult page = mvc.perform(get("/owner").session(session))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(page.getResponse().getContentAsString()).doesNotContain("Bold", other);
    }

    private ResultActions login(String email, String password, String ip, MockHttpSession session) throws Exception {
        MockHttpServletRequestBuilder request = post("/owner/login")
                .session(session)
                .param("email", email)
                .param("password", password)
                .with(csrf())
                .with(remoteAddr(ip));
        return mvc.perform(request);
    }

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    static String randomIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }
}
