package dev.tip.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.owners.api.OwnerPrincipal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class TipHistoryTest extends IntegrationTest {

    private static final Instant DAY_1 = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant DAY_2 = Instant.parse("2026-10-02T12:00:00Z");
    private static final Instant DAY_3 = Instant.parse("2026-10-03T12:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestData data;

    @Autowired
    JdbcClient jdbc;

    OwnerPrincipal owner;
    UUID deviceId;
    String deviceSlug;
    OwnerPrincipal otherOwner;
    UUID otherDeviceId;

    @BeforeEach
    void twoOwnersWithADeviceEach() {
        owner = principal(data.owner());
        deviceSlug = TestData.uniqueSlug();
        deviceId = data.device(owner.id(), deviceSlug, "Owner Panda");
        otherOwner = principal(data.owner());
        otherDeviceId = data.device(otherOwner.id(), TestData.uniqueSlug(), "Other Panda");
    }

    @Test
    void ownerSeesOnlyTheirOwnTipsNewestFirstAcrossTheirDevices() throws Exception {
        UUID secondDevice = data.device(owner.id(), TestData.uniqueSlug(), "Second Panda");
        UUID oldest = tip(deviceId, "CAPTURED", DAY_1);
        UUID newest = tip(secondDevice, "CAPTURED", DAY_3);
        UUID middle = tip(deviceId, "REFUNDED", DAY_2);
        UUID othersTip = tip(otherDeviceId, "CAPTURED", DAY_2);

        list(owner, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(3));

        assertThat(ids(list(owner, ""))).containsExactly(newest, middle, oldest);
        assertThat(ids(list(otherOwner, ""))).containsExactly(othersTip);
    }

    @Test
    void tipHasEveryFieldOfTheContract() throws Exception {
        UUID id = tip(
                deviceId,
                "CAPTURED",
                Instant.parse("2026-10-10T09:05:27Z"),
                "Play some Oasis",
                "REQUEST",
                false,
                null,
                "Request: something by Oasis");

        one(owner, id.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.createdAt").value("2026-10-10T09:05:27Z"))
                .andExpect(jsonPath("$.amountMinor").value(500))
                .andExpect(jsonPath("$.currency").value("GBP"))
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.deviceSlug").value(deviceSlug))
                .andExpect(jsonPath("$.message").value("Play some Oasis"))
                .andExpect(jsonPath("$.messageKind").value("REQUEST"))
                .andExpect(jsonPath("$.reaction").value("REQUEST"))
                .andExpect(jsonPath("$.flagged").value(false))
                .andExpect(jsonPath("$.flagReason").value(nullValue()))
                .andExpect(jsonPath("$.ownerNote").value("Request: something by Oasis"));
        list(owner, "")
                .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.items[0].deviceSlug").value(deviceSlug))
                .andExpect(jsonPath("$.items[0].ownerNote").value("Request: something by Oasis"));
    }

    @Test
    void anotherOwnersTipIsNotFoundExactlyLikeAnUnknownTip() throws Exception {
        UUID othersTip = tip(otherDeviceId, "CAPTURED", DAY_1);
        UUID unknownTip = UUID.randomUUID();

        MockHttpServletResponse others =
                one(owner, othersTip.toString()).andReturn().getResponse();
        MockHttpServletResponse unknown =
                one(owner, unknownTip.toString()).andReturn().getResponse();

        assertThat(others.getStatus()).isEqualTo(404).isEqualTo(unknown.getStatus());
        assertThat(others.getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .isEqualTo(unknown.getContentType());
        Map<String, Object> othersBody = JsonPath.read(others.getContentAsString(), "$");
        Map<String, Object> unknownBody = JsonPath.read(unknown.getContentAsString(), "$");
        // instance repeats the requested path, so it differs by the id that was asked for and nothing else
        assertThat(othersBody.remove("instance")).isEqualTo("/api/owner/tips/" + othersTip);
        assertThat(unknownBody.remove("instance")).isEqualTo("/api/owner/tips/" + unknownTip);
        assertThat(othersBody).isEqualTo(unknownBody).containsEntry("code", "TIP_NOT_FOUND");
        one(otherOwner, othersTip.toString()).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "FAILED", "ABANDONED"})
    void tipsWhereNoMoneyMovedNeverAppear(String hiddenStatus) throws Exception {
        UUID hidden = tip(deviceId, hiddenStatus, DAY_2);
        UUID visible = tip(deviceId, "PENDING", DAY_1);

        assertThat(ids(list(owner, ""))).containsExactly(visible);
        list(owner, "").andExpect(jsonPath("$.totalItems").value(1));
        one(owner, hidden.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TIP_NOT_FOUND"));
    }

    @Test
    void everyVisibleStatusAppearsAndFiltersOnItsOwn() throws Exception {
        UUID captured = tip(deviceId, "CAPTURED", DAY_1);
        UUID pending = tip(deviceId, "PENDING", DAY_1.plusSeconds(1));
        UUID refunded = tip(deviceId, "REFUNDED", DAY_1.plusSeconds(2));
        UUID reversed = tip(deviceId, "REVERSED", DAY_1.plusSeconds(3));

        assertThat(ids(list(owner, ""))).containsExactly(reversed, refunded, pending, captured);
        assertThat(ids(list(owner, "status=CAPTURED"))).containsExactly(captured);
        assertThat(ids(list(owner, "status=PENDING"))).containsExactly(pending);
        assertThat(ids(list(owner, "status=REFUNDED"))).containsExactly(refunded);
        assertThat(ids(list(owner, "status=REVERSED"))).containsExactly(reversed);
    }

    @Test
    void kindFilterMatchesWhatTheModelReadEvenWhenFlagged() throws Exception {
        UUID warm = tip(deviceId, "CAPTURED", DAY_1, "Lovely music", "WARM", false, null, null);
        UUID playful = tip(deviceId, "CAPTURED", DAY_2, "Bet you cannot play faster", "PLAYFUL", false, null, null);
        UUID flaggedRequest =
                tip(deviceId, "CAPTURED", DAY_3, "Play Oasis or else", "REQUEST", true, "threatening", null);
        tip(deviceId, "CAPTURED", DAY_3.plusSeconds(1));

        assertThat(ids(list(owner, "kind=WARM"))).containsExactly(warm);
        assertThat(ids(list(owner, "kind=PLAYFUL"))).containsExactly(playful);
        assertThat(ids(list(owner, "kind=REQUEST"))).containsExactly(flaggedRequest);
        assertThat(ids(list(owner, "kind=PLAIN"))).isEmpty();
    }

    @Test
    void flaggedFilterWorksBothWays() throws Exception {
        UUID clean = tip(deviceId, "CAPTURED", DAY_1, "Thanks", "PLAIN", false, null, null);
        UUID flagged = tip(deviceId, "CAPTURED", DAY_2, "You are awful", "PLAIN", true, "insult", null);

        assertThat(ids(list(owner, "flagged=true"))).containsExactly(flagged);
        assertThat(ids(list(owner, "flagged=false"))).containsExactly(clean);
    }

    @Test
    void dateFiltersAreWholeUtcDaysAndInclusive() throws Exception {
        UUID lastMomentBefore = tip(deviceId, "CAPTURED", Instant.parse("2026-10-01T23:59:59.999999Z"));
        UUID firstMoment = tip(deviceId, "CAPTURED", Instant.parse("2026-10-02T00:00:00Z"));
        UUID lastMoment = tip(deviceId, "CAPTURED", Instant.parse("2026-10-02T23:59:59.999999Z"));
        UUID firstMomentAfter = tip(deviceId, "CAPTURED", Instant.parse("2026-10-03T00:00:00Z"));

        assertThat(ids(list(owner, "from=2026-10-02&to=2026-10-02"))).containsExactly(lastMoment, firstMoment);
        assertThat(ids(list(owner, "from=2026-10-02"))).containsExactly(firstMomentAfter, lastMoment, firstMoment);
        assertThat(ids(list(owner, "to=2026-10-02"))).containsExactly(lastMoment, firstMoment, lastMomentBefore);
    }

    @Test
    void filtersCombine() throws Exception {
        UUID flaggedWarmDay1 = tip(deviceId, "CAPTURED", DAY_1, "Nice but rude", "WARM", true, "insult", null);
        UUID cleanWarmDay2 = tip(deviceId, "CAPTURED", DAY_2, "Lovely", "WARM", false, null, null);
        UUID refundedWarmDay3 = tip(deviceId, "REFUNDED", DAY_3, "Great set", "WARM", false, null, null);
        tip(deviceId, "CAPTURED", DAY_3, "Ha ha", "PLAYFUL", false, null, null);

        assertThat(ids(list(owner, "kind=WARM&flagged=false"))).containsExactly(refundedWarmDay3, cleanWarmDay2);
        assertThat(ids(list(owner, "kind=WARM&flagged=true"))).containsExactly(flaggedWarmDay1);
        assertThat(ids(list(owner, "status=CAPTURED&from=2026-10-02")))
                .hasSize(2)
                .doesNotContain(flaggedWarmDay1);
        assertThat(ids(list(owner, "status=REFUNDED&kind=WARM&to=2026-10-03"))).containsExactly(refundedWarmDay3);
    }

    @Test
    void pagesWalkThroughTheTipsAndReportTheTotal() throws Exception {
        UUID t1 = tip(deviceId, "CAPTURED", DAY_1);
        UUID t2 = tip(deviceId, "CAPTURED", DAY_1.plusSeconds(1));
        UUID t3 = tip(deviceId, "CAPTURED", DAY_1.plusSeconds(2));
        UUID t4 = tip(deviceId, "CAPTURED", DAY_1.plusSeconds(3));
        UUID t5 = tip(deviceId, "CAPTURED", DAY_1.plusSeconds(4));

        assertThat(ids(list(owner, "size=2"))).containsExactly(t5, t4);
        assertThat(ids(list(owner, "page=1&size=2"))).containsExactly(t3, t2);
        assertThat(ids(list(owner, "page=2&size=2"))).containsExactly(t1);
        list(owner, "page=1&size=2")
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(5));
        list(owner, "page=9&size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(9))
                .andExpect(jsonPath("$.totalItems").value(5));
        list(owner, "size=50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void sameTimeTipsKeepAStableOrderAcrossPages() throws Exception {
        for (int i = 0; i < 6; i++) {
            tip(deviceId, "CAPTURED", DAY_1);
        }

        List<UUID> all = ids(list(owner, ""));
        List<UUID> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            paged.addAll(ids(list(owner, "size=2&page=" + page)));
        }

        assertThat(paged).hasSize(6).isEqualTo(all);
    }

    @Test
    void flaggedTipHidesTheMessageAndShowsWhy() throws Exception {
        UUID flagged = tip(
                deviceId,
                "CAPTURED",
                DAY_1,
                "You are useless",
                "REQUEST",
                true,
                "abusive language",
                "Request: should never show");
        UUID clean = tip(deviceId, "CAPTURED", DAY_2, "Thank you", "WARM", false, null, null);

        one(owner, flagged.toString())
                .andExpect(jsonPath("$.flagged").value(true))
                .andExpect(jsonPath("$.message").value(nullValue()))
                .andExpect(jsonPath("$.ownerNote").value(nullValue()))
                .andExpect(jsonPath("$.flagReason").value("abusive language"))
                .andExpect(jsonPath("$.messageKind").value("REQUEST"))
                .andExpect(jsonPath("$.reaction").value("PLAIN"));
        list(owner, "flagged=true")
                .andExpect(jsonPath("$.items[0].message").value(nullValue()))
                .andExpect(jsonPath("$.items[0].flagReason").value("abusive language"));
        for (ResultActions response :
                List.of(one(owner, flagged.toString()), list(owner, ""), list(owner, "flagged=true"))) {
            assertThat(response.andReturn().getResponse().getContentAsString())
                    .contains("abusive language")
                    .doesNotContain("You are useless")
                    .doesNotContain("should never show");
        }
        one(owner, clean.toString())
                .andExpect(jsonPath("$.message").value("Thank you"))
                .andExpect(jsonPath("$.flagReason").value(nullValue()));
    }

    @Test
    void notSignedInIs401ProblemDetails() throws Exception {
        UUID id = tip(deviceId, "CAPTURED", DAY_1);

        for (MockHttpServletRequestBuilder request : List.of(get("/api/owner/tips"), get("/api/owner/tips/{id}", id))) {
            mvc.perform(request.accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "status=CREATED | status",
                "status=captured | status",
                "status=NOPE | status",
                "kind=ANGRY | kind",
                "flagged=yes | flagged",
                "flagged=1 | flagged",
                "size=500 | size",
                "size=51 | size",
                "size=0 | size",
                "size=abc | size",
                "page=-1 | page",
                "page=1.5 | page",
                "page=99999999999 | page",
                "from=2026-13-01 | from",
                "from=2026-02-30 | from",
                "to=yesterday | to",
                "to=10/10/2026 | to",
                "from=2026-10-03&to=2026-10-02 | from"
            })
    void badParameterIs400NamingIt(String query, String parameter) throws Exception {
        list(owner, query)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.detail").value(containsString("'" + parameter + "'")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-2-3-4-5", "123"})
    void malformedTipIdIs400(String id) throws Exception {
        one(owner, id)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.detail").value(containsString("'id'")));
    }

    @Test
    void blankParametersCountAsAbsent() throws Exception {
        UUID id = tip(deviceId, "CAPTURED", DAY_1);

        assertThat(ids(list(owner, "page=&size=&status=&kind=&flagged=&from=&to=")))
                .containsExactly(id);
    }

    private ResultActions list(OwnerPrincipal as, String query) throws Exception {
        return mvc.perform(get("/api/owner/tips?" + query).with(user(as)));
    }

    private ResultActions one(OwnerPrincipal as, String id) throws Exception {
        return mvc.perform(get("/api/owner/tips/{id}", id).with(user(as)));
    }

    private static List<UUID> ids(ResultActions result) throws Exception {
        List<String> ids = JsonPath.read(
                result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.items[*].id");
        return ids.stream().map(UUID::fromString).toList();
    }

    private static OwnerPrincipal principal(UUID ownerId) {
        return new OwnerPrincipal(ownerId, TestData.uniqueEmail(), "Owner", null);
    }

    /** A tip without a message, as the AI leaves it: no kind, reaction PLAIN. */
    private UUID tip(UUID device, String status, Instant createdAt) {
        return tip(device, status, createdAt, null, null, false, null, null);
    }

    private UUID tip(
            UUID device,
            String status,
            Instant createdAt,
            String message,
            String kind,
            boolean flagged,
            String flagReason,
            String ownerNote) {
        UUID id = UUID.randomUUID();
        String reaction = flagged || kind == null ? "PLAIN" : kind;
        jdbc.sql("""
                        INSERT INTO tip (id, device_id, amount_minor, currency, message, status, paypal_order_id,
                                         message_kind, reaction, flagged, flag_reason, owner_note, ai_status, created_at)
                        VALUES (?, ?, 500, 'GBP', ?, ?, ?, ?, ?, ?, ?, ?, 'DONE', ?)""")
                .params(
                        id,
                        device,
                        message,
                        status,
                        TestData.uniqueId("ORDER"),
                        kind,
                        reaction,
                        flagged,
                        flagReason,
                        ownerNote,
                        createdAt.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }
}
