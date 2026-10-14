package dev.tip.owners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.owners.api.OwnerKind;
import dev.tip.owners.api.OwnerPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

/** Where each kind of owner lands after signing out. */
class SignOutTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestData data;

    Judges judges;

    @BeforeEach
    void judges() {
        judges = new Judges(mvc, jdbc);
    }

    @Test
    void aSandboxGoesBackToTheAccessCodePage() throws Exception {
        MockHttpSession session = judges.sandbox(JUDGE_CODE, "UTC").session();

        mvc.perform(post("/owner/logout").session(session).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/judges"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void thePublicDemoGoesToTheRealPandasTipPage() throws Exception {
        MockHttpSession session = judges.demo();

        mvc.perform(post("/owner/logout").session(session).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/t/demo-panda"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aRealOwnerGoesToTheSignInPage() throws Exception {
        String email = TestData.uniqueEmail();
        data.owner(email, "Real", TestData.OWNER_PASSWORD);
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/owner/login")
                        .session(session)
                        .param("email", email)
                        .param("password", TestData.OWNER_PASSWORD)
                        .with(csrf())
                        .with(Judges.remoteAddr(TestData.uniquePublicIp())))
                .andExpect(redirectedUrl("/owner"));

        mvc.perform(post("/owner/logout").session(session).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/owner/login?signedOut"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void withoutARealPandaThePublicDemoGoesToTheSignInPage() {
        SignOutTarget target = new SignOutTarget(" ");

        assertThat(target.targetFor(signedIn(OwnerKind.PUBLIC_DEMO))).isEqualTo("/owner/login?signedOut");
        assertThat(target.targetFor(signedIn(OwnerKind.SANDBOX))).isEqualTo("/judges");
        assertThat(target.targetFor(signedIn(OwnerKind.REAL))).isEqualTo("/owner/login?signedOut");
        assertThat(target.targetFor(null)).isEqualTo("/owner/login?signedOut");
    }

    @Test
    void theRealPandasSlugIsTrimmedAndEncoded() {
        assertThat(new SignOutTarget(" demo-panda ").targetFor(signedIn(OwnerKind.PUBLIC_DEMO)))
                .isEqualTo("/t/demo-panda");
        assertThat(new SignOutTarget("a b").targetFor(signedIn(OwnerKind.PUBLIC_DEMO)))
                .isEqualTo("/t/a%20b");
    }

    private static UsernamePasswordAuthenticationToken signedIn(OwnerKind kind) {
        OwnerPrincipal owner = new OwnerPrincipal(
                UUID.randomUUID(), kind == OwnerKind.REAL ? "o@tip.test" : null, "Owner", null, kind, null);
        return UsernamePasswordAuthenticationToken.authenticated(owner, null, owner.getAuthorities());
    }
}
