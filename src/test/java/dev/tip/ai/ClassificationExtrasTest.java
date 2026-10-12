package dev.tip.ai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.ai.MessageClassifier.Classification;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** The language, English text and song the parser takes from the classification, or drops. */
class ClassificationExtrasTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void readsLanguageTranslationAndSongOfAnUnflaggedForeignRequest() {
        Classification c = parse(
                "REQUEST",
                false,
                Map.of("language", "es", "englishText", "Can you play Despacito?", "song", "Despacito"));

        assertThat(c.language()).isEqualTo("es");
        assertThat(c.englishText()).isEqualTo("Can you play Despacito?");
        assertThat(c.song()).isEqualTo("Despacito");
        assertThat(c.kind()).isEqualTo(MessageKind.REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"xx", "qq", "EN", "Fr", "eng", "e", "", "f1", "zz"})
    void aCodeThatIsNotAKnownLowercaseTwoLetterLanguageIsAbsentAndTakesTheTranslationWithIt(String code) {
        Classification c = parse("WARM", false, Map.of("language", code, "englishText", "Thank you"));

        assertThat(c.language()).isNull();
        assertThat(c.englishText()).isNull();
        assertThat(c.kind()).isEqualTo(MessageKind.WARM);
    }

    @Test
    void aLanguageThatIsNotAStringIsAbsent() {
        assertThat(parseRaw("{\"kind\":\"WARM\",\"flagged\":false,\"flagReason\":null,\"ownerNote\":null,"
                                + "\"language\":7,\"englishText\":\"Hi\",\"song\":null}")
                        .language())
                .isNull();
    }

    @Test
    void noTranslationOnAnEnglishMessage() {
        Classification c = parse("WARM", false, Map.of("language", "en", "englishText", "Thanks a lot"));

        assertThat(c.language()).isEqualTo("en");
        assertThat(c.englishText()).isNull();
    }

    @Test
    void aFlaggedMessageKeepsItsFlagKindTranslationAndSongUnderTheSameRules() {
        Classification c = parse(
                "REQUEST", true, Map.of("language", "fr", "englishText", "You are useless", "song", "Wonderwall"));

        assertThat(c.flagged()).isTrue();
        assertThat(c.kind()).isEqualTo(MessageKind.REQUEST);
        assertThat(c.language()).isEqualTo("fr");
        assertThat(c.englishText()).isEqualTo("You are useless");
        assertThat(c.song()).isEqualTo("Wonderwall");

        Classification broken =
                parse("PLAYFUL", true, Map.of("language", "en", "englishText", "x".repeat(281), "song", "Wonderwall"));
        assertThat(broken.flagged()).isTrue();
        assertThat(broken.englishText()).as("English needs no translation").isNull();
        assertThat(broken.song()).as("a song only on a request").isNull();
        assertThat(parse("REQUEST", true, Map.of("language", "fr", "englishText", "x".repeat(281)))
                        .englishText())
                .as("the cap applies to a flagged message too")
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"WARM", "PLAYFUL", "PLAIN"})
    void noSongOnAnythingButARequest(String kind) {
        assertThat(parse(kind, false, Map.of("language", "en", "song", "Wonderwall"))
                        .song())
                .isNull();
    }

    @Test
    void overLongExtrasAreDroppedAndTheRestIsKept() {
        Classification atLimit = parse(
                "REQUEST", false, Map.of("language", "fr", "englishText", "e".repeat(280), "song", "s".repeat(80)));
        Classification over = parse(
                "REQUEST", false, Map.of("language", "fr", "englishText", "e".repeat(281), "song", "s".repeat(81)));

        assertThat(atLimit.englishText()).hasSize(280);
        assertThat(atLimit.song()).hasSize(80);
        assertThat(over.englishText()).isNull();
        assertThat(over.song()).isNull();
        assertThat(over.language()).isEqualTo("fr");
        assertThat(over.kind()).isEqualTo(MessageKind.REQUEST);
    }

    @Test
    void controlCharactersAreRemovedAndLineBreaksBecomeSpacesLikeATippersMessage() {
        Classification c = parse(
                "REQUEST",
                false,
                Map.of("language", "de", "englishText", "Can you\nplay\u0007 this‮?", "song", " \u0000Wonder\twall "));

        assertThat(c.englishText()).isEqualTo("Can you play this?");
        assertThat(c.song()).isEqualTo("Wonder wall");
    }

    @Test
    void blankOrWronglyTypedExtrasAreAbsentAndNeverFailTheClassification() {
        assertThat(parseRaw("{\"kind\":\"REQUEST\",\"flagged\":false,\"flagReason\":null,\"ownerNote\":null,"
                        + "\"language\":\"fr\",\"englishText\":[\"x\"],\"song\":\" \\t \"}"))
                .isEqualTo(new Classification(MessageKind.REQUEST, false, null, null, "fr", null, null));
    }

    @Test
    void missingExtrasLeaveTheClassificationAsBefore() {
        assertThat(parseRaw("{\"kind\":\"PLAYFUL\",\"flagged\":false,\"flagReason\":null,\"ownerNote\":null}"))
                .isEqualTo(new Classification(MessageKind.PLAYFUL, false, null, null));
    }

    private Classification parse(String kind, boolean flagged, Map<String, String> extras) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", kind);
        root.put("flagged", flagged);
        root.put("flagReason", flagged ? "insult" : null);
        root.put("ownerNote", null);
        root.putAll(extras);
        return parseRaw(jsonMapper.writeValueAsString(root));
    }

    private Classification parseRaw(String json) {
        return ClassificationParser.parse(jsonMapper, json);
    }
}
