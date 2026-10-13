package dev.tip.owners.api;

import java.io.Serial;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The signed in owner. Other features read it with {@code @AuthenticationPrincipal OwnerPrincipal}. The password
 * hash is erased after authentication, so the session never holds it. A sandbox has no email and an {@code
 * expiresAt} that its session never outlives; the public demo's session is read only.
 */
public final class OwnerPrincipal implements UserDetails, CredentialsContainer {

    public static final String ROLE = "OWNER";

    @Serial
    private static final long serialVersionUID = 2L;

    private final UUID id;
    private final String email;
    private final String displayName;
    private final OwnerKind kind;
    private final Instant expiresAt;
    private String passwordHash;

    /** A real owner signing in with email and password. */
    public OwnerPrincipal(UUID id, String email, String displayName, String passwordHash) {
        this(id, Objects.requireNonNull(email, "email"), displayName, passwordHash, OwnerKind.REAL, null);
    }

    public OwnerPrincipal(
            UUID id, String email, String displayName, String passwordHash, OwnerKind kind, Instant expiresAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = email;
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.passwordHash = passwordHash;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.expiresAt = expiresAt;
    }

    public UUID id() {
        return id;
    }

    /** Null for a sandbox and the public demo. */
    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public OwnerKind kind() {
        return kind;
    }

    /** Set for a sandbox only. */
    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean expiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** The public demo: it may read everything of its owner and change nothing. */
    public boolean readOnly() {
        return kind == OwnerKind.PUBLIC_DEMO;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + ROLE));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email != null ? email : id.toString();
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OwnerPrincipal that && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "OwnerPrincipal[id=" + id + ", kind=" + kind + "]";
    }
}
