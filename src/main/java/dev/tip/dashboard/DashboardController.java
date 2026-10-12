package dev.tip.dashboard;

import dev.tip.devices.api.DevicePresence;
import dev.tip.owners.api.OwnerDirectory;
import dev.tip.owners.api.OwnerPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.NullNode;

/**
 * The signed in owner's dashboard panels. Owner session required, see the owner security chain. Every
 * answer is scoped to the owner in SQL and computed in the owner's time zone; flagged tips add to counts
 * and totals only.
 */
@RestController
@RequestMapping("/api/owner")
class DashboardController {

    private final DashboardRepository tips;
    private final DigestRepository digests;
    private final OwnerDirectory owners;
    private final DevicePresence devices;
    private final Clock clock;

    DashboardController(
            DashboardRepository tips,
            DigestRepository digests,
            OwnerDirectory owners,
            DevicePresence devices,
            Clock clock) {
        this.tips = tips;
        this.digests = digests;
        this.owners = owners;
        this.devices = devices;
        this.clock = clock;
    }

    @GetMapping("/today")
    TodayReport today(@AuthenticationPrincipal OwnerPrincipal owner) {
        OwnerDay day = day(owner);
        return TodayReport.of(day, tips.tipFacts(owner.id(), day.startOfToday(), day.instant()));
    }

    @GetMapping("/requests")
    RequestsReport requests(@AuthenticationPrincipal OwnerPrincipal owner) {
        OwnerDay day = day(owner);
        return RequestsReport.of(
                day,
                tips.requests(owner.id(), day.startOfDayBefore(RequestsReport.DAYS - 1), day.instant()),
                digests.find(owner.id(), day.today())
                        .map(DigestRepository.StoredDigest::groups)
                        .orElse(null));
    }

    /** Today's notes, or a JSON {@code null} when there are none yet. */
    @GetMapping("/digest")
    ResponseEntity<Object> digest(@AuthenticationPrincipal OwnerPrincipal owner) {
        Object body = digests.find(owner.id(), day(owner).today())
                .<Object>map(d -> new DigestView(d.bullets(), d.messageCount(), d.generatedAt()))
                .orElse(NullNode.getInstance());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @GetMapping("/devices")
    List<DeviceView> devices(@AuthenticationPrincipal OwnerPrincipal owner) {
        return devices.ownedBy(owner.id()).stream()
                .map(d -> new DeviceView(
                        d.slug(),
                        d.name(),
                        d.online(),
                        d.lastSeenAt() == null ? null : d.lastSeenAt().truncatedTo(ChronoUnit.SECONDS)))
                .toList();
    }

    private OwnerDay day(OwnerPrincipal owner) {
        return OwnerDay.of(clock, owners.timeZone(owner.id()));
    }

    record DigestView(List<String> bullets, int messageCount, Instant generatedAt) {}

    record DeviceView(String slug, String name, boolean online, Instant lastSeenAt) {}
}
