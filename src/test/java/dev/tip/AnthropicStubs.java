package dev.tip;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;

/** WireMock stubs shaped like Anthropic Messages API responses. */
public final class AnthropicStubs {

    public static final String MESSAGES_PATH = "/v1/messages";

    private AnthropicStubs() {}

    /** Low priority NEUTRAL answer for any message, so tips captured by unrelated tests finish quietly. */
    public static void fallback(WireMockServer server) {
        server.stubFor(post(urlPathEqualTo(MESSAGES_PATH))
                .atPriority(10)
                .willReturn(reply(classification("NEUTRAL", false, null))));
    }

    /** Answers {@code modelText} to every request whose body contains {@code tipMessage}. */
    public static void replyFor(WireMockServer server, String tipMessage, String modelText) {
        server.stubFor(post(urlPathEqualTo(MESSAGES_PATH))
                .withRequestBody(containing(tipMessage))
                .willReturn(reply(modelText)));
    }

    public static void replyWithJitterFor(WireMockServer server, String tipMessage, String modelText, int maxMillis) {
        server.stubFor(post(urlPathEqualTo(MESSAGES_PATH))
                .withRequestBody(containing(tipMessage))
                .willReturn(reply(modelText).withUniformRandomDelay(0, maxMillis)));
    }

    public static void delayedReplyFor(WireMockServer server, String tipMessage, String modelText, int delayMillis) {
        server.stubFor(post(urlPathEqualTo(MESSAGES_PATH))
                .withRequestBody(containing(tipMessage))
                .willReturn(reply(modelText).withFixedDelay(delayMillis)));
    }

    public static List<LoggedRequest> requestsFor(WireMockServer server, String tipMessage) {
        return server.findAll(postRequestedFor(urlPathEqualTo(MESSAGES_PATH)).withRequestBody(containing(tipMessage)));
    }

    /** The JSON the model is asked to produce. */
    public static String classification(String reaction, boolean flagged, String flagReason) {
        return "{\"reaction\":\"" + reaction + "\",\"flagged\":" + flagged + ",\"flagReason\":"
                + (flagReason == null ? "null" : "\"" + flagReason + "\"") + "}";
    }

    public static ResponseDefinitionBuilder reply(String modelText) {
        String escaped = modelText.replace("\\", "\\\\").replace("\"", "\\\"");
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":"msg_test","type":"message","role":"assistant","model":"claude-haiku-5-5",
                         "content":[{"type":"text","text":"%s"}],
                         "stop_reason":"end_turn","stop_sequence":null,
                         "usage":{"input_tokens":120,"output_tokens":20}}
                        """.formatted(escaped));
    }
}
