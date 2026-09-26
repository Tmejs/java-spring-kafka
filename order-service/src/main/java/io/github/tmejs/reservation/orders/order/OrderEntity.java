package io.github.tmejs.reservation.orders.order;

import jakarta.persistence.Column;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    private UUID id;

    @Column(name = "owner_subject", nullable = false, length = 255)
    private String ownerSubject;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "rejection_reason", length = 64)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("productId ASC")
    private List<OrderItemEntity> items = new ArrayList<>();

    protected OrderEntity() {}

    OrderEntity(UUID id, String ownerSubject, Instant createdAt) {
        this.id = id;
        this.ownerSubject = ownerSubject;
        this.status = "PENDING";
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    void addItem(UUID itemId, UUID productId, int quantity) {
        items.add(new OrderItemEntity(itemId, this, productId, quantity));
    }

    UUID getId() {
        return id;
    }

    String getStatus() {
        return status;
    }

    String getRejectionReason() {
        return rejectionReason;
    }

    List<OrderItemEntity> getItems() {
        return Collections.unmodifiableList(items);
    }
}
