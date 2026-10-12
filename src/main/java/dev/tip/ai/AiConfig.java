package dev.tip.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import dev.tip.ai.api.TipDigester;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableAsync
class AiConfig {

    static final String EXECUTOR = "aiExecutor";

    /** One virtual thread per captured tip, separate from any shared pool. */
    @Bean(EXECUTOR)
    SimpleAsyncTaskExecutor aiExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("ai-");
        executor.setVirtualThreads(true);
        return executor;
    }

    /** SDK retries stay off: the attempt counter on the tip is the retry. Also used by {@link EvalClassifier}. */
    static AnthropicClient buildClient(AiProperties properties) {
        return AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .baseUrl(properties.baseUrl())
                .timeout(properties.timeout())
                .maxRetries(0)
                .build();
    }

    /** Only built while AI is enabled, so the app starts without a key when it is not. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(name = "tip.ai.enabled", matchIfMissing = true)
    static class ClaudeConfig {

        @Bean
        AnthropicClient anthropicClient(AiProperties properties) {
            return buildClient(properties);
        }

        @Bean
        MessageClassifier claudeMessageClassifier(
                AnthropicClient client, AiProperties properties, JsonMapper jsonMapper) {
            return new ClaudeMessageClassifier(client, properties.model(), jsonMapper);
        }

        @Bean
        TipDigester claudeTipDigester(AnthropicClient client, AiProperties properties, JsonMapper jsonMapper) {
            return new ClaudeTipDigester(client, properties.model(), jsonMapper);
        }
    }
}
