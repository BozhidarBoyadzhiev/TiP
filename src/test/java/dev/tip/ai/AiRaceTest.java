package dev.tip.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tip.AnthropicStubs;
import dev.tip.IntegrationTest;
import dev.tip.ReactedEvents;
import dev.tip.TestData;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The capture listener and the recovery job work on the same tip at the same time. Random stub delays
 * vary who claims first. The claim in PostgreSQL must allow exactly one Claude call and one event.
 */
class AiRaceTest extends IntegrationTest {

    private static final int ROUNDS = 20;
    private static final int MAX_JITTER_MILLIS = 40;

    @Autowired
    TestData data;

    @Autowired
    ReactedEvents reacted;

    @Autowired
    TipReactionWorker worker;

    @Autowired
    AiRecoveryJob recoveryJob;

    @Test
    void listenerAndRecoveryJobTogetherCallClaudeOnceAndReactOnce() throws Exception {
        UUID deviceId = data.device();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < ROUNDS; round++) {
                String message = TestData.uniqueMessage("Race round " + round);
                UUID tipId = data.capturedTip(deviceId, message);
                AnthropicStubs.replyWithJitterFor(
                        ANTHROPIC, message, AnthropicStubs.classification("POSITIVE", false, null), MAX_JITTER_MILLIS);

                CountDownLatch start = new CountDownLatch(1);
                Future<?> listener = pool.submit(() -> {
                    start.await();
                    worker.process(tipId);
                    return null;
                });
                Future<?> recovery = pool.submit(() -> {
                    start.await();
                    recoveryJob.recover();
                    return null;
                });
                start.countDown();
                listener.get(30, TimeUnit.SECONDS);
                recovery.get(30, TimeUnit.SECONDS);

                assertThat(data.aiStatus(tipId))
                        .as("ai status in round %d", round)
                        .isEqualTo("DONE");
                assertThat(AnthropicStubs.requestsFor(ANTHROPIC, message))
                        .as("Claude calls in round %d", round)
                        .hasSize(1);
                await().atMost(Duration.ofSeconds(5))
                        .until(() -> !reacted.forTip(tipId).isEmpty());
                assertThat(reacted.forTip(tipId))
                        .as("events in round %d", round)
                        .hasSize(1);
            }
        }
    }
}
