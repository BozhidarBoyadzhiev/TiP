package dev.tip.devices.api;

import java.util.UUID;

public record DeviceSummary(UUID id, String slug, String displayName) {}
