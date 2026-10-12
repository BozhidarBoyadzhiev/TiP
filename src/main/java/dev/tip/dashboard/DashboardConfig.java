package dev.tip.dashboard;

import dev.tip.shared.ProblemHandshakeHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * The owner channel endpoint and the executor its after commit listeners run on. The channel needs the
 * owner's session, see the owner security chain, and checks the origin itself, see {@link
 * OwnerHandshakeInterceptor}.
 */
@Configuration(proxyBeanMethods = false)
class DashboardConfig implements WebSocketConfigurer {

    static final String CHANNEL_PATH = "/ws/owner";
    static final String EXECUTOR = "dashboardExecutor";

    private final OwnerChannelHandler handler;
    private final OwnerHandshakeInterceptor interceptor;
    private final ProblemHandshakeHandler handshakeHandler;

    DashboardConfig(
            OwnerChannelHandler handler,
            OwnerHandshakeInterceptor interceptor,
            ProblemHandshakeHandler handshakeHandler) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.handshakeHandler = handshakeHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, CHANNEL_PATH)
                .setHandshakeHandler(handshakeHandler)
                // Spring's own check would allow a missing Origin; the interceptor checks it exactly instead.
                .setAllowedOriginPatterns("*")
                .addInterceptors(interceptor);
    }

    /**
     * Runs the listeners that read the database after another feature's commit. On the publisher's thread
     * they would hold the committed transaction's connection while taking a second one.
     */
    @Bean(EXECUTOR)
    SimpleAsyncTaskExecutor dashboardExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("dashboard-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
