package dev.tip.tipping;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import dev.tip.PayPalStubs;
import dev.tip.TestData;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class CreateTipTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestData data;

    String slug;
    UUID deviceId;

    @BeforeEach
    void device() {
        slug = TestData.uniqueSlug();
        deviceId = data.device(slug, "Busker Panda");
    }

    @Test
    void validTipIsStoredAsCreatedWithItsOrderId() throws Exception {
        PayPalStubs.createOrder(PAYPAL, "ORDER-OK");

        createTip(slug, "{\"amountMinor\":250,\"currency\":\"GBP\",\"message\":\"  Great\\nset!\\u0007 \"}", randomIp())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipId").isNotEmpty())
                .andExpect(jsonPath("$.paypalOrderId").value("ORDER-OK"));

        List<Map<String, Object>> tips = data.tipsOfDevice(deviceId);
        assertThat(tips).hasSize(1);
        assertThat(tips.getFirst())
                .containsEntry("status", "CREATED")
                .containsEntry("paypal_order_id", "ORDER-OK")
                .containsEntry("amount_minor", 250L)
                .containsEntry("currency", "GBP")
                .containsEntry("message", "Great set!")
                .containsEntry("ai_status", "PENDING");
    }

    @Test
    void emptyMessageIsStoredAsNull() throws Exception {
        PayPalStubs.createOrder(PAYPAL, "ORDER-EMPTY");

        createTip(slug, "{\"amountMinor\":100,\"currency\":\"GBP\",\"message\":\"   \"}", randomIp())
                .andExpect(status().isCreated());

        assertThat(data.tipsOfDevice(deviceId).getFirst()).containsEntry("message", null);
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        String ip = randomIp();
        createTip(slug, "{\"amountMinor\":99,\"currency\":\"GBP\"}", ip)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AMOUNT_OUT_OF_RANGE"));
        createTip(slug, "{\"amountMinor\":10001,\"currency\":\"GBP\"}", ip)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AMOUNT_OUT_OF_RANGE"));
        createTip(slug, "{\"amountMinor\":200,\"currency\":\"EUR\"}", ip)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENCY_NOT_SUPPORTED"));
        createTip(slug, "{\"amountMinor\":200,\"currency\":\"GBP\",\"message\":\"" + "a".repeat(281) + "\"}", ip)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        createTip(slug, "{\"currency\":\"GBP\"}", ip)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        createTip(slug, "not json", ip).andExpect(status().isBadRequest());

        assertThat(data.tipsOfDevice(deviceId)).isEmpty();
        PAYPAL.verify(0, postRequestedFor(urlPathEqualTo(PayPalStubs.CREATE_ORDER_PATH)));
    }

    @Test
    void messageOfExactly280CharactersIsAccepted() throws Exception {
        PayPalStubs.createOrder(PAYPAL, "ORDER-280");

        createTip(
                        slug,
                        "{\"amountMinor\":200,\"currency\":\"GBP\",\"message\":\"" + "a".repeat(280) + "\"}",
                        randomIp())
                .andExpect(status().isCreated());
    }

    @Test
    void unknownDeviceIs404() throws Exception {
        createTip("no-such-panda", "{\"amountMinor\":200,\"currency\":\"GBP\"}", randomIp())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
    }

    @Test
    void payPalFailureMarksTheTipFailedAndReturns502() throws Exception {
        PayPalStubs.createOrderFails(PAYPAL, 500);

        createTip(slug, "{\"amountMinor\":200,\"currency\":\"GBP\"}", randomIp())
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PAYPAL_ORDER_FAILED"));

        List<Map<String, Object>> tips = data.tipsOfDevice(deviceId);
        assertThat(tips).hasSize(1);
        assertThat(tips.getFirst()).containsEntry("status", "FAILED").containsEntry("paypal_order_id", null);
    }

    @Test
    void eleventhTipInAMinuteFromOneIpIsRateLimited() throws Exception {
        String ip = randomIp();
        for (int i = 0; i < 10; i++) {
            PayPalStubs.createOrder(PAYPAL, "ORDER-RATE-" + i);
            createTip(slug, "{\"amountMinor\":200,\"currency\":\"GBP\"}", ip).andExpect(status().isCreated());
        }

        createTip(slug, "{\"amountMinor\":200,\"currency\":\"GBP\"}", ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        PayPalStubs.createOrder(PAYPAL, "ORDER-OTHER-IP");
        createTip(slug, "{\"amountMinor\":200,\"currency\":\"GBP\"}", randomIp())
                .andExpect(status().isCreated());
    }

    private ResultActions createTip(String slug, String body, String ip) throws Exception {
        return mvc.perform(post("/api/public/devices/{slug}/tips", slug)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                }));
    }

    static String randomIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }
}
