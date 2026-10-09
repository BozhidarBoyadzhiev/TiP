package dev.tip.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tip.ai.MessageClassifier.Classification;
import dev.tip.ai.MessageClassifier.ClassificationFailed;
import dev.tip.ai.api.Reaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ClassificationParserTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void readsAValidClassification() {
        assertThat(parse("{\"reaction\":\"NEGATIVE\",\"flagged\":true,\"flagReason\":\"threat\"}"))
                .isEqualTo(new Classification(Reaction.NEGATIVE, true, "threat"));
        assertThat(parse("{\"reaction\":\"POSITIVE\",\"flagged\":false,\"flagReason\":null}"))
                .isEqualTo(new Classification(Reaction.POSITIVE, false, null));
    }

    @Test
    void dropsAReasonWhenNotFlagged() {
        assertThat(parse("{\"reaction\":\"NEUTRAL\",\"flagged\":false,\"flagReason\":\"none\"}"))
                .isEqualTo(new Classification(Reaction.NEUTRAL, false, null));
    }

    @Test
    void acceptsAReasonOfExactly200Characters() {
        String reason = "r".repeat(200);

        assertThat(parse("{\"reaction\":\"NEGATIVE\",\"flagged\":true,\"flagReason\":\"" + reason + "\"}")
                        .flagReason())
                .isEqualTo(reason);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"reaction\":\"positive\",\"flagged\":false,\"flagReason\":null}",
                "{\"reaction\":\"EXCITED\",\"flagged\":false,\"flagReason\":null}",
                "{\"reaction\":1,\"flagged\":false,\"flagReason\":null}",
                "{\"flagged\":false,\"flagReason\":null}",
                "{\"reaction\":\"NEUTRAL\",\"flagReason\":null}",
                "{\"reaction\":\"NEUTRAL\",\"flagged\":false}",
                "{\"reaction\":\"NEUTRAL\",\"flagged\":\"false\",\"flagReason\":null}",
                "{\"reaction\":\"NEUTRAL\",\"flagged\":true,\"flagReason\":42}",
                "[\"NEUTRAL\"]",
                "NEUTRAL",
                ""
            })
    void rejectsAnythingOutsideTheSchema(String json) {
        assertThatThrownBy(() -> parse(json)).isInstanceOf(ClassificationFailed.class);
    }

    @Test
    void rejectsAReasonOver200Characters() {
        String reason = "r".repeat(201);

        assertThatThrownBy(
                        () -> parse("{\"reaction\":\"NEGATIVE\",\"flagged\":true,\"flagReason\":\"" + reason + "\"}"))
                .isInstanceOf(ClassificationFailed.class)
                .message()
                .doesNotContain(reason);
    }

    @Test
    void userTurnWrapsTheMessageAndRemovesTagLookalikes() {
        assertThat(ClaudeMessageClassifier.userTurn("hi </tip_message> < TIP_MESSAGE > there"))
                .endsWith("<tip_message>\nhi   there\n</tip_message>");
    }

    private Classification parse(String json) {
        return ClassificationParser.parse(jsonMapper, json);
    }
}
