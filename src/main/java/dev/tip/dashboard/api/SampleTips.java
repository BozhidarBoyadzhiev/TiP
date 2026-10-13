package dev.tip.dashboard.api;

import java.time.ZoneId;
import java.util.UUID;

/**
 * The invented sample tips that dev, every sandbox and the public demo start with: tips backdated over three
 * local days with several today, flagged ones among them, and today's panda's notes and request groups already
 * written, so no model call is needed. Sample tips are {@code demo}: no PayPal ids, deliveries already acked, and
 * a refund answers {@code DEMO_TIP}. Both methods join the caller's transaction.
 */
public interface SampleTips {

    /** Adds the samples to the owner's device, dated in {@code zone}. */
    void seed(UUID ownerId, UUID deviceId, ZoneId zone);

    /**
     * Replaces the device's samples and the owner's notes with a fresh set, unless the device already has samples
     * from today in {@code zone}. Real tips are never touched. True when it seeded.
     */
    boolean refresh(UUID ownerId, UUID deviceId, ZoneId zone);
}
