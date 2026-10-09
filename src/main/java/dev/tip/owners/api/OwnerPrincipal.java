package dev.tip.owners.api;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The signed in owner. Other features read it with {@code @AuthenticationPrincipal OwnerPrincipal}.
 * The password hash is erased after authentication, so the session never holds it.
 */
public final class OwnerPrincipal implements UserDetails, CredentialsContainer {

    public static final String ROLE = "OWNER";

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID id;
    private final String email;
    private final String displayName;
    private String passwordHash;

    public OwnerPrincipal(UUID id, String email, String displayName, String passwordHash) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = Objects.requireNonNull(email, "email");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.passwordHash = passwordHash;
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
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
        return email;
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
        return "OwnerPrincipal[id=" + id + "]";
    }
}
