package dev.tip.devices;

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
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@EnableAsync
class WebSocketConfig implements WebSocketConfigurer {

    static final String DEVICE_PATH = "/ws/device/";

    static final String DELIVERY_EXECUTOR = "deliveryExecutor";

    /** Larger messages are closed by the container with 1009 before they reach a handler. */
    private static final int CONTAINER_TEXT_BUFFER_BYTES = 8 * 1024;

    private static final int CONTAINER_BINARY_BUFFER_BYTES = 1024;

    private final DeviceChannelHandler deviceHandler;
    private final DeviceHandshakeInterceptor deviceInterceptor;
    private final ProblemHandshakeHandler handshakeHandler;

    WebSocketConfig(
            DeviceChannelHandler deviceHandler,
            DeviceHandshakeInterceptor deviceInterceptor,
            ProblemHandshakeHandler handshakeHandler) {
        this.deviceHandler = deviceHandler;
        this.deviceInterceptor = deviceInterceptor;
        this.handshakeHandler = handshakeHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(deviceHandler, DEVICE_PATH + "*")
                .setHandshakeHandler(handshakeHandler)
                .addInterceptors(deviceInterceptor);
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
