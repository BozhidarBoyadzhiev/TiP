package dev.tip.devices;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Sends a {@code reaction} to a device session and records the send. The dispatcher, the resend job and
 * the connect replay all send through here. Delivery rules from {@code docs/DEVICE_PROTOCOL.md}.
 */
@Component
class ReactionSender {

    /** Unacknowledged reactions older than this are dropped: a light reacting late means nothing. */
    static final Duration MAX_AGE = Duration.ofSeconds(60);

    static final Duration RESEND_INTERVAL = Duration.ofSeconds(5);

    /** The first send plus up to 3 resends. */
    static final int MAX_SENDS = 4;

    private static final Logger log = LoggerFactory.getLogger(ReactionSender.class);

    private final ReactionDeliveryRepository deliveries;
    private final ChannelMessages messages;
    private final Clock clock;

    ReactionSender(ReactionDeliveryRepository deliveries, ChannelMessages messages, Clock clock) {
        this.deliveries = deliveries;
        this.messages = messages;
        this.clock = clock;
    }

    void send(WebSocketSession session, PendingReaction reaction) {
        try {
            session.sendMessage(
                    messages.reaction(reaction.tipId(), reaction.reaction(), reaction.repeats(), reaction.reactedAt()));
        } catch (IOException | RuntimeException e) {
            log.debug("Sending reaction {} to device {} failed", reaction.tipId(), reaction.deviceId(), e);
            return;
        }
        deliveries.recordSend(reaction.tipId(), clock.instant());
    }
}
