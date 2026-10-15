package dev.tip.tipping;

import java.time.Instant;
import java.util.List;
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

    /**
     * PayPal confirmed the capture, so the money moved: recorded even when the tip was marked failed or abandoned
     * meanwhile, for example after a lost capture answer and a webhook that came hours later.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "UPDATE tip SET status = 'CAPTURED', paypal_capture_id = :captureId, captured_at = :capturedAt"
                    + " WHERE id = :id AND status IN ('CREATED', 'PENDING', 'FAILED', 'ABANDONED')",
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

    /** A pending capture PayPal refused in the end. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE tip SET status = 'FAILED' WHERE id = :id AND status = 'PENDING'", nativeQuery = true)
    int markPendingFailed(UUID id);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE tip SET status = 'REFUNDED' WHERE id = :id AND status = 'CAPTURED'", nativeQuery = true)
    int markRefunded(UUID id);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE tip SET status = 'REVERSED' WHERE id = :id AND status = 'CAPTURED'", nativeQuery = true)
    int markReversed(UUID id);

    /** The first time TiP asks PayPal to capture the tip's order; later attempts keep the first time. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "UPDATE tip SET capture_attempted_at = :at WHERE id = :id AND capture_attempted_at IS NULL",
            nativeQuery = true)
    int markCaptureAttempted(UUID id, Instant at);

    /** CREATED tips older than {@code cutoff} whose capture was never attempted: their orders were never approved. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "UPDATE tip SET status = 'ABANDONED'"
                    + " WHERE status = 'CREATED' AND created_at < :cutoff AND capture_attempted_at IS NULL",
            nativeQuery = true)
    int abandonUnattemptedCreatedBefore(Instant cutoff);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE tip SET status = 'ABANDONED' WHERE id = :id AND status = 'CREATED'", nativeQuery = true)
    int markAbandoned(UUID id);

    /** CREATED tips older than {@code cutoff} whose capture was attempted, oldest first. */
    @Query(
            value = "SELECT * FROM tip WHERE status = 'CREATED' AND created_at < :cutoff"
                    + " AND capture_attempted_at IS NOT NULL AND paypal_order_id IS NOT NULL"
                    + " ORDER BY created_at LIMIT :limit",
            nativeQuery = true)
    List<Tip> findAttemptedCreatedBefore(Instant cutoff, int limit);

    /** PENDING tips created before {@code cutoff}, oldest first. */
    @Query(
            value = "SELECT * FROM tip WHERE status = 'PENDING' AND created_at < :cutoff"
                    + " AND paypal_order_id IS NOT NULL ORDER BY created_at LIMIT :limit",
            nativeQuery = true)
    List<Tip> findPendingCreatedBefore(Instant cutoff, int limit);

    @Query("SELECT t.status FROM Tip t WHERE t.id = :id")
    Optional<TipStatus> findStatus(UUID id);

    @Query("SELECT t.deviceId FROM Tip t WHERE t.id = :id")
    Optional<UUID> findDeviceId(UUID id);

    @Query("SELECT t.id FROM Tip t WHERE t.paypalOrderId = :orderId")
    Optional<UUID> findIdByPaypalOrderId(String orderId);

    @Query("SELECT t.id FROM Tip t WHERE t.paypalCaptureId = :captureId")
    Optional<UUID> findIdByPaypalCaptureId(String captureId);
}
