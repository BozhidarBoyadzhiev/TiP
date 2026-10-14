package dev.tip.owners;

import dev.tip.ai.api.AiAvailability;
import dev.tip.owners.api.OwnerKind;
import dev.tip.owners.api.OwnerPrincipal;
import java.time.Instant;
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

    /**
     * {@code aiResting} is true while the model is skipped after a spend limit or an authentication error. {@code
     * kind} is {@code OWNER} for a real owner, {@code SANDBOX} or {@code PUBLIC_DEMO}; {@code readOnly} is true
     * for the public demo only; {@code expiresAt} is set for a sandbox only.
     */
    @GetMapping("/api/owner/me")
    Me me(@AuthenticationPrincipal OwnerPrincipal owner) {
        return owners.find(owner.id())
                .map(profile -> new Me(
                        profile.displayName(),
                        profile.timeZone().getId(),
                        ai.resting(),
                        kindName(profile.kind()),
                        profile.kind() == OwnerKind.PUBLIC_DEMO,
                        profile.kind() == OwnerKind.SANDBOX ? profile.expiresAt() : null))
                .orElseThrow(OwnerNotFound::new);
    }

    /** The page's word for a real owner is {@code OWNER}; the other kinds keep their names. */
    private static String kindName(OwnerKind kind) {
        return kind == OwnerKind.REAL ? "OWNER" : kind.name();
    }

    record Me(
            String displayName, String timeZone, boolean aiResting, String kind, boolean readOnly, Instant expiresAt) {}
}
