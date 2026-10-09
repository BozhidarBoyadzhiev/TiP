package dev.tip.shared;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class SecurityTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void unknownPathsAreDenied() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().isForbidden());
        mvc.perform(post("/api/private/things")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
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
                .andExpect(header().string(
                                "Content-Security-Policy", containsString("script-src 'self' https://*.paypal.com")))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")));
    }
}
