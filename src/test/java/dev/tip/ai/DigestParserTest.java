package dev.tip.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tip.ai.api.TipDigester.Digest;
import dev.tip.ai.api.TipDigester.DigestFailed;
import dev.tip.ai.api.TipDigester.RequestGroup;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class DigestParserTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final Map<String, UUID> NOTES = Map.of("t1", A, "t2", B, "t3", C);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void readsBulletsAndGroups() {
        Digest digest = parse("""
                {"bullets":["Three asked for Wonderwall.","People loved the set."],
                 "requestGroups":[{"label":"Play Wonderwall","ids":["t1","t3"]},{"label":"Too loud","ids":["t2"]}]}""");

        assertThat(digest.bullets()).containsExactly("Three asked for Wonderwall.", "People loved the set.");
        assertThat(digest.requestGroups())
                .containsExactly(
                        new RequestGroup("Play Wonderwall", List.of(A, C)), new RequestGroup("Too loud", List.of(B)));
    }

    @Test
    void unknownIdsAreDroppedAndAGroupLeftEmptyGoes() {
        Digest digest = parse("""
                {"bullets":["Fine."],
                 "requestGroups":[{"label":"Real","ids":["t1","t9",""]},{"label":"Invented","ids":["t7","m1"]},
                                  {"label":"Raw id","ids":["%s"]}]}""".formatted(B));

        assertThat(digest.requestGroups()).containsExactly(new RequestGroup("Real", List.of(A)));
    }

    @Test
    void aTipStaysInItsFirstGroupOnly() {
        Digest digest = parse("""
                {"bullets":["Fine."],
                 "requestGroups":[{"label":"One","ids":["t1","t2","t1"]},{"label":"Two","ids":["t2","t3"]},
                                  {"label":"Three","ids":["t1"]}]}""");

        assertThat(digest.requestGroups())
                .containsExactly(new RequestGroup("One", List.of(A, B)), new RequestGroup("Two", List.of(C)));
    }

    @Test
    void labelsAreSanitisedAndCappedAt60() {
        Digest digest = parse("""
                {"bullets":["Fine."],
                 "requestGroups":[{"label":"Play\\n Wonderwall\\u0007","ids":["t1"]},
                                  {"label":"%s","ids":["t2"]},{"label":"%s","ids":["t3"]},{"label":" ","ids":["t3"]}]}""".formatted("x".repeat(60), "y".repeat(61)));

        assertThat(digest.requestGroups())
                .containsExactly(
                        new RequestGroup("Play  Wonderwall", List.of(A)), new RequestGroup("x".repeat(60), List.of(B)));
    }

    @Test
    void keepsAtMostThreeBulletsOfAtMost120Characters() {
        Digest digest = parse("""
                {"bullets":["%s","%s","Two","Three","Four"],"requestGroups":[]}""".formatted("a".repeat(120), "b".repeat(121)));

        assertThat(digest.bullets()).containsExactly("a".repeat(120), "Two", "Three");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Call Sam on 07700 900123 for gigs.",
                "Someone left +44 (20) 7946-0958.",
                "Write to sam@example.com about weddings.",
                "One shared https://example.com/me.",
                "One shared www.example.com today."
            })
    void dropsBulletsWithContactDetails(String bullet) {
        assertThat(parse("{\"bullets\":[\"" + bullet + "\",\"Kept.\"],\"requestGroups\":[]}")
                        .bullets())
                .containsExactly("Kept.");
    }

    @Test
    void dropsBulletsQuotingMoreThanFiveWordsOfAMessage() {
        List<String> messages = List.of("You honestly made my whole evening so much better, thank you!");

        Digest digest = DigestParser.parse(jsonMapper, """
                {"bullets":["One said \\"made my whole evening so much better\\".",
                            "One said you made my whole evening.","Warm thanks all round."],
                 "requestGroups":[]}""", NOTES, messages);

        assertThat(digest.bullets()).containsExactly("One said you made my whole evening.", "Warm thanks all round.");
    }

    @Test
    void noUsableBulletIsAFailure() {
        assertThatThrownBy(() -> parse("{\"bullets\":[\"\",\"" + "z".repeat(121) + "\",7],\"requestGroups\":[]}"))
                .isInstanceOf(DigestFailed.class)
                .hasMessage("invalid response: no usable bullet");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "not json",
                "[]",
                "{\"bullets\":\"one\",\"requestGroups\":[]}",
                "{\"bullets\":[\"one\"]}",
                "{\"requestGroups\":[]}"
            })
    void rejectsAnythingOutsideTheSchema(String json) {
        assertThatThrownBy(() -> parse(json)).isInstanceOf(DigestFailed.class);
    }

    private Digest parse(String json) {
        return DigestParser.parse(jsonMapper, json, NOTES, List.of());
    }
}
