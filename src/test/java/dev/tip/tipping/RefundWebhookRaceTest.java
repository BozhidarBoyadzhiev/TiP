package dev.tip.tipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.TestData;
import dev.tip.WebhookEvents;
import dev.tip.owners.api.OwnerPrincipal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The owner's refund response and the PAYMENT.CAPTURE.REFUNDED webhook arrive together. Random stub
 * delays let either reach the database first; the conditional update must make exactly one transition.
 */
class RefundWebhookRaceTest extends IntegrationTest {

    private static final int ROUNDS = 20;
    private static final int MAX_JITTER_MILLIS = 40;

    @Autowired
    MockMvc mvc;

    @Autowired
    TestData data;

    @MockitoSpyBean
    TipTransitions transitions;

    private final Map<UUID, AtomicInteger> refundTransitions = new ConcurrentHashMap<>();

    @BeforeEach
    void countWinningRefundTransitions() {
        doAnswer(invocation -> {
                    boolean changed = (boolean) invocation.callRealMethod();
                    if (changed) {
                        refundTransitions
                                .computeIfAbsent(invocation.getArgument(0), id -> new AtomicInteger())
                                .incrementAndGet();
                    }
                    return changed;
                })
                .when(transitions)
                .refund(any());
    }

    @Test
    void refundResponseAndWebhookTogetherMakeOneTransition() throws Exception {
        PayPalStubs.verifyWebhookWithJitter(PAYPAL, MAX_JITTER_MILLIS);
        UUID ownerId = data.owner();
        OwnerPrincipal owner = new OwnerPrincipal(ownerId, "owner@example.com", "Owner", null);
        UUID deviceId = data.device(ownerId, TestData.uniqueSlug(), "Race Panda");

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < ROUNDS; round++) {
                String captureId = TestData.uniqueId("CAPTURE");
                String refundId = TestData.uniqueId("REFUND");
                UUID tipId = data.tip(deviceId, "CAPTURED", TestData.uniqueId("ORDER"), Instant.now());
                data.setCaptureId(tipId, captureId);
                PayPalStubs.refundWithJitter(PAYPAL, captureId, refundId, "COMPLETED", MAX_JITTER_MILLIS);
                String webhookBody = WebhookEvents.refund(TestData.uniqueId("WH"), refundId, captureId, null);

                CountDownLatch start = new CountDownLatch(1);
                Future<MockHttpServletResponse> refund = pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/api/owner/tips/{tipId}/refund", tipId)
                                    .with(user(owner))
                                    .with(csrf()))
                            .andReturn()
                            .getResponse();
                });
                Future<MockHttpServletResponse> webhook = pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/webhooks/paypal")
                                    .headers(WebhookEvents.transmissionHeaders())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(webhookBody))
                            .andReturn()
                            .getResponse();
                });
                start.countDown();

                assertThat(refund.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
                assertThat(refund.get().getContentAsString()).contains("\"REFUNDED\"");
                assertThat(webhook.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
                assertThat(data.tipStatus(tipId)).isEqualTo("REFUNDED");
                assertThat(refundTransitions.get(tipId))
                        .as("transitions in round %d", round)
                        .hasValue(1);
            }
        }
    }
}
