package io.github.tmejs.reservation.inventory.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class BusinessMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        registry.close();
    }

    @Test
    void recordsReservationOutcomeAndDuplicateOnlyAfterCommitWithBoundedTags() {
        var metrics = new BusinessMetrics(registry);
        TransactionSynchronizationManager.initSynchronization();

        metrics.reservationOutcomeAfterCommit("reserved", "none");
        metrics.duplicateAfterCommit("order-created");

        assertThat(registry.find("reservation.outcomes").counter()).isNull();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        assertThat(registry.counter("reservation.outcomes", "outcome", "reserved", "reason", "none").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("reservation.events.duplicates", "event.type", "order-created").count())
                .isEqualTo(1.0);
        assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags().stream()
                        .map(io.micrometer.core.instrument.Tag::getKey).toList())
                .doesNotContain("orderId", "eventId", "productId");
    }
}
