package dev.tip.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import java.time.Clock;
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
            throw new ModelUnavailable("resting after a spend limit or authentication error");
        }
        try {
            Message message = client.messages().create(params);
            count(false);
            breaker.succeeded();
            return message;
        } catch (AnthropicServiceException e) {
            count(true);
            breaker.failed(e.statusCode(), e.getMessage() + " " + e.body());
            throw new ModelUnavailable("api status " + e.statusCode());
        } catch (RuntimeException e) {
            count(true);
            breaker.failedWithoutAnswer();
            throw new ModelUnavailable(e.getClass().getSimpleName());
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
