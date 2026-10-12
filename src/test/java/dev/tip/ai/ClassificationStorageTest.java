package dev.tip.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.AnthropicStubs;
import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.ReactedEvents;
import dev.tip.TestData;
import dev.tip.ai.api.Reaction;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** What the classification stores besides the kind: language, translation, song and its key. */
class ClassificationStorageTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestData data;

    @Autowired
    ReactedEvents reacted;

    UUID deviceId;

    @BeforeEach
    void device() {
        deviceId = data.device();
    }

    @Test
    void storesLanguageTranslationSongAndTheKeyComputedInJava() throws Exception {
        String message = TestData.uniqueMessage("Puedes tocar The Wonderwall");
        Map<String, Object> row = captureAndClassify(
                message,
                AnthropicStubs.classification(
                        "REQUEST",
                        false,
                        null,
                        "Request: Wonderwall",
                        "es",
                        "Can you play The Wonderwall?",
                        "The Wónderwall!"));

        assertThat(row)
                .containsEntry("message_kind", "REQUEST")
                .containsEntry("owner_note", "Request: Wonderwall")
                .containsEntry("message_language", "es")
                .containsEntry("message_english", "Can you play The Wonderwall?")
                .containsEntry("requested_song", "The Wónderwall!")
                .containsEntry("requested_song_key", "wonderwall");
    }

    @Test
    void aFlaggedTipKeepsItsLanguageTranslationAndSongForTheOwnerAndStillReactsPlainOnce() throws Exception {
        String message = TestData.uniqueMessage("Joue Wonderwall ou gare a toi");
        UUID[] tip = new UUID[1];
        Map<String, Object> row = captureAndClassify(
                message,
                AnthropicStubs.classification(
                        "REQUEST", true, "threat", null, "fr", "Play Wonderwall or watch out", "Wonderwall"),
                tip);

        assertThat(row)
                .containsEntry("flagged", true)
                .containsEntry("reaction", "PLAIN")
                .containsEntry("message_language", "fr")
                .containsEntry("message_english", "Play Wonderwall or watch out")
                .containsEntry("requested_song", "Wonderwall")
                .containsEntry("requested_song_key", "wonderwall")
                .containsEntry("owner_verdict", null);
        assertThat(reacted.forTip(tip[0])).singleElement().satisfies(e -> {
            assertThat(e.reaction()).isEqualTo(Reaction.PLAIN);
            assertThat(e.repeats()).isOne();
        });
    }

    @Test
    void anUnknownLanguageIsDroppedWithItsTranslationAndTheKindStays() throws Exception {
        String message = TestData.uniqueMessage("Blorp zib thanks");
        Map<String, Object> row = captureAndClassify(
                message, AnthropicStubs.classification("WARM", false, null, null, "xx", "Thanks", null));

        assertThat(row)
                .containsEntry("message_kind", "WARM")
                .containsEntry("reaction", "WARM")
                .containsEntry("message_language", null)
                .containsEntry("message_english", null);
    }

    private Map<String, Object> captureAndClassify(String message, String modelText, UUID... tipOut) throws Exception {
        String orderId = TestData.uniqueId("ORDER");
        UUID tipId = data.tipWithMessage(deviceId, orderId, message);
        if (tipOut.length > 0) {
            tipOut[0] = tipId;
        }
        AnthropicStubs.replyFor(ANTHROPIC, message, modelText);
        PayPalStubs.captureCompleted(PAYPAL, orderId, TestData.uniqueId("CAPTURE"));

        mvc.perform(post("/api/public/tips/{tipId}/capture", tipId)).andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(30)).until(() -> "DONE".equals(data.aiStatus(tipId)));
        await().atMost(Duration.ofSeconds(30))
                .until(() -> !reacted.forTip(tipId).isEmpty());
        return data.tipRow(tipId);
    }
}
