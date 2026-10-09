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

    TextMessage reaction(UUID tipId, Reaction reaction, Instant at) {
        return text(new DeviceReaction("reaction", tipId.toString(), reaction.name(), time(at)));
    }

    /** What observers see: no tip id, amount, message or payer data. */
    TextMessage observerReaction(Reaction reaction, Instant at) {
        return text(new ObserverReaction("reaction", reaction.name(), time(at)));
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

    record DeviceReaction(String type, String id, String reaction, String at) {}

    record ObserverReaction(String type, String reaction, String at) {}

    record Inbound(String type, String id) {}
}
