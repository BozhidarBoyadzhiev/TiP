package dev.tip.dashboard;

import java.time.Instant;
import java.util.UUID;

/**
 * One tip as its owner sees it. A flagged tip has no {@code message} or {@code ownerNote}, only its
 * {@code flagReason}, so the owner does not have to read the abuse. {@code messageKind} and {@code
 * reaction} are plain strings, because the AI feature's types are internal to it.
 */
record TipView(
        UUID id,
        Instant createdAt,
        long amountMinor,
        String currency,
        String status,
        String deviceSlug,
        String message,
        String messageKind,
        String reaction,
        boolean flagged,
        String flagReason,
        String ownerNote) {}
