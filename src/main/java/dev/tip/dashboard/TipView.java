package dev.tip.dashboard;

import java.time.Instant;
import java.util.UUID;

/**
 * One tip as its owner sees it. {@code flagged} is true while the message is hidden (the AI flagged it and the
 * owner has not overruled that, see {@link HiddenMessages}); such a tip has no {@code message}, {@code
 * ownerNote}, {@code language}, {@code englishText} or {@code requestedSong}, only its {@code flagReason}, so
 * the owner does not have to read the abuse unless they ask for it. {@code unhidden} is true when the owner
 * overruled the flag: the tip then shows like any other, with what the AI read from it, and keeps its {@code
 * flagReason}. {@code messageKind} and {@code reaction} are plain strings, because the AI feature's types
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
        boolean unhidden,
        String flagReason,
        String ownerNote,
        String language,
        String languageName,
        String englishText,
        String requestedSong) {}
