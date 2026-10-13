package dev.tip.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every model call of the app: skipped while the {@link AiBreaker} rests, otherwise made once (SDK retries are off)
 * and counted in {@code ai_usage}. A failure is reported to the breaker and thrown as {@link ModelUnavailable}.
 */
@Component
class GuardedModelCalls implements ModelCalls {

    private static final Logger log = LoggerFactory.getLogger(GuardedModelCalls.class);

    private final AiBreaker breaker;
    private final AiUsageRepository usage;
    private final Clock clock;

    GuardedModelCalls(AiBreaker breaker, AiUsageRepository usage, Clock clock) {
        this.breaker = breaker;
        this.usage = usage;
        this.clock = clock;
    }

    @Override
    public Message create(AnthropicClient client, MessageCreateParams params) {
        if (!breaker.tryCall()) {
            throw new ModelUnavailable("resting after the model API rejected a request");
        }
        try {
            Message message = client.messages().create(params);
            count(false);
            breaker.succeeded();
            return message;
        } catch (AnthropicServiceException e) {
            count(true);
            ApiError error = ApiError.of(e);
            breaker.failed(e.statusCode(), error.type(), error.message());
            throw new ModelUnavailable("api status " + e.statusCode());
        } catch (RuntimeException e) {
            count(true);
            breaker.failedWithoutAnswer();
            throw new ModelUnavailable(e.getClass().getSimpleName());
        }
    }

    /**
     * The {@code error.type} and {@code error.message} of the API's error body, null when absent. The body describes
     * the request, never repeats the prompt.
     */
    record ApiError(String type, String message) {

        static ApiError of(AnthropicServiceException e) {
            try {
                Map<?, ?> body = e.body().convert(Map.class);
                if (body != null && body.get("error") instanceof Map<?, ?> error) {
                    return new ApiError(text(error.get("type")), text(error.get("message")));
                }
            } catch (RuntimeException unreadable) {
                log.debug("Unreadable model API error body");
            }
            return new ApiError(e.errorType().map(Object::toString).orElse(null), null);
        }

        private static String text(Object value) {
            return value instanceof String s ? s : null;
        }
    }

    /** Counting never fails the call it counts. */
    private void count(boolean failed) {
        try {
            usage.record(clock.instant(), failed);
        } catch (RuntimeException e) {
            log.warn("Could not count a model call: {}", e.getClass().getSimpleName());
        }
    }
}
