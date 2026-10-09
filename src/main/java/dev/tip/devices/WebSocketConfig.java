package dev.tip.devices;

import dev.tip.shared.TipProperties;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Raw WebSocket endpoints, no STOMP or SockJS, because the ESP32 speaks plain WebSocket.
 *
 * <p>The device channel keeps Spring's default same origin check. The ESP32 sends no {@code Origin}
 * header, which that check allows. Cross site WebSocket hijacking cannot reach this endpoint anyway: a
 * browser cannot set an {@code Authorization} header on a WebSocket, so a page on another site has no
 * way to present the device secret.
 *
 * <p>The observer channel is public and read only. It allows only the origin in {@code
 * TIP_PUBLIC_BASE_URL}, where the virtual panda page is served, plus clients that send no origin at all,
 * such as a display script.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@EnableAsync
class WebSocketConfig implements WebSocketConfigurer {

    static final String DEVICE_PATH = "/ws/device/";
    static final String OBSERVER_PATH = "/ws/observe/";

    static final String DELIVERY_EXECUTOR = "deliveryExecutor";

    /** Larger messages are closed by the container with 1009 before they reach a handler. */
    private static final int CONTAINER_TEXT_BUFFER_BYTES = 8 * 1024;

    private static final int CONTAINER_BINARY_BUFFER_BYTES = 1024;

    private final DeviceChannelHandler deviceHandler;
    private final DeviceHandshakeInterceptor deviceInterceptor;
    private final ObserverChannelHandler observerHandler;
    private final ObserverHandshakeInterceptor observerInterceptor;
    private final ProblemHandshakeHandler handshakeHandler;
    private final TipProperties tipProperties;

    WebSocketConfig(
            DeviceChannelHandler deviceHandler,
            DeviceHandshakeInterceptor deviceInterceptor,
            ObserverChannelHandler observerHandler,
            ObserverHandshakeInterceptor observerInterceptor,
            ProblemHandshakeHandler handshakeHandler,
            TipProperties tipProperties) {
        this.deviceHandler = deviceHandler;
        this.deviceInterceptor = deviceInterceptor;
        this.observerHandler = observerHandler;
        this.observerInterceptor = observerInterceptor;
        this.handshakeHandler = handshakeHandler;
        this.tipProperties = tipProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(deviceHandler, DEVICE_PATH + "*")
                .setHandshakeHandler(handshakeHandler)
                .addInterceptors(deviceInterceptor);
        registry.addHandler(observerHandler, OBSERVER_PATH + "*")
                .setHandshakeHandler(handshakeHandler)
                .setAllowedOrigins(publicOrigin(tipProperties.publicBaseUrl()))
                .addInterceptors(observerInterceptor);
    }

    /** The origin of the public base URL: scheme, host and port, without a path or trailing slash. */
    static String publicOrigin(String publicBaseUrl) {
        URI uri = URI.create(publicBaseUrl.strip());
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    /**
     * Runs the reaction dispatcher off the publisher's thread. After commit, the publisher's database
     * connection is still bound to its thread, so a dispatcher running there would hold two connections
     * at once, and enough simultaneous reactions would drain the pool and wait on each other.
     */
    @Bean(DELIVERY_EXECUTOR)
    SimpleAsyncTaskExecutor deliveryExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("delivery-");
        executor.setVirtualThreads(true);
        return executor;
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(CONTAINER_TEXT_BUFFER_BYTES);
        container.setMaxBinaryMessageBufferSize(CONTAINER_BINARY_BUFFER_BYTES);
        return container;
    }
}
