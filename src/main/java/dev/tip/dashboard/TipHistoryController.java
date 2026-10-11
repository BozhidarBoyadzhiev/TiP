package dev.tip.dashboard;

import dev.tip.owners.api.OwnerPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed in owner's tips. Owner session required, see the owner security chain. Parameters are
 * taken as text and checked by {@link TipHistoryQuery}, so every bad value gets a 400 naming it.
 */
@RestController
@RequestMapping("/api/owner/tips")
class TipHistoryController {

    private final TipHistoryRepository tips;

    TipHistoryController(TipHistoryRepository tips) {
        this.tips = tips;
    }

    /** Newest first. Only tips where money moved, on the owner's own devices. */
    @GetMapping
    TipPage list(
            @AuthenticationPrincipal OwnerPrincipal owner,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String flagged,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return tips.page(owner.id(), TipHistoryQuery.parse(page, size, status, kind, flagged, from, to));
    }

    /** 404 for another owner's tip, the same answer as for a tip that does not exist. */
    @GetMapping("/{id}")
    TipView one(@AuthenticationPrincipal OwnerPrincipal owner, @PathVariable String id) {
        return tips.find(owner.id(), TipHistoryQuery.tipId(id)).orElseThrow(DashboardExceptions.TipNotFound::new);
    }
}
