package dev.tip.owners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.owners.api.OwnerPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two sandboxes and a real owner never see each other's data: every owner endpoint, the reveal and the verdict
 * answer another's tip with the same 404 as an unknown id, and every list holds only the caller's own.
 */
class SandboxIsolationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestData data;

    @Autowired
    JsonMapper json;

    Judges.Sandbox first;
    Judges.Sandbox second;
    OwnerPrincipal real;
    UUID realDevice;
    String realSlug;
    UUID realTip;
    UUID realHiddenTip;

    @BeforeEach
    void threeOwners() throws Exception {
        Judges judges = new Judges(mvc, jdbc);
        first = judges.sandbox(JUDGE_CODE, "UTC");
        second = judges.sandbox(JUDGE_CODE, "UTC");
        String email = TestData.uniqueEmail();
        real = new OwnerPrincipal(data.owner(email, "Real", TestData.OWNER_PASSWORD), email, "Real", null);
        realSlug = TestData.uniqueSlug();
        realDevice = data.device(real.id(), realSlug, "Real Panda");
        realTip = data.tip(
                realDevice,
                "CAPTURED",
                Instant.now(),
                Map.of("message", "Real owner's tip", "paypal_capture_id", TestData.uniqueId("CAPTURE")));
        realHiddenTip = data.tip(
                realDevice,
                "CAPTURED",
                Instant.now(),
                Map.of("message", "Real hidden", "flagged", true, "flag_reason", "insult", "ai_status", "DONE"));
    }

    @Test
    void anotherOwnersTipIsTheSame404AsAnUnknownIdOnEveryEndpoint() throws Exception {
        UUID firstShown = new Judges(mvc, jdbc).demoTip(first.deviceId(), false, null);
        UUID firstHidden = new Judges(mvc, jdbc).demoTip(first.deviceId(), true, null);
        UUID unknown = UUID.randomUUID();

        for (Caller caller : List.of(asSecond(), asReal())) {
            for (UUID foreign : List.of(firstShown, firstHidden)) {
                assertSameAsUnknown(caller, foreign, unknown);
            }
        }
        for (UUID foreign : List.of(realTip, realHiddenTip)) {
            assertSameAsUnknown(asFirst(), foreign, unknown);
            assertSameAsUnknown(asSecond(), foreign, unknown);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM tip WHERE owner_verdict IS NOT NULL AND id IN (?, ?)")
                        .params(firstHidden, realHiddenTip)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(data.tipStatus(realTip)).isEqualTo("CAPTURED");
    }

    @Test
    void everyListHoldsOnlyTheCallersOwnData() throws Exception {
        List<String> firstIds = tipIds(first.deviceId());
        List<String> secondIds = tipIds(second.deviceId());

        JsonNode firstTips = body(asFirst(), get("/api/owner/tips?size=50"));
        assertThat(firstTips.path("items").findValuesAsString("id"))
                .containsExactlyInAnyOrderElementsOf(firstIds)
                .doesNotContainAnyElementsOf(secondIds)
                .doesNotContain(realTip.toString());
        assertThat(body(asReal(), get("/api/owner/tips?size=50")).path("items").findValuesAsString("id"))
                .containsExactlyInAnyOrder(realTip.toString(), realHiddenTip.toString());

        assertThat(body(asFirst(), get("/api/owner/devices")).findValuesAsString("slug"))
                .containsExactly(first.slug());
        assertThat(body(asReal(), get("/api/owner/devices")).findValuesAsString("slug"))
                .containsExactly(realSlug);

        JsonNode firstRequests = body(asFirst(), get("/api/owner/requests"));
        assertThat(firstRequests.path("groups").findValues("tipIds").stream()
                        .flatMap(ids -> ids.valueStream().map(JsonNode::asString))
                        .toList())
                .isNotEmpty()
                .allSatisfy(id -> assertThat(firstIds).contains(id));
        assertThat(body(asReal(), get("/api/owner/requests")).path("groups").isEmpty())
                .isTrue();
        assertThat(body(asReal(), get("/api/owner/digest")).isNull()).isTrue();
        assertThat(body(asFirst(), get("/api/owner/digest")).path("bullets").size())
                .isPositive();
        assertThat(body(asReal(), get("/api/owner/today")).path("tipCount").asInt())
                .isEqualTo(2);
        assertThat(body(asFirst(), get("/api/owner/today")).path("tipCount").asInt())
                .isEqualTo(body(asSecond(), get("/api/owner/today"))
                        .path("tipCount")
                        .asInt());
        assertThat(body(asFirst(), get("/api/owner/me")).path("displayName").asString())
                .isEqualTo("Judge");
        assertThat(body(asReal(), get("/api/owner/me")).path("displayName").asString())
                .isEqualTo("Real");
    }

    @Test
    void aResumeLinkSignsInOnlyToItsOwnSandbox() throws Exception {
        String url = body(asFirst(), post("/api/owner/resume-link").with(csrf()))
                .path("url")
                .asString();
        MockHttpSession other = new MockHttpSession();
        MockHttpServletResponse resumed = mvc.perform(
                        Judges.resume(Judges.token(url)).session(other).with(TestData.newClient()))
                .andReturn()
                .getResponse();

        assertThat(resumed.getStatus()).isEqualTo(204);
        assertThat(Judges.principal(other).id()).isEqualTo(first.ownerId());
        assertThat(jdbc.sql("SELECT owner_id FROM resume_token WHERE owner_id = ?")
                        .param(first.ownerId())
                        .query(UUID.class)
                        .list())
                .hasSize(1);
    }

    private void assertSameAsUnknown(Caller caller, UUID foreign, UUID unknown) throws Exception {
        for (String path : List.of("/api/owner/tips/%s", "/api/owner/tips/%s/message")) {
            assertSame(caller.call(get(path.formatted(foreign))), caller.call(get(path.formatted(unknown))));
        }
        assertSame(
                caller.call(put("/api/owner/tips/%s/verdict".formatted(foreign))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":false}")),
                caller.call(put("/api/owner/tips/%s/verdict".formatted(unknown))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\":false}")));
        assertSame(
                caller.call(post("/api/owner/tips/%s/refund".formatted(foreign)).with(csrf())),
                caller.call(post("/api/owner/tips/%s/refund".formatted(unknown)).with(csrf())));
    }

    /** The same status, code and detail; only {@code instance} names the id. */
    private void assertSame(MockHttpServletResponse foreign, MockHttpServletResponse unknown) throws Exception {
        assertThat(foreign.getStatus()).isEqualTo(404).isEqualTo(unknown.getStatus());
        JsonNode a = json.readTree(foreign.getContentAsString());
        JsonNode b = json.readTree(unknown.getContentAsString());
        assertThat(a.path("code")).isEqualTo(b.path("code"));
        assertThat(a.path("detail")).isEqualTo(b.path("detail"));
    }

    private JsonNode body(Caller caller, MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = caller.call(request);
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    private List<String> tipIds(UUID deviceId) {
        return jdbc.sql("SELECT id::text FROM tip WHERE device_id = ? AND status IN"
                        + " ('CAPTURED', 'PENDING', 'REFUNDED', 'REVERSED')")
                .param(deviceId)
                .query(String.class)
                .list();
    }

    private Caller asFirst() {
        return request ->
                mvc.perform(request.session(first.session())).andReturn().getResponse();
    }

    private Caller asSecond() {
        return request ->
                mvc.perform(request.session(second.session())).andReturn().getResponse();
    }

    private Caller asReal() {
        return request -> mvc.perform(request.with(user(real))).andReturn().getResponse();
    }

    @FunctionalInterface
    private interface Caller {
        MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception;
    }
}
