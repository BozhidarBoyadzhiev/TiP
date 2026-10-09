package dev.tip.devices;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

/** A real WebSocket client ({@link StandardWebSocketClient}) that records what it receives. */
final class TestSocket extends TextWebSocketHandler implements AutoCloseable {

    /** {@code TIP_PUBLIC_BASE_URL} in the test profile. */
    static final String PUBLIC_ORIGIN = "http://localhost:8080";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> pongs = new LinkedBlockingQueue<>();
    private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
    private volatile WebSocketSession session;

    private TestSocket() {}

    static TestSocket connectDevice(int port, UUID deviceId, String secret) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setBearerAuth(secret);
        return connect(URI.create("ws://localhost:" + port + "/ws/device/" + deviceId), headers);
    }

    /** An observer with the app's public origin, from its own client IP. */
    static TestSocket connectObserver(int port, String slug, String clientIp) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(PUBLIC_ORIGIN);
        headers.set("X-Forwarded-For", clientIp);
        return connect(URI.create("ws://localhost:" + port + "/ws/observe/" + slug), headers);
    }

    static TestSocket connect(URI uri, WebSocketHttpHeaders headers) {
        TestSocket socket = new TestSocket();
        try {
            new StandardWebSocketClient().execute(socket, headers, uri).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Could not connect to " + uri, e);
        }
        return socket;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        this.session = session;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        messages.add(message.getPayload());
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        ByteBuffer payload = message.getPayload();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        pongs.add(new String(bytes, StandardCharsets.UTF_8));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closed.complete(status);
    }

    String next() {
        try {
            String message = messages.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(message).as("message within %s", TIMEOUT).isNotNull();
            return message;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> nextJson() {
        return JSON.readValue(next(), Map.class);
    }

    /** Messages received and not yet taken, without waiting. */
    int pending() {
        return messages.size();
    }

    void send(String text) {
        try {
            session.sendMessage(new TextMessage(text));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    void ack(UUID tipId) {
        send("{\"type\":\"ack\",\"id\":\"" + tipId + "\"}");
    }

    /**
     * Pings the server and waits for the pong. The server handles a session's frames in order, so every
     * message sent before has been handled when this returns.
     */
    void sync() {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        try {
            session.sendMessage(new PingMessage(ByteBuffer.wrap(marker.getBytes(StandardCharsets.UTF_8))));
            String pong;
            do {
                pong = pongs.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                assertThat(pong).as("pong within %s", TIMEOUT).isNotNull();
            } while (!pong.equals(marker));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    CloseStatus awaitClose() {
        try {
            return closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Session was not closed within " + TIMEOUT, e);
        }
    }

    boolean isOpen() {
        return !closed.isDone() && session.isOpen();
    }

    @Override
    public void close() throws Exception {
        if (session.isOpen()) {
            session.close();
        }
    }
}
