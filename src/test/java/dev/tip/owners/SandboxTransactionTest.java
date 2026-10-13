package dev.tip.owners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.dashboard.api.SampleTips;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** A sandbox is made in one transaction: when seeding fails, neither the owner nor its panda stays behind. */
class SandboxTransactionTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoSpyBean
    SampleTips samples;

    @Test
    void aFailureWhileSeedingLeavesNothingAndSignsNobodyIn() throws Exception {
        doThrow(new IllegalStateException("seeding failed")).when(samples).seed(any(), any(), any());
        long owners = count("SELECT count(*) FROM owner WHERE kind = 'SANDBOX'");
        long devices = count("SELECT count(*) FROM device WHERE slug LIKE 'judge-%'");
        MockHttpSession session = new MockHttpSession();

        MockHttpServletResponse response =
                new Judges(mvc, jdbc).judgeSession(JUDGE_CODE, "UTC", TestData.uniquePublicIp(), session);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).doesNotContain("seeding failed");
        assertThat(count("SELECT count(*) FROM owner WHERE kind = 'SANDBOX'")).isEqualTo(owners);
        assertThat(count("SELECT count(*) FROM device WHERE slug LIKE 'judge-%'"))
                .isEqualTo(devices);
        assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
