package dev.tip.tipping;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

class CaptureRateLimitTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void theThirtyFirstCaptureInAMinuteFromOneIpIs429AndOtherClientsAreUnaffected() throws Exception {
        String ip = TestData.uniquePublicIp();
        for (int i = 0; i < 30; i++) {
            mvc.perform(post("/api/public/tips/{id}/capture", UUID.randomUUID()).with(from(ip)))
                    .andExpect(status().isNotFound());
        }

        mvc.perform(post("/api/public/tips/{id}/capture", UUID.randomUUID()).with(from(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        mvc.perform(post("/api/public/tips/{id}/capture", UUID.randomUUID()).with(TestData.newClient()))
                .andExpect(status().isNotFound());
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
