package io.github.tmejs.reservation.orders.observability;

import java.util.UUID;
import org.slf4j.MDC;

public final class CorrelationContext {
    private CorrelationContext() {}

    public static void withMessageIds(UUID orderId, UUID eventId, Runnable action) {
        try (var order = MDC.putCloseable("orderId", orderId.toString());
                var event = MDC.putCloseable("eventId", eventId.toString())) {
            action.run();
        }
    }
}
