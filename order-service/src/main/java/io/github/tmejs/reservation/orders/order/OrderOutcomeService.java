package io.github.tmejs.reservation.orders.order;

import io.github.tmejs.reservation.events.EventCodec;
import io.github.tmejs.reservation.events.EventMetadata;
import io.github.tmejs.reservation.events.StockRejected;
import io.github.tmejs.reservation.events.StockReserved;
import io.github.tmejs.reservation.orders.messaging.OutboxRepository;
import io.github.tmejs.reservation.orders.messaging.ProcessedEventRepository;
import io.github.tmejs.reservation.orders.observability.BusinessMetrics;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderOutcomeService {
    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final ProcessedEventRepository processedEvents;
    private final Clock clock;
    private final BusinessMetrics metrics;

    public OrderOutcomeService(
            OrderRepository orders,
            OutboxRepository outbox,
            ProcessedEventRepository processedEvents,
            Clock clock,
            BusinessMetrics metrics) {
        this.orders = orders;
        this.outbox = outbox;
        this.processedEvents = processedEvents;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional
    public void confirm(StockReserved event) {
        validate(event == null ? null : event.metadata(), EventCodec.STOCK_RESERVED, event == null ? null : event.causationId());
        apply(event.metadata(), event.causationId(), null);
    }

    @Transactional
    public void reject(StockRejected event) {
        validate(event == null ? null : event.metadata(), EventCodec.STOCK_REJECTED, event == null ? null : event.causationId());
        if (!"UNKNOWN_PRODUCT".equals(event.reason()) && !"INSUFFICIENT_STOCK".equals(event.reason())) {
            throw new IllegalArgumentException("Unsupported rejection reason");
        }
        apply(event.metadata(), event.causationId(), event.reason());
    }

    private void apply(EventMetadata metadata, UUID causationId, String rejectionReason) {
        OrderEntity order = orders.findByIdForOutcome(metadata.orderId())
                .orElseThrow(() -> new IllegalStateException("Reservation result references an unknown order"));
        UUID createdEventId = outbox.findEventIdByOrderAndType(metadata.orderId(), EventCodec.ORDER_CREATED)
                .orElseThrow(() -> new IllegalStateException("Order has no persisted creation event"));
        if (!createdEventId.equals(causationId)) {
            throw new IllegalArgumentException("Reservation result has the wrong causation identifier");
        }
        String outcome = rejectionReason == null ? "CONFIRMED" : "REJECTED";
        order.verifyOutcome(outcome, rejectionReason);
        if (!processedEvents.tryClaim(metadata.eventId(), clock.instant())) {
            metrics.duplicateAfterCommit(metadata.eventType());
            return;
        }
        if (rejectionReason == null) {
            order.confirm(metadata.occurredAt());
        } else {
            order.reject(rejectionReason, metadata.occurredAt());
        }
        metrics.reservationOutcomeAfterCommit(outcome.toLowerCase(java.util.Locale.ROOT),
                rejectionReason == null ? "none" : rejectionReason.toLowerCase(java.util.Locale.ROOT));
    }

    private static void validate(EventMetadata metadata, String expectedType, UUID causationId) {
        if (metadata == null || metadata.eventId() == null || metadata.occurredAt() == null
                || metadata.orderId() == null || causationId == null) {
            throw new IllegalArgumentException("Reservation result metadata and causation are required");
        }
        if (!expectedType.equals(metadata.eventType()) || metadata.schemaVersion() != EventCodec.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported reservation result contract");
        }
    }
}
