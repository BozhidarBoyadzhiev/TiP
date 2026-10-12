package dev.tip.dashboard;

import dev.tip.owners.api.OwnerPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reveal and overrule a message the AI flagged. Owner session required, and the CSRF header for the verdict,
 * see the owner security chain. Both answer the same 404 for an unknown id, another owner's tip and a tip
 * the AI did not flag, so tip ids cannot be probed.
 */
@RestController
@RequestMapping("/api/owner/tips/{id}")
class TipModerationController {

    private final TipModeration moderation;
    private final TipHistoryRepository tips;

    TipModerationController(TipModeration moderation, TipHistoryRepository tips) {
        this.moderation = moderation;
        this.tips = tips;
    }

    /** The flagged message, read on demand. Never stored by the browser. */
    @GetMapping("/message")
    ResponseEntity<MessageView> message(@AuthenticationPrincipal OwnerPrincipal owner, @PathVariable String id) {
        String text = moderation
                .message(owner.id(), TipHistoryQuery.tipId(id))
                .orElseThrow(DashboardExceptions.TipNotFound::new);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MessageView(text));
    }

    /** {@code hidden} false overrules the AI's flag, true hides the message again. Returns the tip as listed. */
    @PutMapping("/verdict")
    TipView verdict(
            @AuthenticationPrincipal OwnerPrincipal owner,
            @PathVariable String id,
            @Valid @RequestBody VerdictRequest request) {
        UUID tipId = TipHistoryQuery.tipId(id);
        if (!moderation.setHidden(owner.id(), tipId, request.hidden())) {
            throw new DashboardExceptions.TipNotFound();
        }
        return tips.find(owner.id(), tipId).orElseThrow(DashboardExceptions.TipNotFound::new);
    }

    record VerdictRequest(@NotNull Boolean hidden) {}

    record MessageView(String text) {}
}
