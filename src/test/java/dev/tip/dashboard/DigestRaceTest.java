package dev.tip.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.IntegrationTest;
import dev.tip.TestData;
import dev.tip.ai.api.TipDigester;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The digest job's claim and watermark under concurrency. A gated digester holds a call until the test lets
 * it go, so the order of events is set by the test, never by timing.
 */
@Import(DigestRaceTest.GateConfiguration.class)
class DigestRaceTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-12");
    private static final long WAIT_SECONDS = 30;

    @Autowired
    DigestJob job;

    @Autowired
    DigestRepository digests;

    @Autowired
    GatedDigester digester;

    @Autowired
    TestData data;

    @Autowired
    JdbcClient jdbc;

    UUID ownerId;
    UUID deviceId;

    @BeforeEach
    void ownerWithThreeMessages() {
        clock.pin(NOW);
        // Only this test's digest is stale, so every call the job makes is for it.
        jdbc.sql("UPDATE owner_digest SET stale = false").update();
        digester.reset();
        ownerId = data.owner();
        deviceId = data.device(ownerId, TestData.uniqueSlug(), "Race Panda");
        message("Lovely music", NOW.minusSeconds(3000));
        message("Thanks for the tunes", NOW.minusSeconds(2000));
        message("You made my day", NOW.minusSeconds(1000));
        digests.markStale(ownerId, TODAY);
    }

    @Test
    void aSecondRunnerWhileTheFirstHoldsTheClaimDoesNotCallTheModel() throws Exception {
        CountDownLatch release = digester.holdNextCall();
        CompletableFuture<Void> first = CompletableFuture.runAsync(job::run);
        digester.awaitCalls(1);

        job.run();
        assertThat(digester.calls()).isOne();

        release.countDown();
        first.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(digester.calls()).isOne();
        assertThat(digests.find(ownerId, TODAY).orElseThrow().bullets()).containsExactly("Read 3 messages.");
    }

    @Test
    void aSlowOldCallNeverOverwritesTheNewerDigest() throws Exception {
        CountDownLatch release = digester.holdNextCall();
        CompletableFuture<Void> old = CompletableFuture.runAsync(job::run);
        digester.awaitCalls(1);

        // The old claim has expired and a newer message arrived, so a second instance takes over.
        clock.advance(Duration.ofMinutes(3));
        message("The newest message", clock.instant());
        digests.markStale(ownerId, TODAY);
        job.run();
        assertThat(digests.find(ownerId, TODAY).orElseThrow().bullets()).containsExactly("Read 4 messages.");

        release.countDown();
        old.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(digester.calls()).isEqualTo(2);
        DigestRepository.StoredDigest stored = digests.find(ownerId, TODAY).orElseThrow();
        assertThat(stored.bullets()).containsExactly("Read 4 messages.");
        assertThat(stored.messageCount()).isEqualTo(4);
    }

    private void message(String text, Instant at) {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("message", TestData.uniqueMessage(text));
        columns.put("message_kind", "WARM");
        columns.put("reaction", "WARM");
        columns.put("ai_status", "DONE");
        columns.put("reaction_at", at);
        columns.put("captured_at", at);
        columns.put("paypal_capture_id", TestData.uniqueId("CAPTURE"));
        data.tip(deviceId, "CAPTURED", at, columns);
    }

    /** Replaces the Claude digester in this context only. */
    @TestConfiguration(proxyBeanMethods = false)
    static class GateConfiguration {

        @Bean
        @Primary
        GatedDigester gatedDigester() {
            return new GatedDigester();
        }
    }

    /** Counts calls, answers how many messages it read, and can hold the next call until released. */
    static class GatedDigester implements TipDigester {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<CountDownLatch> hold = new AtomicReference<>();

        @Override
        public Digest digest(List<DigestMessage> messages, List<DigestNote> notes) {
            CountDownLatch release = hold.getAndSet(null);
            synchronized (calls) {
                calls.incrementAndGet();
                calls.notifyAll();
            }
            if (release != null) {
                try {
                    if (!release.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                        throw new DigestFailed("the test never released the held call");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new DigestFailed("interrupted");
                }
            }
            return new Digest(List.of("Read " + messages.size() + " messages."), List.of());
        }

        void reset() {
            calls.set(0);
            hold.set(null);
        }

        /** The next call blocks until the returned latch is counted down. */
        CountDownLatch holdNextCall() {
            CountDownLatch release = new CountDownLatch(1);
            hold.set(release);
            return release;
        }

        int calls() {
            return calls.get();
        }

        void awaitCalls(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            synchronized (calls) {
                while (calls.get() < count) {
                    long left = deadline - System.nanoTime();
                    assertThat(left)
                            .as("call %d within %d s", count, WAIT_SECONDS)
                            .isPositive();
                    calls.wait(TimeUnit.NANOSECONDS.toMillis(left) + 1);
                }
            }
        }
    }
}
