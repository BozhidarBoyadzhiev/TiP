package dev.tip.owners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.tip.TestData;
import dev.tip.owners.api.OwnerPrincipal;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Signs in to new sandboxes and the public demo over MockMvc, each from a fresh client address. */
final class Judges {

    private final MockMvc mvc;
    private final JdbcClient jdbc;

    Judges(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    /** A sandbox made with {@code code}, its session and its device. */
    Sandbox sandbox(String code, String timeZone) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletResponse response = judgeSession(code, timeZone, TestData.uniquePublicIp(), session);
        assertThat(response.getStatus()).as("sandbox sign in").isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/owner");
        UUID ownerId = principal(session).id();
        Map<String, Object> device = jdbc.sql("SELECT id, slug FROM device WHERE owner_id = ?")
                .param(ownerId)
                .query()
                .singleRow();
        return new Sandbox(session, ownerId, (UUID) device.get("id"), (String) device.get("slug"));
    }

    MockHttpServletResponse judgeSession(String code, String timeZone, String ip, MockHttpSession session)
            throws Exception {
        return mvc.perform(post("/judges/session")
                        .session(session)
                        .param("code", code)
                        .param("timeZone", timeZone)
                        .with(csrf())
                        .with(remoteAddr(ip)))
                .andReturn()
                .getResponse();
    }

    /** A read only session on the public demo. */
    MockHttpSession demo() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletResponse response = mvc.perform(
                        post("/demo/session").session(session).with(csrf()).with(remoteAddr(TestData.uniquePublicIp())))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as("demo sign in").isEqualTo(302);
        return session;
    }

    /** The owner the session is signed in as. */
    static OwnerPrincipal principal(MockHttpSession session) {
        SecurityContext context = (SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return (OwnerPrincipal) context.getAuthentication().getPrincipal();
    }

    static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /** The newest demo tip of the device with this flag, or any when {@code flagged} is null. */
    UUID demoTip(UUID deviceId, Boolean flagged, String language) {
        return jdbc.sql("""
                        SELECT id FROM tip WHERE device_id = ? AND demo AND (CAST(? AS boolean) IS NULL OR flagged = ?)
                          AND (CAST(? AS varchar) IS NULL OR message_language = ?)
                          AND status = 'CAPTURED'
                        ORDER BY created_at DESC LIMIT 1""")
                .params(deviceId, flagged, flagged, language, language)
                .query(UUID.class)
                .single();
    }

    record Sandbox(MockHttpSession session, UUID ownerId, UUID deviceId, String slug) {}
}
