package dev.tip.devices;

import dev.tip.devices.api.DeviceSummary;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The authenticated device channel. On connect the session is registered as the device's one session,
 * still pending, the device is marked connected, it gets {@code hello}, and only then is the session
 * ready for live sends, so {@code hello} is always the first message. The replay of its unacknowledged
 * reactions of the last minute, oldest first, runs after that and covers anything stored while pending. Inbound
 * messages are size checked and parsed; an {@code ack} acknowledges one of the device's own reactions,
 * anything else is ignored, and a session that keeps sending such messages is closed.
 */
@Component
class DeviceChannelHandler extends TextWebSocketHandler {

    static final int MAX_INBOUND_BYTES = 1024;

    private static final String CONCURRENT = DeviceChannelHandler.class.getName() + ".concurrent";
    private static final String INVALID = DeviceChannelHandler.class.getName() + ".invalid";

    private static final Logger log = LoggerFactory.getLogger(DeviceChannelHandler.class);

    private final DeviceSessions sessions;
    private final ChannelMessages messages;
    private final ReactionDeliveryRepository deliveries;
    private final ReactionSender sender;
    private final SessionKeepalive keepalive;
    private final DevicePresenceTracker presence;
    private final Clock clock;

    DeviceChannelHandler(
            DeviceSessions sessions,
            ChannelMessages messages,
            ReactionDeliveryRepository deliveries,
            ReactionSender sender,
            SessionKeepalive keepalive,
            DevicePresenceTracker presence,
            Clock clock) {
        this.sessions = sessions;
        this.messages = messages;
        this.deliveries = deliveries;
        this.sender = sender;
        this.keepalive = keepalive;
        this.presence = presence;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        DeviceSummary device = device(raw);
        WebSocketSession session = DeviceSessions.concurrent(raw);
        raw.getAttributes().put(CONCURRENT, session);
        raw.getAttributes().put(INVALID, new InvalidMessages());
        keepalive.connected(raw);
        sessions.register(device.id(), session);
        presence.connected(device.id());
        session.sendMessage(messages.hello(device.displayName(), clock.instant()));
        sessions.markReady(device.id(), session);
        log.info("Device {} connected", device.id());
        for (PendingReaction reaction :
                deliveries.unacked(device.id(), clock.instant().minus(ReactionSender.MAX_AGE))) {
            sender.send(session, reaction);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        WebSocketSession session = concurrent(raw);
        if (message.getPayloadLength() > MAX_INBOUND_BYTES) {
            DeviceSessions.close(session, CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        Optional<UUID> tipId = messages.ackedTipId(message.getPayload());
        if (tipId.isPresent()) {
            deliveries.ack(tipId.get(), device(raw).id(), clock.instant());
        } else {
            log.debug("Ignored an invalid message from device {}", device(raw).id());
            if (((InvalidMessages) raw.getAttributes().get(INVALID)).recordAndCheckLimit(clock.instant())) {
                DeviceSessions.close(session, CloseStatus.POLICY_VIOLATION);
            }
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession raw, PongMessage message) {
        keepalive.ponged(raw);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        UUID deviceId = device(raw).id();
        WebSocketSession session = (WebSocketSession) raw.getAttributes().get(CONCURRENT);
        if (session != null) {
            sessions.unregister(deviceId, session);
        }
        presence.disconnected(deviceId);
        log.info("Device {} disconnected with close code {}", deviceId, status.getCode());
    }

    private static DeviceSummary device(WebSocketSession raw) {
        return (DeviceSummary) raw.getAttributes().get(DeviceHandshakeInterceptor.DEVICE);
    }

    private static WebSocketSession concurrent(WebSocketSession raw) {
        return (WebSocketSession) raw.getAttributes().get(CONCURRENT);
    }
}
