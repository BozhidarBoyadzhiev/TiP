package dev.tip.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

/** How the classifier and the digester call the model. The app's is {@link GuardedModelCalls}. */
interface ModelCalls {

    /** @throws ModelUnavailable when the call failed or was not made */
    Message create(AnthropicClient client, MessageCreateParams params);

    /** Straight through, without the breaker or counting: for the eval tool, which runs outside the app. */
    static ModelCalls direct() {
        return (client, params) -> {
            try {
                return client.messages().create(params);
            } catch (AnthropicServiceException e) {
                throw new ModelUnavailable("api status " + e.statusCode());
            } catch (AnthropicException e) {
                throw new ModelUnavailable(e.getClass().getSimpleName());
            }
        };
    }
}
