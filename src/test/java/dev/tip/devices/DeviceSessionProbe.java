package dev.tip.devices;

import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

/**
 * Lets tests outside this package send a device a message the server itself never sends, such as a
 * reaction of an unknown kind, over the device's live session.
 */
@Component
public class DeviceSessionProbe {

    private final DeviceSessions sessions;

    DeviceSessionProbe(DeviceSessions sessions) {
        this.sessions = sessions;
    }

    public boolean isConnected(UUID deviceId) {
        return sessions.find(deviceId).isPresent();
    }

    public void sendRaw(UUID deviceId, String text) throws IOException {
        sessions.find(deviceId).orElseThrow().sendMessage(new TextMessage(text));
    }
}
