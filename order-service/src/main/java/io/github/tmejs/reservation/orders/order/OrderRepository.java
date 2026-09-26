package io.github.tmejs.reservation.orders.order;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    @EntityGraph(attributePaths = "items")
    Optional<OrderEntity> findByIdAndOwnerSubject(UUID id, String ownerSubject);
}
