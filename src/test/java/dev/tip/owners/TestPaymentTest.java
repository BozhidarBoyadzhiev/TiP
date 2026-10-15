package dev.tip.owners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.owners.api.OwnerPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The test payment details of a judge's sandbox. The test profile sets the three card values and the account's
 * email but not its password, so the card is there and the account is not.
 */
@ExtendWith(OutputCaptureExtension.class)
class TestPaymentTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    TestData data;

    @Test
    void aSandboxGetsTheConfiguredCardAndTheSampleBillingWithoutStoring(CapturedOutput output) throws Exception {
        Judges.Sandbox sandbox = new Judges(mvc, jdbc).sandbox(JUDGE_CODE, "UTC");

        mvc.perform(get("/api/owner/test-payment").session(sandbox.session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.card.number").value("judge-card-number-for-tests"))
                .andExpect(jsonPath("$.card.expiry").value("judge-card-expiry"))
                .andExpect(jsonPath("$.card.cvc").value("judge-card-cvc"))
                .andExpect(jsonPath("$.paypal").isEmpty())
                .andExpect(jsonPath("$.billing.name").value("Test Judge"))
                .andExpect(jsonPath("$.billing.postcode").value("SW1A 1AA"))
                .andExpect(jsonPath("$.billing.mobile").value("07700 900123"))
                .andExpect(jsonPath("$.billing.email").value("judge@example.com"));

        assertThat(output.getAll())
                .doesNotContain("judge-card-number-for-tests", "judge-card-expiry", "judge-card-cvc")
                .doesNotContain("judge-buyer@example.com");
    }

    @Test
    void aRealOwnerGetsThe404OfAnUnknownPath() throws Exception {
        String email = TestData.uniqueEmail();
        OwnerPrincipal real =
                new OwnerPrincipal(data.owner(email, "Real", TestData.OWNER_PASSWORD), email, "Real", null);

        assertLikeAnUnknownPath(user(real), null);
    }

    @Test
    void thePublicDemoGetsThe404OfAnUnknownPath() throws Exception {
        MockHttpSession demo = new Judges(mvc, jdbc).demo();

        assertLikeAnUnknownPath(request -> request, demo);
    }

    @Test
    void signedOutGets401() throws Exception {
        mvc.perform(get("/api/owner/test-payment")).andExpect(status().isUnauthorized());
    }

    private void assertLikeAnUnknownPath(RequestPostProcessor who, MockHttpSession session) throws Exception {
        MockHttpServletResponse details = perform("/api/owner/test-payment", who, session);
        MockHttpServletResponse unknown = perform("/api/owner/no-such-thing", who, session);

        assertThat(details.getStatus()).isEqualTo(404).isEqualTo(unknown.getStatus());
        assertThat(withoutInstance(details)).isEqualTo(withoutInstance(unknown));
        assertThat(details.getContentAsString()).doesNotContain("judge-card");
    }

    private MockHttpServletResponse perform(String path, RequestPostProcessor who, MockHttpSession session)
            throws Exception {
        var request = get(path).with(who);
        if (session != null) {
            request.session(session);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private String withoutInstance(MockHttpServletResponse response) throws Exception {
        var problem = (ObjectNode) json.readTree(response.getContentAsString());
        problem.remove("instance");
        return problem.toString();
    }
}
