package dev.tip.devices;

import org.springframework.stereotype.Component;

/** Lets tests outside this package run the job that reports devices offline. */
@Component
public class PresenceJobRunner {

    private final DevicePresenceTracker presence;

    PresenceJobRunner(DevicePresenceTracker presence) {
        this.presence = presence;
    }

    public void reportOffline() {
        presence.reportOffline();
    }
}
