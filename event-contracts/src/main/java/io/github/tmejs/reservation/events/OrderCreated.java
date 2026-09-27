package io.github.tmejs.reservation.events;

import java.util.List;

public record OrderCreated(EventMetadata metadata, List<OrderLine> items) {

    public OrderCreated {
        items = items == null ? null : List.copyOf(items);
    }
}
