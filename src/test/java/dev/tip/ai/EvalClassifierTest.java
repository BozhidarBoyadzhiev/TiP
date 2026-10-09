package dev.tip.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.tip.AnthropicStubs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The seam the aiEval tool uses runs the production prompt, schema and parser. */
class EvalClassifierTest {

    private static final WireMockServer CLAUDE =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeAll
    static void start() {
        CLAUDE.start();
    }

    @AfterAll
    static void stop() {
        CLAUDE.stop();
    }

    @BeforeEach
    void reset() {
        CLAUDE.resetAll();
    }

    @Test
    void withoutAnApiKeyThereIsNothingToRun() {
        assertThat(EvalClassifier.fromEnvironment(environment(""))).isEmpty();
    }

    @Test
    void sendsTheProductionPromptAndSchemaWithTheConfiguredModel() {
        stub(AnthropicStubs.classification("WARM", false, null));
        EvalClassifier classifier =
                EvalClassifier.fromEnvironment(environment("eval-key")).orElseThrow();

        EvalClassifier.Verdict verdict = classifier.classify("You made my day");

        assertThat(verdict).isEqualTo(new EvalClassifier.Verdict("WARM", false, null, null));
        assertThat(classifier.model()).isEqualTo("claude-eval-model");
        JsonNode body = JsonMapper.builder()
                .build()
                .readTree(CLAUDE.findAll(postRequestedFor(urlPathEqualTo(AnthropicStubs.MESSAGES_PATH)))
                        .getFirst()
                        .getBodyAsString());
        JsonNode system = body.path("system");
        assertThat(
                        system.isString()
                                ? system.asString()
                                : system.get(0).path("text").asString())
                .isEqualTo(ClaudeMessageClassifier.SYSTEM_PROMPT);
        assertThat(body.path("model").asString()).isEqualTo("claude-eval-model");
        assertThat(body.path("output_config").path("format").path("type").asString())
                .isEqualTo("json_schema");
    }

    @Test
    void flaggedRequestKeepsItsKindButTheOwnerSeesNoNote() {
        stub(AnthropicStubs.classification("REQUEST", true, "threat", "Request: something by Oasis"));

        EvalClassifier.Verdict verdict = EvalClassifier.fromEnvironment(environment("eval-key"))
                .orElseThrow()
                .classify("Play Oasis or I will find you");

        assertThat(verdict).isEqualTo(new EvalClassifier.Verdict("REQUEST", true, null, null));
    }

    @Test
    void outputTheParserRejectsIsAnInvalidVerdict() {
        stub("{\"kind\":\"EXCITED\",\"flagged\":false,\"flagReason\":null,\"ownerNote\":null}");

        EvalClassifier.Verdict verdict = EvalClassifier.fromEnvironment(environment("eval-key"))
                .orElseThrow()
                .classify("Hi");

        assertThat(verdict.valid()).isFalse();
        assertThat(verdict.failure()).isEqualTo("invalid response: kind not allowed");
    }

    private static void stub(String modelText) {
        CLAUDE.stubFor(post(urlPathEqualTo(AnthropicStubs.MESSAGES_PATH)).willReturn(AnthropicStubs.reply(modelText)));
    }

    private static MockEnvironment environment(String apiKey) {
        return new MockEnvironment()
                .withProperty("tip.ai.enabled", "true")
                .withProperty("tip.ai.model", "claude-eval-model")
                .withProperty("tip.ai.api-key", apiKey)
                .withProperty("tip.ai.base-url", CLAUDE.baseUrl())
                .withProperty("tip.ai.timeout", "PT2S");
    }
}
