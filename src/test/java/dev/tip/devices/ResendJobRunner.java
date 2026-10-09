package dev.tip.devices;

import java.time.Duration;
import org.springframework.stereotype.Component;

/** Lets tests outside this package, such as the fake device's, run the resend job themselves. */
@Component
public class ResendJobRunner {

    public static final Duration RESEND_INTERVAL = ReactionSender.RESEND_INTERVAL;

    private final ReactionResendJob job;

    ResendJobRunner(ReactionResendJob job) {
        this.job = job;
    }

    public void resend() {
        job.resend();
    }
}
