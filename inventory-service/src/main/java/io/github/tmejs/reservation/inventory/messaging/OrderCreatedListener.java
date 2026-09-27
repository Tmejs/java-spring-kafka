package io.github.tmejs.reservation.inventory.messaging;

import io.github.tmejs.reservation.events.EventCodec;
import io.github.tmejs.reservation.inventory.reservation.ReservationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderCreatedListener {
    private final EventCodec codec;
    private final ReservationService reservations;

    public OrderCreatedListener(EventCodec codec, ReservationService reservations) {
        this.codec = codec;
        this.reservations = reservations;
    }

    @KafkaListener(
            topics = "${reservation.kafka.orders-topic:orders.v1}",
            groupId = "${reservation.kafka.consumer-group:inventory-reservations}")
    public void onOrderCreated(String payload) {
        reservations.reserve(codec.decodeOrderCreated(payload));
    }
}
