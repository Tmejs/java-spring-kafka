package io.github.tmejs.reservation.orders.observability;

import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;

public final class CorrelationContext {
    private CorrelationContext() {}

    public static void withMessageIds(UUID orderId, UUID eventId, Runnable action) {
        withMessageIds(orderId, eventId, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T withMessageIds(UUID orderId, UUID eventId, Supplier<T> action) {
        try (var order = MDC.putCloseable("orderId", orderId.toString());
                var event = MDC.putCloseable("eventId", eventId.toString())) {
            return action.get();
        }
    }
}
