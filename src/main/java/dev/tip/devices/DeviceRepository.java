package dev.tip.devices;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DeviceRepository extends JpaRepository<Device, UUID> {

    Optional<Device> findBySlug(String slug);

    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);
}
