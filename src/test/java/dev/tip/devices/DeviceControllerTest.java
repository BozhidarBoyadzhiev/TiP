package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class DeviceControllerTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DemoDeviceSeeder seeder;

    @Autowired
    JdbcClient jdbc;

    @Test
    void returnsTipConfigForTheSeededDemoDevice() throws Exception {
        mvc.perform(get("/api/public/devices/demo-panda"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Demo Panda"))
                .andExpect(jsonPath("$.currency").value("GBP"))
                .andExpect(jsonPath("$.presetAmountsMinor[0]").value(100))
                .andExpect(jsonPath("$.presetAmountsMinor[2]").value(500))
                .andExpect(jsonPath("$.minMinor").value(100))
                .andExpect(jsonPath("$.maxMinor").value(10000))
                .andExpect(jsonPath("$.paypalClientId").value("test-client-id"));
    }

    @Test
    void unknownSlugIsProblemDetails404() throws Exception {
        mvc.perform(get("/api/public/devices/no-such-panda"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
    }

    @Test
    void malformedSlugIsRejected() throws Exception {
        mvc.perform(get("/api/public/devices/Not_A_Slug"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void seederDoesNotDuplicateAnExistingDevice() {
        seeder.run(new DefaultApplicationArguments());

        Long count = jdbc.sql("SELECT count(*) FROM device WHERE slug = 'demo-panda'")
                .query(Long.class)
                .single();
        assertThat(count).isEqualTo(1);
    }
}
