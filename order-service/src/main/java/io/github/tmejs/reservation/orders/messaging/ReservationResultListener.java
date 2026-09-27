package io.github.tmejs.reservation.orders.messaging;

import io.github.tmejs.reservation.events.EventCodec;
import io.github.tmejs.reservation.events.StockRejected;
import io.github.tmejs.reservation.events.StockReserved;
import io.github.tmejs.reservation.orders.order.OrderOutcomeService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ReservationResultListener {
    private final EventCodec codec;
    private final OrderOutcomeService outcomes;

    public ReservationResultListener(EventCodec codec, OrderOutcomeService outcomes) {
        this.codec = codec;
        this.outcomes = outcomes;
    }

    @KafkaListener(
            topics = "${reservation.kafka.results-topic:reservation-results.v1}",
            groupId = "${reservation.kafka.result-consumer-group:order-outcomes}")
    public void onReservationResult(String payload) {
        Object result = codec.decodeReservationResult(payload);
        switch (result) {
            case StockReserved reserved -> outcomes.confirm(reserved);
            case StockRejected rejected -> outcomes.reject(rejected);
            default -> throw new IllegalArgumentException("Unsupported reservation result");
        }
    }
}
