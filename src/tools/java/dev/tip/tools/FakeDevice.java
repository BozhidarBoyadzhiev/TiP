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
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A command line device that speaks the device channel exactly as the ESP32 firmware will: it connects
 * with the device secret, acks each reaction as soon as it arrives, plays it after any reaction already
 * playing or queued, prints it with a text picture of its LED pattern scaled by {@code repeats}, ignores
 * repeats of the last 16 tip ids, and reconnects with the client policy's backoff.
 * A rejected secret (401) stops it; the firmware retries every 10 minutes instead. When the server says it is
 * closing because another connection replaced this one, it waits at least a minute before reconnecting, so
 * two clients with the same identity settle instead of replacing each other every second.
 *
 * <pre>./gradlew fakeDevice --args="--url=ws://localhost:8080 --device=&lt;uuid&gt; --secret=&lt;secret&gt;"</pre>
 *
 * <p>Without {@code --secret} it reads {@code TIP_DEMO_DEVICE_SECRET}, which the Gradle task loads from
 * {@code .env}, so the secret stays out of the shell history.
 */
public final class FakeDevice {

    static final int EXIT_AUTH_REJECTED = 2;
    static final int EXIT_USAGE = 64;

    /** The shortest wait after {@code closing} with reason {@code replaced}. */
    static final Duration REPLACED_WAIT = Duration.ofSeconds(60);

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
    private final AtomicBoolean replaced = new AtomicBoolean();
    private volatile WebSocket socket;

    /** {@code speedUp} plays patterns that many times faster than the panda; 1 outside tests. */
    FakeDevice(URI endpoint, String secret, PrintStream out, Backoff backoff, int speedUp) {
        this.endpoint = endpoint;
        this.secret = secret;
        this.out = out;
        this.backoff = backoff;
        this.player = new Player(out, speedUp);
    }

    public static void main(String[] args) {
        Optional<Options> options = Options.parse(args, System.getenv(), System.err);
        if (options.isEmpty()) {
            System.exit(EXIT_USAGE);
            return;
        }
        FakeDevice device = new FakeDevice(
                options.get().endpoint(), options.get().secret(), System.out, new Backoff(Math::random), 1);
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
                Duration connectedFor = Duration.ZERO;
                try {
                    socket = http.newWebSocketBuilder()
                            .header("Authorization", "Bearer " + secret)
                            .connectTimeout(CONNECT_TIMEOUT)
                            .buildAsync(endpoint, new Listener(closed))
                            .join();
                    long connectedAt = System.nanoTime();
                    closed.join();
                    connectedFor = Duration.ofNanos(System.nanoTime() - connectedAt);
                } catch (CompletionException e) {
                    if (handshakeStatus(e) == 401) {
                        out.println("Auth rejected (401). Check the device id and secret, then start again.");
                        return EXIT_AUTH_REJECTED;
                    }
                    out.println("Connection failed: " + describe(e.getCause()));
                }
                Duration delay = backoff.next(connectedFor);
                if (replaced.getAndSet(false) && delay.compareTo(REPLACED_WAIT) < 0) {
                    delay = REPLACED_WAIT;
                }
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
        } else if (type.equals("closing")) {
            String reason = message.path("reason").asString("");
            out.println("Server is closing the connection: " + reason);
            if (reason.equals("replaced")) {
                out.println("Replaced by another connection as this device");
                replaced.set(true);
            }
        } else if (type.equals("reaction")) {
            handleReaction(
                    message.path("id").asString(""), message.path("reaction").asString(""), repeats(message));
        }
    }

    /** A missing or non integer {@code repeats} means 1; any other value is clamped to 1 to 5. */
    static int repeats(JsonNode message) {
        JsonNode repeats = message.path("repeats");
        if (!repeats.isIntegralNumber()) {
            return LedPattern.MIN_REPEATS;
        }
        return LedPattern.clampRepeats(repeats.bigIntegerValue());
    }

    /**
     * Acks on receipt, before playback: resends come 5 seconds apart and a pattern can run up to 10
     * seconds, so an ack after playback would bring resends mid pattern.
     */
    private void handleReaction(String tipId, String reaction, int repeats) {
        try {
            UUID.fromString(tipId);
        } catch (IllegalArgumentException e) {
            return;
        }
        LedPattern pattern = knownPattern(reaction);
        if (pattern == null) {
            out.println("Unknown kind from tip " + tipId + ", acked, playing PLAIN with repeats 1");
            pattern = LedPattern.PLAIN;
            repeats = LedPattern.MIN_REPEATS;
        }
        if (!recent.add(tipId)) {
            out.println("Repeat of tip " + tipId + ", not played again, acked again");
            ack(tipId);
            return;
        }
        ack(tipId);
        Player.Reaction dropped = player.enqueue(new Player.Reaction(tipId, pattern, repeats));
        if (dropped != null) {
            out.println("Queue full, dropped tip " + dropped.tipId());
        }
    }

    /** The pattern for one of the four kinds, or null for anything else, which the client policy plays as PLAIN. */
    private static LedPattern knownPattern(String reaction) {
        for (LedPattern pattern : LedPattern.values()) {
            if (pattern.name().equals(reaction)) {
                return pattern;
            }
        }
        return null;
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
