package dev.tip.tools;

import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A command line device that speaks the device channel exactly as the ESP32 firmware will: it connects
 * with the device secret, prints each reaction with a text picture of its LED pattern, acks it when it
 * starts playing, ignores repeats among the last 16 tip ids, and reconnects with the protocol backoff.
 * A rejected secret (401) stops it, like the firmware, which waits for a reboot.
 *
 * <pre>./gradlew fakeDevice --args="--url=ws://localhost:8080 --device=&lt;uuid&gt; --secret=&lt;secret&gt;"</pre>
 *
 * <p>Without {@code --secret} it reads {@code TIP_DEMO_DEVICE_SECRET}, which the Gradle task loads from
 * {@code .env}, so the secret stays out of the shell history.
 */
public final class FakeDevice {

    static final int EXIT_AUTH_REJECTED = 2;
    static final int EXIT_USAGE = 64;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI endpoint;
    private final String secret;
    private final PrintStream out;
    private final Backoff backoff;
    private final HttpClient http = HttpClient.newHttpClient();
    private final RecentIds recent = new RecentIds();
    private final Player player;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile WebSocket socket;

    FakeDevice(URI endpoint, String secret, PrintStream out, Backoff backoff) {
        this.endpoint = endpoint;
        this.secret = secret;
        this.out = out;
        this.backoff = backoff;
        this.player = new Player(this::ack, out);
    }

    public static void main(String[] args) {
        Optional<Options> options = Options.parse(args, System.getenv(), System.err);
        if (options.isEmpty()) {
            System.exit(EXIT_USAGE);
            return;
        }
        FakeDevice device =
                new FakeDevice(options.get().endpoint(), options.get().secret(), System.out, new Backoff(Math::random));
        Runtime.getRuntime().addShutdownHook(new Thread(device::stop));
        System.exit(device.run());
    }

    /** Connects and reconnects until stopped or the secret is rejected. Returns the exit status. */
    int run() {
        Thread playerThread = Thread.ofVirtual().name("fake-led").start(player);
        try {
            while (stopped.getCount() > 0) {
                out.println("Connecting to " + endpoint);
                CompletableFuture<Integer> closed = new CompletableFuture<>();
                try {
                    socket = http.newWebSocketBuilder()
                            .header("Authorization", "Bearer " + secret)
                            .connectTimeout(CONNECT_TIMEOUT)
                            .buildAsync(endpoint, new Listener(closed))
                            .join();
                    backoff.reset();
                    closed.join();
                } catch (CompletionException e) {
                    if (handshakeStatus(e) == 401) {
                        out.println("Auth rejected (401). Check the device id and secret, then start again.");
                        return EXIT_AUTH_REJECTED;
                    }
                    out.println("Connection failed: " + describe(e.getCause()));
                }
                Duration delay = backoff.next();
                if (stopped.getCount() > 0) {
                    out.printf("Reconnecting in %.1f s%n", delay.toMillis() / 1000.0);
                }
                if (stopped.await(delay.toMillis(), TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } finally {
            playerThread.interrupt();
        }
    }

    void stop() {
        stopped.countDown();
        WebSocket current = socket;
        if (current != null && !current.isOutputClosed()) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "bye").orTimeout(2, TimeUnit.SECONDS);
        }
    }

    /** Reactions queued behind the one playing. */
    int waitingReactions() {
        return player.waiting();
    }

    void handle(String text) {
        JsonNode message;
        try {
            message = JSON.readTree(text);
        } catch (JacksonException e) {
            return;
        }
        String type = message.path("type").asString("");
        if (type.equals("hello")) {
            out.printf(
                    "Connected as \"%s\", server time %s%n",
                    message.path("deviceName").asString(""),
                    message.path("serverTime").asString(""));
        } else if (type.equals("reaction")) {
            handleReaction(
                    message.path("id").asString(""), message.path("reaction").asString(""));
        }
    }

    private void handleReaction(String tipId, String reaction) {
        LedPattern pattern;
        try {
            UUID.fromString(tipId);
            pattern = LedPattern.valueOf(reaction);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!recent.add(tipId)) {
            out.println("Repeat of tip " + tipId + ", not played again, acked again");
            ack(tipId);
            return;
        }
        Player.Reaction dropped = player.enqueue(new Player.Reaction(tipId, pattern));
        if (dropped != null) {
            out.println("Queue full, dropped tip " + dropped.tipId());
        }
    }

    /** Sends one ack. The JDK WebSocket allows one outstanding send at a time, hence the lock. */
    private synchronized void ack(String tipId) {
        WebSocket current = socket;
        if (current == null || current.isOutputClosed()) {
            return;
        }
        try {
            current.sendText("{\"type\":\"ack\",\"id\":\"" + tipId + "\"}", true)
                    .join();
        } catch (CompletionException e) {
            out.println("Could not ack tip " + tipId + ": " + describe(e.getCause()));
        }
    }

    private static int handshakeStatus(CompletionException e) {
        return e.getCause() instanceof WebSocketHandshakeException handshake
                ? handshake.getResponse().statusCode()
                : -1;
    }

    private static String describe(Throwable error) {
        if (error instanceof WebSocketHandshakeException handshake) {
            return "HTTP " + handshake.getResponse().statusCode();
        }
        return error == null ? "unknown error" : error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    private final class Listener implements WebSocket.Listener {

        private final CompletableFuture<Integer> closed;
        private final StringBuilder text = new StringBuilder();

        Listener(CompletableFuture<Integer> closed) {
            this.closed = closed;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                handle(text.toString());
                text.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            out.println("Closed by the server with " + statusCode + (reason.isEmpty() ? "" : " (" + reason + ")"));
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            out.println("Connection lost: " + describe(error));
            closed.complete(-1);
        }
    }

    /** Command line options. */
    record Options(URI endpoint, String secret) {

        static final String USAGE = "Usage: ./gradlew fakeDevice --args=\"--url=ws://localhost:8080 --device=<uuid>"
                + " [--secret=<secret>]\"\nWithout --secret, TIP_DEMO_DEVICE_SECRET is used.";

        /** The options, or empty after printing why they are not valid. */
        static Optional<Options> parse(String[] args, Map<String, String> env, PrintStream err) {
            String url = "ws://localhost:8080";
            String device = null;
            String secret = env.get("TIP_DEMO_DEVICE_SECRET");
            for (String arg : args) {
                if (arg.startsWith("--url=")) {
                    url = arg.substring("--url=".length());
                } else if (arg.startsWith("--device=")) {
                    device = arg.substring("--device=".length());
                } else if (arg.startsWith("--secret=")) {
                    secret = arg.substring("--secret=".length());
                } else {
                    err.println("Unknown option " + arg + "\n" + USAGE);
                    return Optional.empty();
                }
            }
            if (device == null || secret == null || secret.isBlank()) {
                err.println("--device and a secret are required.\n" + USAGE);
                return Optional.empty();
            }
            try {
                UUID deviceId = UUID.fromString(device);
                URI base = URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
                if (!"ws".equals(base.getScheme()) && !"wss".equals(base.getScheme())) {
                    err.println("--url must start with ws:// or wss://\n" + USAGE);
                    return Optional.empty();
                }
                return Optional.of(new Options(URI.create(base + "/ws/device/" + deviceId), secret.strip()));
            } catch (IllegalArgumentException e) {
                err.println("Invalid --device or --url: " + e.getMessage() + "\n" + USAGE);
                return Optional.empty();
            }
        }
    }
}
