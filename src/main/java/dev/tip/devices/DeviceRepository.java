package dev.tip.devices;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

interface DeviceRepository extends JpaRepository<Device, UUID> {

    Optional<Device> findBySlug(String slug);

    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);

    List<Device> findByOwnerIdOrderBySlug(UUID ownerId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE device SET last_seen_at = :seenAt WHERE id = :id", nativeQuery = true)
    int markSeen(UUID id, Instant seenAt);
}
