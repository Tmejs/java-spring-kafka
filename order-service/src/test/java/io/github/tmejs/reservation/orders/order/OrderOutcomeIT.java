package io.github.tmejs.reservation.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tmejs.reservation.events.EventCodec;
import io.github.tmejs.reservation.events.EventMetadata;
import io.github.tmejs.reservation.events.StockRejected;
import io.github.tmejs.reservation.events.StockReserved;
import io.github.tmejs.reservation.orders.OrderApplication;
import io.github.tmejs.reservation.orders.support.OrderPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = OrderApplication.class, properties = "reservation.outbox.scheduling-enabled=false")
@Import(OrderOutcomeIT.FixedClockConfiguration.class)
class OrderOutcomeIT extends OrderPostgresIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-02-03T04:05:06Z");
    private static final Instant PROCESSED_AT = Instant.parse("2026-02-03T05:06:07Z");

    @Autowired private OrderOutcomeService outcomes;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearData() {
        jdbc.execute("truncate table order_items, idempotency_keys, outbox, processed_events, orders cascade");
    }

    @Test
    void confirmsPendingOrderAndClaimsResultAtomically() {
        Source source = pendingOrder();

        outcomes.confirm(reserved(source, UUID.randomUUID()));

        assertThat(status(source.orderId())).isEqualTo("CONFIRMED");
        assertThat(reason(source.orderId())).isNull();
        assertThat(processedEventCount()).isOne();
        assertThat(processedAt()).isEqualTo(PROCESSED_AT);
    }

    @Test
    void rejectsPendingOrderWithBusinessReason() {
        Source source = pendingOrder();

        outcomes.reject(rejected(source, UUID.randomUUID(), "INSUFFICIENT_STOCK"));

        assertThat(status(source.orderId())).isEqualTo("REJECTED");
        assertThat(reason(source.orderId())).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(processedEventCount()).isOne();
    }

    @Test
    void exactRedeliveryAfterCommittedOutcomeIsANoOp() {
        Source source = pendingOrder();
        UUID resultId = UUID.randomUUID();
        StockReserved event = reserved(source, resultId);

        outcomes.confirm(event);
        outcomes.confirm(event);

        assertThat(status(source.orderId())).isEqualTo("CONFIRMED");
        assertThat(processedEventCount()).isOne();
    }

    @Test
    void newEventIdWithSameTerminalOutcomeIsRecordedWithoutAnotherTransition() {
        Source source = pendingOrder();

        outcomes.confirm(reserved(source, UUID.randomUUID()));
        outcomes.confirm(reserved(source, UUID.randomUUID()));

        assertThat(status(source.orderId())).isEqualTo("CONFIRMED");
        assertThat(processedEventCount()).isEqualTo(2);
    }

    @Test
    void contradictoryTerminalOutcomeRollsBackItsEventClaim() {
        Source source = pendingOrder();
        outcomes.confirm(reserved(source, UUID.randomUUID()));

        assertThatThrownBy(() -> outcomes.reject(rejected(source, UUID.randomUUID(), "UNKNOWN_PRODUCT")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(status(source.orderId())).isEqualTo("CONFIRMED");
        assertThat(reason(source.orderId())).isNull();
        assertThat(processedEventCount()).isOne();
    }

    @Test
    void reusedEventIdCannotHideAContradictoryTerminalOutcome() {
        Source source = pendingOrder();
        UUID resultId = UUID.randomUUID();
        outcomes.confirm(reserved(source, resultId));

        assertThatThrownBy(() -> outcomes.reject(rejected(source, resultId, "UNKNOWN_PRODUCT")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(status(source.orderId())).isEqualTo("CONFIRMED");
        assertThat(processedEventCount()).isOne();
    }

    @Test
    void differentRejectionReasonForTerminalOrderRollsBackItsEventClaim() {
        Source source = pendingOrder();
        outcomes.reject(rejected(source, UUID.randomUUID(), "INSUFFICIENT_STOCK"));

        assertThatThrownBy(() -> outcomes.reject(rejected(source, UUID.randomUUID(), "UNKNOWN_PRODUCT")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(status(source.orderId())).isEqualTo("REJECTED");
        assertThat(reason(source.orderId())).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(processedEventCount()).isOne();
    }

    @Test
    void unknownOrderAndWrongCausationAreTechnicalFailuresWithoutClaims() {
        Source source = pendingOrder();
        StockReserved unknown = new StockReserved(
                metadata(UUID.randomUUID(), UUID.randomUUID(), EventCodec.STOCK_RESERVED), source.createdEventId());
        StockReserved wrongCause = new StockReserved(
                metadata(UUID.randomUUID(), source.orderId(), EventCodec.STOCK_RESERVED), UUID.randomUUID());

        assertThatThrownBy(() -> outcomes.confirm(unknown)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> outcomes.confirm(wrongCause)).isInstanceOf(IllegalArgumentException.class);

        assertThat(status(source.orderId())).isEqualTo("PENDING");
        assertThat(processedEventCount()).isZero();
    }

    @Test
    void invalidMetadataAndUnboundedReasonRemainTechnicalFailures() {
        Source source = pendingOrder();
        StockReserved missingTimestamp = new StockReserved(
                new EventMetadata(UUID.randomUUID(), EventCodec.STOCK_RESERVED, 1, null, source.orderId()),
                source.createdEventId());
        StockRejected longReason = rejected(source, UUID.randomUUID(), "x".repeat(65));

        assertThatThrownBy(() -> outcomes.confirm(missingTimestamp)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outcomes.reject(longReason)).isInstanceOf(IllegalArgumentException.class);

        assertThat(status(source.orderId())).isEqualTo("PENDING");
        assertThat(processedEventCount()).isZero();
    }

    @Test
    void concurrentOppositeOutcomesSerializeToOneTerminalDecision() throws Exception {
        Source source = pendingOrder();
        StockReserved reserved = reserved(source, UUID.randomUUID());
        StockRejected rejected = rejected(source, UUID.randomUUID(), "INSUFFICIENT_STOCK");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var confirmation = executor.submit(() -> applyAfterBarrier(() -> outcomes.confirm(reserved), ready, start));
            var rejection = executor.submit(() -> applyAfterBarrier(() -> outcomes.reject(rejected), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = confirmation.get(10, TimeUnit.SECONDS) + rejection.get(10, TimeUnit.SECONDS);
            assertThat(successes).isOne();
        }

        assertThat(status(source.orderId())).isIn("CONFIRMED", "REJECTED");
        assertThat(processedEventCount()).isOne();
    }

    private Source pendingOrder() {
        UUID orderId = UUID.randomUUID();
        UUID createdEventId = UUID.randomUUID();
        jdbc.update("insert into orders(id,owner_subject,status,created_at,updated_at) values (?,?, 'PENDING', ?, ?)",
                orderId, "owner", Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
        jdbc.update("insert into outbox(id,event_id,order_id,event_type,topic,payload,created_at,next_attempt_at,attempt_count) "
                        + "values (?,?,?,?,?,'{}',?,?,0)",
                UUID.randomUUID(), createdEventId, orderId, EventCodec.ORDER_CREATED, "orders.v1",
                Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
        return new Source(orderId, createdEventId);
    }

    private static StockReserved reserved(Source source, UUID eventId) {
        return new StockReserved(metadata(eventId, source.orderId(), EventCodec.STOCK_RESERVED), source.createdEventId());
    }

    private static StockRejected rejected(Source source, UUID eventId, String reason) {
        return new StockRejected(
                metadata(eventId, source.orderId(), EventCodec.STOCK_REJECTED), source.createdEventId(), reason);
    }

    private static EventMetadata metadata(UUID eventId, UUID orderId, String type) {
        return new EventMetadata(eventId, type, EventCodec.SCHEMA_VERSION, PROCESSED_AT, orderId);
    }

    private String status(UUID orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private String reason(UUID orderId) {
        return jdbc.queryForObject("select rejection_reason from orders where id = ?", String.class, orderId);
    }

    private int processedEventCount() {
        return jdbc.queryForObject("select count(*) from processed_events", Integer.class);
    }

    private Instant processedAt() {
        return jdbc.queryForObject("select processed_at from processed_events", Instant.class);
    }

    private record Source(UUID orderId, UUID createdEventId) {}

    private int applyAfterBarrier(Runnable outcome, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            outcome.run();
            return 1;
        } catch (IllegalStateException expectedConflict) {
            return 0;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock outcomeClock() {
            return Clock.fixed(PROCESSED_AT, ZoneOffset.UTC);
        }
    }
}
