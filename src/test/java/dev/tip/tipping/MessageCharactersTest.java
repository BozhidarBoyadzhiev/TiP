package dev.tip.tipping;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.tip.AnthropicStubs;
import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.TestData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every character a tipper types reaches the database and the model unchanged, over real HTTP: a Latin
 * accent, an accented capital, a CJK character and an emoji outside the Basic Multilingual Plane.
 */
class MessageCharactersTest extends IntegrationTest {

    private static final char REPLACEMENT = '\uFFFD';

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    TestData data;

    @Autowired
    JsonMapper jsonMapper;

    String slug;
    UUID deviceId;

    @BeforeEach
    void device() {
        slug = TestData.uniqueSlug();
        deviceId = data.device(slug, "Unicode Panda");
    }

    @Test
    void storedMessageAndTheModelsInputKeepEveryCharacter() throws Exception {
        String message = TestData.uniqueMessage("Danke schön, Élodie! 熊猫 🐼");
        assertThat(message.codePoints().filter(Character::isSupplementaryCodePoint))
                .as("the panda is a surrogate pair in Java")
                .hasSize(1);
        AnthropicStubs.replyFor(ANTHROPIC, message, AnthropicStubs.classification("WARM", false, null));

        UUID tipId = tipAndCapture(jsonString(message));

        assertThat(data.tipRow(tipId).get("message")).isEqualTo(message);
        LoggedRequest sent = onlyClaudeRequest(tipId);
        String body = sent.getBodyAsString();
        assertThat(body).doesNotContain(String.valueOf(REPLACEMENT));
        assertThat(userTurn(body))
                .endsWith("<tip_message>\n" + message + "\n</tip_message>")
                .doesNotContain(String.valueOf(REPLACEMENT));
        ANTHROPIC.verify(postRequestedFor(urlPathEqualTo(AnthropicStubs.MESSAGES_PATH))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing(message))));
    }

    @Test
    void aLoneSurrogateIsRemovedInsteadOfReachingTheDatabaseOrTheModel() throws Exception {
        String words = TestData.uniqueMessage("half a panda");
        // JSON may escape half of a surrogate pair on its own; Java then holds a lone surrogate.
        UUID tipId = tipAndCapture("\"" + words + " \\ud83d!\"");

        String stored = (String) data.tipRow(tipId).get("message");
        assertThat(stored).isEqualTo(words + " !");
        assertThat(userTurn(onlyClaudeRequest(tipId).getBodyAsString())).contains(words + " !\n</tip_message>");
    }

    private UUID tipAndCapture(String messageJson) throws Exception {
        String orderId = TestData.uniqueId("ORDER");
        PayPalStubs.createOrder(PAYPAL, orderId);
        PayPalStubs.captureCompleted(PAYPAL, orderId, TestData.uniqueId("CAPTURE"));
        String ip = TestData.uniquePublicIp();

        HttpResponse<String> created = http.send(
                HttpRequest.newBuilder(URI.create(url("/api/public/devices/" + slug + "/tips")))
                        .header("Content-Type", "application/json")
                        .header("X-Forwarded-For", ip)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(
                                ("{\"amountMinor\":200,\"currency\":\"GBP\",\"message\":" + messageJson + "}")
                                        .getBytes(StandardCharsets.UTF_8)))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(created.statusCode()).isEqualTo(201);
        UUID tipId = UUID.fromString(
                jsonMapper.readTree(created.body()).path("tipId").asString());

        HttpResponse<String> captured = http.send(
                HttpRequest.newBuilder(URI.create(url("/api/public/tips/" + tipId + "/capture")))
                        .header("X-Forwarded-For", ip)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(captured.statusCode()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(15)).until(() -> "DONE".equals(data.aiStatus(tipId)));
        assertThat(data.tipsOfDevice(deviceId)).hasSize(1);
        return tipId;
    }

    /** The single classification request of this test's device, found by the stored message. */
    private LoggedRequest onlyClaudeRequest(UUID tipId) {
        String stored = (String) data.tipRow(tipId).get("message");
        List<LoggedRequest> requests =
                ANTHROPIC.findAll(postRequestedFor(urlPathEqualTo(AnthropicStubs.MESSAGES_PATH))).stream()
                        .filter(request -> userTurn(request.getBodyAsString()).contains(stored))
                        .toList();
        assertThat(requests).hasSize(1);
        return requests.getFirst();
    }

    private String userTurn(String body) {
        JsonNode content = jsonMapper.readTree(body).path("messages").get(0).path("content");
        return content.isString()
                ? content.asString()
                : content.get(0).path("text").asString();
    }

    private String jsonString(String text) {
        return jsonMapper.writeValueAsString(text);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
