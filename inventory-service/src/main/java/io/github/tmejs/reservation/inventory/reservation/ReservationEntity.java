package io.github.tmejs.reservation.inventory.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class ReservationEntity {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "triggering_event_id", nullable = false)
    private UUID triggeringEventId;

    @Column(name = "items_fingerprint", nullable = false, length = 64)
    private String itemsFingerprint;

    @Column(nullable = false, length = 32)
    private String outcome;

    @Column(name = "rejection_reason", length = 64)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReservationEntity() {}
}
