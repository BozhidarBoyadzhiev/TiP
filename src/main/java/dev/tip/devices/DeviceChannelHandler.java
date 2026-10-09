package dev.tip.devices;

import dev.tip.devices.api.DeviceSummary;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The authenticated device channel. On connect the device gets {@code hello} and becomes the device's
 * one registered session. Inbound messages are size checked and parsed; anything that is not a well
 * formed {@code ack} is ignored, and a session that keeps sending such messages is closed.
 */
@Component
class DeviceChannelHandler extends TextWebSocketHandler {

    static final int MAX_INBOUND_BYTES = 1024;

    private static final String CONCURRENT = DeviceChannelHandler.class.getName() + ".concurrent";
    private static final String INVALID = DeviceChannelHandler.class.getName() + ".invalid";

    private static final Logger log = LoggerFactory.getLogger(DeviceChannelHandler.class);

    private final DeviceSessions sessions;
    private final ChannelMessages messages;
    private final Clock clock;

    DeviceChannelHandler(DeviceSessions sessions, ChannelMessages messages, Clock clock) {
        this.sessions = sessions;
        this.messages = messages;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        DeviceSummary device = device(raw);
        WebSocketSession session = DeviceSessions.concurrent(raw);
        raw.getAttributes().put(CONCURRENT, session);
        raw.getAttributes().put(INVALID, new InvalidMessages());
        session.sendMessage(messages.hello(device.displayName(), clock.instant()));
        sessions.register(device.id(), session);
        log.info("Device {} connected", device.id());
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        WebSocketSession session = concurrent(raw);
        if (message.getPayloadLength() > MAX_INBOUND_BYTES) {
            DeviceSessions.close(session, CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        Optional<UUID> tipId = messages.ackedTipId(message.getPayload());
        if (tipId.isEmpty()) {
            log.debug("Ignored an invalid message from device {}", device(raw).id());
            if (((InvalidMessages) raw.getAttributes().get(INVALID)).recordAndCheckLimit(clock.instant())) {
                DeviceSessions.close(session, CloseStatus.POLICY_VIOLATION);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        UUID deviceId = device(raw).id();
        WebSocketSession session = (WebSocketSession) raw.getAttributes().get(CONCURRENT);
        if (session != null) {
            sessions.unregister(deviceId, session);
        }
        log.info("Device {} disconnected with close code {}", deviceId, status.getCode());
    }

    private static DeviceSummary device(WebSocketSession raw) {
        return (DeviceSummary) raw.getAttributes().get(DeviceHandshakeInterceptor.DEVICE);
    }

    private static WebSocketSession concurrent(WebSocketSession raw) {
        return (WebSocketSession) raw.getAttributes().get(CONCURRENT);
    }
}
