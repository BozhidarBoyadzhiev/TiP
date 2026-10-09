package dev.tip.owners;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OwnerRepository extends JpaRepository<Owner, UUID> {

    Optional<Owner> findByEmail(String email);
}
