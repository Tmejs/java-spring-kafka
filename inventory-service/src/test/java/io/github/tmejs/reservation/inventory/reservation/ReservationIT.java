package io.github.tmejs.reservation.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.tmejs.reservation.events.EventCodec;
import io.github.tmejs.reservation.events.EventMetadata;
import io.github.tmejs.reservation.events.OrderCreated;
import io.github.tmejs.reservation.events.OrderLine;
import io.github.tmejs.reservation.events.StockRejected;
import io.github.tmejs.reservation.events.StockReserved;
import io.github.tmejs.reservation.inventory.InventoryApplication;
import io.github.tmejs.reservation.inventory.support.InventoryPostgresIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(classes = InventoryApplication.class, properties = {
        "reservation.outbox.scheduling-enabled=false",
        "logging.level.org.apache.kafka=WARN"})
@Import(ReservationIT.FixedClockConfiguration.class)
class ReservationIT extends InventoryPostgresIntegrationTest {
    private static final String ORDERS_TOPIC = "orders.v1";
    private static final String CONSUMER_GROUP = "inventory-reservations";
    private static final Instant DECIDED_AT = Instant.parse("2026-02-03T04:05:06Z");
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:4.1.1");

    static { KAFKA.start(); }

    @Autowired private ReservationService reservations;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private EventCodec codec;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void clearData() {
        jdbc.execute("truncate table outbox, processed_events, reservations, products cascade");
    }

    @Test
    void reservesEveryItemAndWritesOneCommittedResult() {
        UUID first = product(5);
        UUID second = product(9);
        OrderCreated event = event(UUID.randomUUID(), UUID.randomUUID(),
                List.of(new OrderLine(second, 4), new OrderLine(first, 2)));

        reservations.reserve(event);

        assertThat(stock(first)).isEqualTo(3);
        assertThat(stock(second)).isEqualTo(5);
        assertThat(count("reservations")).isOne();
        assertThat(count("processed_events")).isOne();
        assertThat(count("outbox")).isOne();
        StockReserved result = codec.decodeStockReserved(outboxPayload());
        assertThat(result.causationId()).isEqualTo(event.metadata().eventId());
        assertThat(result.metadata().orderId()).isEqualTo(event.metadata().orderId());
        assertThat(result.metadata().occurredAt()).isEqualTo(DECIDED_AT);
    }

    @Test
    void rejectsUnknownProductWithoutChangingKnownStock() {
        UUID known = product(6);
        OrderCreated event = event(UUID.randomUUID(), UUID.randomUUID(),
                List.of(new OrderLine(known, 2), new OrderLine(UUID.randomUUID(), 1)));

        reservations.reserve(event);

        assertThat(stock(known)).isEqualTo(6);
        assertRejected(event, "UNKNOWN_PRODUCT");
    }

    @Test
    void rejectsAnyShortageWithoutPartiallyReservingOtherItems() {
        UUID enough = product(8);
        UUID shortProduct = product(1);
        OrderCreated event = event(UUID.randomUUID(), UUID.randomUUID(),
                List.of(new OrderLine(enough, 3), new OrderLine(shortProduct, 2)));

        reservations.reserve(event);

        assertThat(stock(enough)).isEqualTo(8);
        assertThat(stock(shortProduct)).isOne();
        assertRejected(event, "INSUFFICIENT_STOCK");
    }

    @Test
    void identicalEventRedeliveryDoesNotChangeStockOrCreateAnotherResult() {
        UUID product = product(5);
        OrderCreated event = event(UUID.randomUUID(), UUID.randomUUID(), List.of(new OrderLine(product, 2)));

        reservations.reserve(event);
        reservations.reserve(event);

        assertThat(stock(product)).isEqualTo(3);
        assertThat(count("reservations")).isOne();
        assertThat(count("processed_events")).isOne();
        assertThat(count("outbox")).isOne();
    }

    @Test
    void newEventIdForTheSameOrderRecordsDeliveryWithoutRepeatingDecision() {
        UUID firstProduct = product(5);
        UUID secondProduct = product(7);
        UUID orderId = UUID.randomUUID();
        OrderCreated first = event(UUID.randomUUID(), orderId,
                List.of(new OrderLine(firstProduct, 2), new OrderLine(secondProduct, 3)));
        OrderCreated redelivery = event(UUID.randomUUID(), orderId,
                List.of(new OrderLine(secondProduct, 3), new OrderLine(firstProduct, 2)));

        reservations.reserve(first);
        reservations.reserve(redelivery);

        assertThat(stock(firstProduct)).isEqualTo(3);
        assertThat(stock(secondProduct)).isEqualTo(4);
        assertThat(count("reservations")).isOne();
        assertThat(count("processed_events")).isEqualTo(2);
        assertThat(count("outbox")).isOne();
    }

    @Test
    void concurrentEventsForTheSameOrderSerializeIntoOneDecision() throws Exception {
        UUID product = product(5);
        UUID orderId = UUID.randomUUID();
        OrderCreated first = event(UUID.randomUUID(), orderId, List.of(new OrderLine(product, 2)));
        OrderCreated second = event(UUID.randomUUID(), orderId, List.of(new OrderLine(product, 2)));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> reserveAfterBarrier(first, ready, start));
            var two = executor.submit(() -> reserveAfterBarrier(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);
        }

        assertThat(stock(product)).isEqualTo(3);
        assertThat(count("reservations")).isOne();
        assertThat(count("processed_events")).isEqualTo(2);
        assertThat(count("outbox")).isOne();
    }

    @Test
    void repeatedProductInOneEventIsAContractErrorWithNoWrites() {
        UUID product = product(5);
        OrderCreated inconsistent = event(UUID.randomUUID(), UUID.randomUUID(),
                List.of(new OrderLine(product, 1), new OrderLine(product, 1)));

        assertThatThrownBy(() -> reservations.reserve(inconsistent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct");

        assertThat(stock(product)).isEqualTo(5);
        assertThat(count("reservations")).isZero();
        assertThat(count("processed_events")).isZero();
        assertThat(count("outbox")).isZero();
    }

    @Test
    void missingOccurredAtIsAContractErrorWithNoWrites() {
        UUID product = product(5);
        OrderCreated malformed = new OrderCreated(
                new EventMetadata(
                        UUID.randomUUID(), EventCodec.ORDER_CREATED, EventCodec.SCHEMA_VERSION, null, UUID.randomUUID()),
                List.of(new OrderLine(product, 2)));

        assertThatThrownBy(() -> reservations.reserve(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata");

        assertThat(stock(product)).isEqualTo(5);
        assertThat(count("reservations")).isZero();
        assertThat(count("processed_events")).isZero();
        assertThat(count("outbox")).isZero();
    }

    @Test
    void changedItemsForAnExistingOrderAreAContractErrorAndRollBackEventClaim() {
        UUID product = product(5);
        UUID orderId = UUID.randomUUID();
        reservations.reserve(event(UUID.randomUUID(), orderId, List.of(new OrderLine(product, 2))));

        assertThatThrownBy(() -> reservations.reserve(
                        event(UUID.randomUUID(), orderId, List.of(new OrderLine(product, 3)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fingerprint");

        assertThat(stock(product)).isEqualTo(3);
        assertThat(count("reservations")).isOne();
        assertThat(count("processed_events")).isOne();
        assertThat(count("outbox")).isOne();
    }

    @Test
    void competingOrdersProduceOneReservationAndOneRejection() throws Exception {
        UUID product = product(5);
        OrderCreated first = event(UUID.randomUUID(), UUID.randomUUID(), List.of(new OrderLine(product, 5)));
        OrderCreated second = event(UUID.randomUUID(), UUID.randomUUID(), List.of(new OrderLine(product, 5)));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> reserveAfterBarrier(first, ready, start));
            var two = executor.submit(() -> reserveAfterBarrier(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from reservations where outcome = 'RESERVED'", Integer.class)).isOne();
        assertThat(stock(product)).isZero();
        assertThat(count("outbox")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from reservations where outcome = 'REJECTED'", Integer.class)).isOne();
    }

    @Test
    void technicalOutboxFailureRollsBackStockDecisionAndEventClaim() {
        UUID product = product(5);
        OrderCreated event = event(UUID.randomUUID(), UUID.randomUUID(), List.of(new OrderLine(product, 2)));
        jdbc.execute("create function reject_test_outbox() returns trigger language plpgsql as $$ "
                + "begin raise exception 'test outbox failure'; end $$");
        jdbc.execute("create trigger reject_test_outbox before insert on outbox "
                + "for each row execute function reject_test_outbox()");
        try {
            assertThatThrownBy(() -> reservations.reserve(event)).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("drop trigger reject_test_outbox on outbox");
            jdbc.execute("drop function reject_test_outbox()");
        }

        assertThat(stock(product)).isEqualTo(5);
        assertThat(count("reservations")).isZero();
        assertThat(count("processed_events")).isZero();
        assertThat(count("outbox")).isZero();
    }

    @Test
    void kafkaListenerDelegatesThroughCommittedTransactionAndAcknowledgesRecord() throws Exception {
        UUID product = product(4);
        OrderCreated event = event(UUID.randomUUID(), UUID.randomUUID(), List.of(new OrderLine(product, 3)));

        kafka.send(new ProducerRecord<>(ORDERS_TOPIC, event.metadata().orderId().toString(), codec.encode(event)))
                .get(10, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(stock(product)).isOne();
            assertThat(count("reservations")).isOne();
            assertThat(count("processed_events")).isOne();
            assertThat(count("outbox")).isOne();
        });
        kafka.send(new ProducerRecord<>(ORDERS_TOPIC, event.metadata().orderId().toString(), codec.encode(event)))
                .get(10, TimeUnit.SECONDS);
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(admin.listConsumerGroupOffsets(CONSUMER_GROUP)
                                    .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).values())
                            .anyMatch(offset -> offset.offset() >= 2));
        }
        assertThat(stock(product)).isOne();
        assertThat(count("reservations")).isOne();
        assertThat(count("processed_events")).isOne();
        assertThat(count("outbox")).isOne();
    }

    private Void reserveAfterBarrier(OrderCreated event, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        reservations.reserve(event);
        return null;
    }

    private void assertRejected(OrderCreated source, String expectedReason) {
        assertThat(count("reservations")).isOne();
        assertThat(count("processed_events")).isOne();
        assertThat(count("outbox")).isOne();
        StockRejected result = codec.decodeStockRejected(outboxPayload());
        assertThat(result.reason()).isEqualTo(expectedReason);
        assertThat(result.causationId()).isEqualTo(source.metadata().eventId());
        assertThat(result.metadata().occurredAt()).isEqualTo(DECIDED_AT);
    }

    private UUID product(int quantity) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into products(id, name, available_quantity) values (?, ?, ?)", id, "fixture", quantity);
        return id;
    }

    private int stock(UUID productId) {
        return jdbc.queryForObject(
                "select available_quantity from products where id = ?", Integer.class, productId);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private String outboxPayload() {
        return jdbc.queryForObject("select payload from outbox", String.class);
    }

    private static OrderCreated event(UUID eventId, UUID orderId, List<OrderLine> lines) {
        return new OrderCreated(
                new EventMetadata(eventId, EventCodec.ORDER_CREATED, EventCodec.SCHEMA_VERSION,
                        Instant.parse("2026-01-01T00:00:00Z"), orderId),
                lines);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedReservationClock() {
            return Clock.fixed(DECIDED_AT, ZoneOffset.UTC);
        }
    }
}
