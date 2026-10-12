package dev.tip.devices;

import dev.tip.ai.api.Reaction;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON messages of {@code docs/DEVICE_PROTOCOL.md}. Times are ISO 8601 UTC to the second, as in the
 * protocol's examples.
 */
@Component
class ChannelMessages {

    private final JsonMapper json;

    ChannelMessages(JsonMapper json) {
        this.json = json;
    }

    TextMessage hello(String deviceName, Instant serverTime) {
        return text(new Hello("hello", deviceName, time(serverTime)));
    }

    TextMessage reaction(UUID tipId, Reaction reaction, int repeats, Instant at) {
        return text(new DeviceReaction("reaction", tipId.toString(), reaction.name(), repeats, time(at)));
    }

    /**
     * What observers see: the reaction and its repeats, so the virtual panda plays what the LED plays. No
     * tip id, amount, message, kind, flag or payer data.
     */
    TextMessage observerReaction(Reaction reaction, int repeats, Instant at) {
        return text(new ObserverReaction("reaction", reaction.name(), repeats, time(at)));
    }

    /**
     * Why the server is about to close a device session. Sent just before the close, because close codes
     * do not reach clients through Render's proxy: they arrive as 1005 (no status).
     */
    TextMessage closing(ClosingReason reason) {
        return text(new Closing("closing", reason.wire));
    }

    /** The tip id of a well formed {@code ack}, or empty for anything else. */
    Optional<UUID> ackedTipId(String payload) {
        try {
            Inbound inbound = json.readValue(payload, Inbound.class);
            if (inbound == null || !"ack".equals(inbound.type()) || inbound.id() == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(Uuids.parse(inbound.id()));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private TextMessage text(Object message) {
        return new TextMessage(json.writeValueAsString(message));
    }

    private static String time(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    record Hello(String type, String deviceName, String serverTime) {}

    record DeviceReaction(String type, String id, String reaction, int repeats, String at) {}

    record ObserverReaction(String type, String reaction, int repeats, String at) {}

    record Closing(String type, String reason) {}

    record Inbound(String type, String id) {}

    /** The {@code reason} of a {@code closing} message. */
    enum ClosingReason {
        /** Another connection authenticated as the same device. */
        REPLACED("replaced"),
        /** No pong for 45 seconds. */
        TIMEOUT("timeout"),
        /** The server is shutting down, for example during a deploy. */
        SHUTDOWN("shutdown");

        final String wire;

        ClosingReason(String wire) {
            this.wire = wire;
        }
    }
}
