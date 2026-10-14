package dev.tip.owners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.devices.RawWebSocket;
import dev.tip.owners.api.OwnerSignedOut;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** The shared read only demo dashboard: anyone may look, nobody may change anything. */
@RecordApplicationEvents
class PublicDemoTest extends IntegrationTest {

    private static final Set<RequestMethod> SAFE = Set.of(RequestMethod.GET, RequestMethod.HEAD, RequestMethod.OPTIONS);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PublicDemo publicDemo;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Autowired
    ApplicationEvents events;

    @LocalServerPort
    int port;

    Judges judges;

    @BeforeEach
    void judges() {
        judges = new Judges(mvc, jdbc);
    }

    @Test
    void startupMadeOneDemoOwnerWithAVirtualPandaAndTodaysSamples() {
        assertThat(jdbc.sql("SELECT count(*) FROM owner WHERE kind = 'PUBLIC_DEMO'")
                        .query(Long.class)
                        .single())
                .isOne();
        publicDemo.refresh();

        assertThat(jdbc.sql("SELECT count(*) FROM owner WHERE kind = 'PUBLIC_DEMO'")
                        .query(Long.class)
                        .single())
                .isOne();
        assertThat(demoTipIds()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(jdbc.sql("SELECT count(*) FROM device WHERE slug = 'public-demo'")
                        .query(Long.class)
                        .single())
                .isOne();
    }

    @Test
    void theDemoSessionReadsEverythingIncludingAHiddenMessage() throws Exception {
        MockHttpSession demo = judges.demo();
        UUID hidden = judges.demoTip(demoDevice(), true, "bg");

        for (String path : List.of(
                "/api/owner/me",
                "/api/owner/tips",
                "/api/owner/today",
                "/api/owner/requests",
                "/api/owner/digest",
                "/api/owner/devices",
                "/api/owner/tips/" + hidden)) {
            mvc.perform(get(path).session(demo)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/owner/tips/{id}/message", hidden).session(demo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("bg"));
        mvc.perform(get("/owner").session(demo)).andExpect(status().isOk());
    }

    @Test
    void everyMutatingOwnerEndpointAnswers403ReadOnly() throws Exception {
        MockHttpSession demo = judges.demo();
        List<String> swept = new ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            Set<RequestMethod> mutating = methods.isEmpty()
                    ? Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE)
                    : methods.stream().filter(m -> !SAFE.contains(m)).collect(Collectors.toSet());
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/owner")) {
                    continue;
                }
                for (RequestMethod method : mutating) {
                    String path =
                            pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                    mvc.perform(request(HttpMethod.valueOf(method.name()), path)
                                    .session(demo)
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"hidden\":false}"))
                            .andExpect(status().isForbidden())
                            .andExpect(jsonPath("$.code").value("DEMO_READ_ONLY"));
                    swept.add(method + " " + pattern);
                }
            }
        }

        assertThat(swept)
                .contains(
                        "POST /api/owner/tips/{tipId}/refund",
                        "PUT /api/owner/tips/{id}/verdict",
                        "POST /api/owner/resume-link");
        UUID hidden = judges.demoTip(demoDevice(), true, null);
        mvc.perform(request(HttpMethod.PUT, "/api/owner/tips/" + hidden + "/verdict")
                        .session(demo)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":false}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT owner_verdict FROM tip WHERE id = ?")
                        .param(hidden)
                        .query()
                        .singleRow())
                .containsEntry("owner_verdict", null);
    }

    @Test
    void signingOutOfTheDemoTellsNoOtherViewer() throws Exception {
        MockHttpSession demo = judges.demo();

        mvc.perform(post("/owner/logout").session(demo).with(csrf())).andExpect(redirectedUrl("/t/demo-panda"));

        assertThat(events.stream(OwnerSignedOut.class)).isEmpty();
    }

    @Test
    void theDemoPandaCanBeWatchedButTakesNoTipsAndNoHardware() throws Exception {
        mvc.perform(get("/p/public-demo")).andExpect(status().isOk());
        mvc.perform(get("/api/public/devices/public-demo")).andExpect(status().isOk());
        mvc.perform(get("/t/public-demo").accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
        mvc.perform(post("/api/public/devices/public-demo/tips")
                        .with(TestData.newClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":200,\"currency\":\"GBP\",\"message\":\"hello demo\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));

        String secret = TestData.randomSecret();
        jdbc.sql("UPDATE device SET secret_hash = sha256(?) WHERE id = ?")
                .params(Base64.getUrlDecoder().decode(secret), demoDevice())
                .update();
        try (RawWebSocket socket = RawWebSocket.handshake(
                port,
                "/ws/device/" + demoDevice(),
                Map.of("Authorization", "Bearer " + secret, "X-Forwarded-For", TestData.uniquePublicIp()))) {
            assertThat(socket.status).isEqualTo(401);
        }
    }

    @Test
    void theNightlyJobReplacesTheSamplesOnceADaySoTodayStaysCurrent() {
        Set<UUID> before = demoTipIds();
        try {
            Instant tomorrow = clock.instant().plus(Duration.ofDays(1));
            clock.pin(tomorrow);
            publicDemo.nightly();

            Set<UUID> after = demoTipIds();
            assertThat(after).hasSameSizeAs(before).doesNotContainAnyElementsOf(before);
            assertThat(jdbc.sql("SELECT local_day FROM owner_digest WHERE owner_id = ?")
                            .param(demoOwner())
                            .query(LocalDate.class)
                            .list())
                    .containsExactly(LocalDate.ofInstant(tomorrow, ZoneId.of("Europe/London")));

            publicDemo.nightly();
            assertThat(demoTipIds()).isEqualTo(after);
        } finally {
            clock.release();
            jdbc.sql("DELETE FROM tip WHERE device_id = ? AND demo")
                    .param(demoDevice())
                    .update();
            publicDemo.refresh();
        }
    }

    @Test
    void demoSessionsAreRateLimitedGenerously() throws Exception {
        String ip = TestData.uniquePublicIp();
        for (int i = 0; i < 30; i++) {
            mvc.perform(post("/demo/session").with(csrf()).with(Judges.remoteAddr(ip)))
                    .andExpect(status().isFound());
        }

        mvc.perform(post("/demo/session").with(csrf()).with(Judges.remoteAddr(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    private Set<UUID> demoTipIds() {
        return Set.copyOf(jdbc.sql("SELECT id FROM tip WHERE device_id = ? AND demo")
                .param(demoDevice())
                .query(UUID.class)
                .list());
    }

    private UUID demoDevice() {
        return jdbc.sql("SELECT id FROM device WHERE slug = 'public-demo'")
                .query(UUID.class)
                .single();
    }

    private UUID demoOwner() {
        return jdbc.sql("SELECT id FROM owner WHERE kind = 'PUBLIC_DEMO'")
                .query(UUID.class)
                .single();
    }
}
