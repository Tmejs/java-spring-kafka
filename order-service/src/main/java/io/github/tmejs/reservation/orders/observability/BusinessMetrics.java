package io.github.tmejs.reservation.orders.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class BusinessMetrics {
    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void orderCreatedAfterCommit() {
        afterCommit(() -> registry.counter("reservation.orders.created").increment());
    }

    public void reservationOutcomeAfterCommit(String outcome, String reason) {
        afterCommit(() -> registry.counter(
                        "reservation.outcomes", "outcome", outcome, "reason", reason)
                .increment());
    }

    public void duplicateAfterCommit(String eventType) {
        afterCommit(() -> registry.counter("reservation.events.duplicates", "event.type", eventType).increment());
    }

    public void outboxPublishFailure(String eventType) {
        registry.counter("reservation.outbox.publish.failures", "event.type", eventType).increment();
    }

    public void deadLetterOutcome(String outcome, String source) {
        registry.counter("reservation.dlt.publications", "outcome", outcome, "source", source).increment();
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Business metric registration requires an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
