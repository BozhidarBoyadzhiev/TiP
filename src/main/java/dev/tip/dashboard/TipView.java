package dev.tip.dashboard;

import java.time.Instant;
import java.util.UUID;

/**
 * One tip as its owner sees it. A flagged tip has no {@code message}, {@code ownerNote}, {@code language},
 * {@code englishText} or {@code requestedSong}, only its {@code flagReason}, so the owner does not have to
 * read the abuse. {@code messageKind} and {@code reaction} are plain strings, because the AI feature's types
 * are internal to it. {@code language} is the ISO 639-1 code the AI read, {@code languageName} its English
 * name, and {@code englishText} the AI's translation when the message is not in English. {@code repeats}
 * is what the panda was sent, read from the stored reaction delivery; null when none was stored, such as for
 * a tip that has not reacted yet.
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
        Integer repeats,
        boolean flagged,
        String flagReason,
        String ownerNote,
        String language,
        String languageName,
        String englishText,
        String requestedSong) {}
