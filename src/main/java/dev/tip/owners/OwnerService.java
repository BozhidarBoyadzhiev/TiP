package dev.tip.owners;

import dev.tip.owners.api.OwnerDirectory;
import dev.tip.owners.api.OwnerPrincipal;
import dev.tip.owners.api.OwnerProfile;
import dev.tip.owners.api.OwnerProvisioning;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner accounts backed by the {@code owner} table. Being the only {@link UserDetailsService} bean also
 * stops Spring Boot from creating its default in memory user with a generated password.
 */
@Service
class OwnerService implements UserDetailsService, OwnerProvisioning, OwnerDirectory {

    static final int MIN_PASSWORD_LENGTH = 12;

    private static final Logger log = LoggerFactory.getLogger(OwnerService.class);

    private final OwnerRepository owners;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    OwnerService(OwnerRepository owners, PasswordEncoder passwordEncoder, Clock clock) {
        this.owners = owners;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** The authentication provider turns the not found exception into the same failure as a wrong password. */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        return owners.findByEmail(OwnerEmails.normalize(email))
                .map(o -> new OwnerPrincipal(o.id(), o.email(), o.displayName(), o.passwordHash()))
                .orElseThrow(() -> new UsernameNotFoundException("No owner with this email"));
    }

    @Override
    @Transactional
    public UUID ensureOwner(String email, String rawPassword, String displayName) {
        String normalized = OwnerEmails.normalize(email);
        return owners.findByEmail(normalized).map(Owner::id).orElseGet(() -> {
            if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
                throw new IllegalArgumentException("Owner passwords must have at least 12 characters");
            }
            Owner owner = owners.save(new Owner(
                    UUID.randomUUID(), normalized, passwordEncoder.encode(rawPassword), displayName, clock.instant()));
            log.info("Created owner {}", owner.id());
            return owner.id();
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OwnerProfile> find(UUID ownerId) {
        return owners.findById(ownerId)
                .map(o -> new OwnerProfile(o.displayName(), o.timeZone(), o.kind(), o.expiresAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID ownerId) {
        return find(ownerId).map(o -> o.activeAt(clock.instant())).orElse(false);
    }
}
