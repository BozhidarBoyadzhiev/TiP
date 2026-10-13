package dev.tip.owners;

import dev.tip.ai.api.AiAvailability;
import dev.tip.owners.api.OwnerPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed in owner's own profile. Owner session required, see the owner security chain. */
@RestController
class OwnerController {

    private final OwnerService owners;
    private final AiAvailability ai;

    OwnerController(OwnerService owners, AiAvailability ai) {
        this.owners = owners;
        this.ai = ai;
    }

    /** {@code aiResting} is true while the model is skipped after a spend limit or an authentication error. */
    @GetMapping("/api/owner/me")
    Me me(@AuthenticationPrincipal OwnerPrincipal owner) {
        return owners.find(owner.id())
                .map(profile -> new Me(profile.displayName(), profile.timeZone().getId(), ai.resting()))
                .orElseThrow(OwnerNotFound::new);
    }

    record Me(String displayName, String timeZone, boolean aiResting) {}
}
