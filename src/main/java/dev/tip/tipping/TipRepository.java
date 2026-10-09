package dev.tip.tipping;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * Status changes are conditional updates: the caller that gets one affected row owns the transition.
 * PostgreSQL row locking makes a concurrent caller wait and then re-check the condition.
 */
interface TipRepository extends JpaRepository<Tip, UUID> {

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "UPDATE tip SET paypal_order_id = :orderId"
                    + " WHERE id = :id AND status = 'CREATED' AND paypal_order_id IS NULL",
            nativeQuery = true)
    int attachOrder(UUID id, String orderId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "UPDATE tip SET status = 'CAPTURED', paypal_capture_id = :captureId, captured_at = :capturedAt"
                    + " WHERE id = :id AND status IN ('CREATED', 'PENDING')",
            nativeQuery = true)
    int markCaptured(UUID id, String captureId, Instant capturedAt);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "UPDATE tip SET status = 'PENDING', paypal_capture_id = :captureId"
                    + " WHERE id = :id AND status = 'CREATED'",
            nativeQuery = true)
    int markPending(UUID id, String captureId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "UPDATE tip SET status = 'FAILED' WHERE id = :id AND status IN ('CREATED', 'PENDING')",
            nativeQuery = true)
    int markFailed(UUID id);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE tip SET status = 'REFUNDED' WHERE id = :id AND status = 'CAPTURED'", nativeQuery = true)
    int markRefunded(UUID id);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "UPDATE tip SET status = 'ABANDONED' WHERE status = 'CREATED' AND created_at < :cutoff",
            nativeQuery = true)
    int abandonCreatedBefore(Instant cutoff);

    @Query("SELECT t.status FROM Tip t WHERE t.id = :id")
    Optional<TipStatus> findStatus(UUID id);

    @Query("SELECT t.deviceId FROM Tip t WHERE t.id = :id")
    Optional<UUID> findDeviceId(UUID id);

    @Query("SELECT t.id FROM Tip t WHERE t.paypalOrderId = :orderId")
    Optional<UUID> findIdByPaypalOrderId(String orderId);

    @Query("SELECT t.id FROM Tip t WHERE t.paypalCaptureId = :captureId")
    Optional<UUID> findIdByPaypalCaptureId(String captureId);
}
