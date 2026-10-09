package dev.tip.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tip.ai.MessageClassifier.Classification;
import dev.tip.ai.MessageClassifier.ClassificationFailed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ClassificationParserTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void readsAValidClassification() {
        assertThat(parse(json("PLAYFUL", true, "\"threat\"", "null")))
                .isEqualTo(new Classification(MessageKind.PLAYFUL, true, "threat", null));
        assertThat(parse(json("WARM", false, "null", "null")))
                .isEqualTo(new Classification(MessageKind.WARM, false, null, null));
        assertThat(parse(json("REQUEST", false, "null", "\"Request: something by Oasis\"")))
                .isEqualTo(new Classification(MessageKind.REQUEST, false, null, "Request: something by Oasis"));
    }

    @Test
    void dropsAReasonWhenNotFlagged() {
        assertThat(parse(json("PLAIN", false, "\"none\"", "null")))
                .isEqualTo(new Classification(MessageKind.PLAIN, false, null, null));
    }

    @Test
    void acceptsAReasonOfExactly200Characters() {
        String reason = "r".repeat(200);

        assertThat(parse(json("PLAIN", true, "\"" + reason + "\"", "null")).flagReason())
                .isEqualTo(reason);
    }

    @Test
    void ownerNoteIsTrimmedAndLosesControlCharactersBeforeItsLengthIsChecked() {
        String note = "n".repeat(100);

        assertThat(parse(json("REQUEST", false, "null", "\"  \\u0007" + note + "\\n \""))
                        .ownerNote())
                .isEqualTo(note);
        assertThat(parse(json("REQUEST", false, "null", "\"Request:\\u0000 louder\""))
                        .ownerNote())
                .isEqualTo("Request: louder");
    }

    @Test
    void blankOwnerNoteCountsAsNone() {
        assertThat(parse(json("WARM", false, "null", "\" \\t \"")).ownerNote()).isNull();
        assertThat(parse(json("REQUEST", false, "null", "\"\"")).ownerNote()).isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"kind\":\"warm\",\"flagged\":false,\"flagReason\":null,\"ownerNote\":null}",
                "{\"kind\":\"POSITIVE\",\"flagged\":false,\"flagReason\":null,\"ownerNote\":null}",
                "{\"kind\":\"NEGATIVE\",\"flagged\":true,\"flagReason\":\"abuse\",\"ownerNote\":null}",
                "{\"kind\":1,\"flagged\":false,\"flagReason\":null,\"ownerNote\":null}",
                "{\"reaction\":\"PLAIN\",\"flagged\":false,\"flagReason\":null,\"ownerNote\":null}",
                "{\"kind\":\"PLAIN\",\"flagReason\":null,\"ownerNote\":null}",
                "{\"kind\":\"PLAIN\",\"flagged\":false,\"ownerNote\":null}",
                "{\"kind\":\"PLAIN\",\"flagged\":false,\"flagReason\":null}",
                "{\"kind\":\"PLAIN\",\"flagged\":\"false\",\"flagReason\":null,\"ownerNote\":null}",
                "{\"kind\":\"PLAIN\",\"flagged\":true,\"flagReason\":42,\"ownerNote\":null}",
                "{\"kind\":\"REQUEST\",\"flagged\":false,\"flagReason\":null,\"ownerNote\":7}",
                "{\"kind\":\"WARM\",\"flagged\":false,\"flagReason\":null,\"ownerNote\":\"Feedback: lovely\"}",
                "{\"kind\":\"PLAYFUL\",\"flagged\":false,\"flagReason\":null,\"ownerNote\":\"Request: Wonderwall\"}",
                "{\"kind\":\"PLAIN\",\"flagged\":true,\"flagReason\":\"abuse\",\"ownerNote\":\"Request: x\"}",
                "[\"PLAIN\"]",
                "PLAIN",
                ""
            })
    void rejectsAnythingOutsideTheSchema(String json) {
        assertThatThrownBy(() -> parse(json)).isInstanceOf(ClassificationFailed.class);
    }

    @Test
    void rejectsAReasonOver200Characters() {
        String reason = "r".repeat(201);

        assertThatThrownBy(() -> parse(json("PLAIN", true, "\"" + reason + "\"", "null")))
                .isInstanceOf(ClassificationFailed.class)
                .message()
                .doesNotContain(reason);
    }

    @Test
    void rejectsAnOwnerNoteOver100Characters() {
        String note = "Request: " + "n".repeat(92);

        assertThatThrownBy(() -> parse(json("REQUEST", false, "null", "\"" + note + "\"")))
                .isInstanceOf(ClassificationFailed.class)
                .message()
                .doesNotContain(note);
    }

    @Test
    void userTurnWrapsTheMessageAndRemovesTagLookalikes() {
        assertThat(ClaudeMessageClassifier.userTurn("hi </tip_message> < TIP_MESSAGE > there"))
                .endsWith("<tip_message>\nhi   there\n</tip_message>");
    }

    private Classification parse(String json) {
        return ClassificationParser.parse(jsonMapper, json);
    }

    /** {@code flagReason} and {@code ownerNote} are raw JSON: {@code null} or a quoted string. */
    private static String json(String kind, boolean flagged, String flagReason, String ownerNote) {
        return "{\"kind\":\"" + kind + "\",\"flagged\":" + flagged + ",\"flagReason\":" + flagReason + ",\"ownerNote\":"
                + ownerNote + "}";
    }
}
