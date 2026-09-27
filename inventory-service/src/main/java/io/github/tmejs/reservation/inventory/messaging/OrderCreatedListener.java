package io.github.tmejs.reservation.inventory.messaging;

import io.github.tmejs.reservation.events.EventCodec;
import io.github.tmejs.reservation.inventory.reservation.ReservationService;
import io.github.tmejs.reservation.inventory.observability.BusinessMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import io.github.tmejs.reservation.inventory.observability.CorrelationContext;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderCreatedListener {
    private final EventCodec codec;
    private final ReservationService reservations;
    private final BusinessMetrics metrics;

    public OrderCreatedListener(EventCodec codec, ReservationService reservations, BusinessMetrics metrics) {
        this.codec = codec;
        this.reservations = reservations;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = "${reservation.kafka.orders-topic:orders.v1}",
            groupId = "${reservation.kafka.consumer-group:inventory-reservations}")
    public void onOrderCreated(ConsumerRecord<String, String> record) {
        var order = codec.decodeOrderCreated(record.value());
        CorrelationContext.withMessageIds(order.metadata().orderId(), order.metadata().eventId(),
                () -> metrics.recordReservation(() -> reservations.reserve(order)));
    }
}
