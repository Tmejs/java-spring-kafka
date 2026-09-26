package io.github.tmejs.reservation.orders.messaging;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {}
