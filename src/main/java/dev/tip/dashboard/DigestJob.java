package dev.tip.dashboard;

import dev.tip.ai.api.TipDigester;
import dev.tip.ai.api.TipDigester.Digest;
import dev.tip.ai.api.TipDigester.DigestMessage;
import dev.tip.ai.api.TipDigester.DigestNote;
import dev.tip.dashboard.DashboardRepository.DigestInput;
import dev.tip.dashboard.RequestsReport.StoredGroup;
import dev.tip.owners.api.OwnerDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Regenerates stale digests. A reaction marks its owner's digest of the day stale; every two minutes this
 * job takes each stale digest of an owner's today, at most once every {@link #MIN_INTERVAL} per owner and
 * only with at least {@link #MIN_MESSAGES} shown messages that day ({@link HiddenMessages}). The claim in
 * {@link DigestRepository} keeps a second instance from calling the model for the same digest, and a digest
 * is stored only when it read newer changes (reactions, or a message the owner unhid) than the stored one. A failure keeps the stored digest and
 * tries again on the next run. Without AI nothing happens. The model is called outside any transaction.
 */
@Component
class DigestJob {

    static final Duration MIN_INTERVAL = Duration.ofMinutes(10);
    static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(2);
    static final int MIN_MESSAGES = 3;

    /** Caps what one call reads, newest first. */
    static final int MAX_MESSAGES = 100;

    static final int MAX_NOTES = 100;

    private static final Logger log = LoggerFactory.getLogger(DigestJob.class);

    private final DigestRepository digests;
    private final DashboardRepository tips;
    private final OwnerDirectory owners;
    private final Optional<TipDigester> digester;
    private final OwnerSockets sockets;
    private final Clock clock;

    DigestJob(
            DigestRepository digests,
            DashboardRepository tips,
            OwnerDirectory owners,
            Optional<TipDigester> digester,
            OwnerSockets sockets,
            Clock clock) {
        this.digests = digests;
        this.tips = tips;
        this.owners = owners;
        this.digester = digester;
        this.sockets = sockets;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${tip.dashboard.digest-interval}",
            initialDelayString = "${tip.dashboard.digest-interval}")
    void run() {
        if (digester.isEmpty()) {
            return;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        for (DigestRepository.Candidate candidate :
                digests.staleSince(LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(1))) {
            ZoneId zone = owners.timeZone(candidate.ownerId());
            OwnerDay day = new OwnerDay(zone, now.atZone(zone));
            if (candidate.day().equals(day.today())) {
                regenerate(candidate.ownerId(), day);
            }
        }
    }

    private void regenerate(UUID ownerId, OwnerDay day) {
        Instant now = day.instant();
        Optional<DigestRepository.Claim> claim =
                digests.claim(ownerId, day.today(), now, now.minus(CLAIM_TIMEOUT), now.minus(MIN_INTERVAL));
        if (claim.isEmpty()) {
            return;
        }
        Instant claimedAt = claim.get().claimedAt();
        List<DigestInput> messages = tips.digestMessages(ownerId, day.startOfToday(), now, MAX_MESSAGES);
        List<DigestInput> notes =
                tips.digestNotes(ownerId, day.startOfDayBefore(RequestsReport.DAYS - 1), now, MAX_NOTES);
        Instant watermark = Stream.concat(messages.stream(), notes.stream())
                .map(DigestInput::changedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        Instant stored = claim.get().storedWatermark();
        if (messages.size() < MIN_MESSAGES || watermark == null || (stored != null && !watermark.isAfter(stored))) {
            digests.release(ownerId, day.today(), claimedAt, false);
            return;
        }
        Digest digest;
        try {
            digest = digester.get()
                    .digest(
                            messages.stream()
                                    .map(m -> new DigestMessage(m.tipId(), m.text()))
                                    .toList(),
                            notes.stream()
                                    .map(n -> new DigestNote(n.tipId(), n.text()))
                                    .toList());
        } catch (RuntimeException e) {
            String reason = e instanceof TipDigester.DigestFailed
                    ? e.getMessage()
                    : e.getClass().getSimpleName();
            log.warn("Digest for owner {} failed, keeping the previous one: {}", ownerId, reason);
            digests.release(ownerId, day.today(), claimedAt, true);
            return;
        }
        boolean storedNow = digests.store(
                ownerId,
                day.today(),
                claimedAt,
                digest.bullets(),
                digest.requestGroups().stream()
                        .map(g -> new StoredGroup(g.label(), g.tipIds()))
                        .toList(),
                messages.size(),
                watermark,
                now);
        digests.release(ownerId, day.today(), claimedAt, false);
        if (storedNow) {
            log.info("Stored the digest of owner {} from {} messages", ownerId, messages.size());
            sockets.digestChanged(ownerId);
        } else {
            log.info("Digest of owner {} discarded, a newer one is stored or it was dropped", ownerId);
        }
    }
}
