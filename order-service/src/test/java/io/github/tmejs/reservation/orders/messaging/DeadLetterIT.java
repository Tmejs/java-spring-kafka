package io.github.tmejs.reservation.orders.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.tmejs.reservation.events.EventCodec;
import io.github.tmejs.reservation.events.EventMetadata;
import io.github.tmejs.reservation.events.StockReserved;
import io.github.tmejs.reservation.orders.OrderApplication;
import io.github.tmejs.reservation.orders.support.OrderPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(classes = OrderApplication.class, properties = {
        "reservation.outbox.scheduling-enabled=false", "reservation.kafka.dlt-ack-timeout=2s",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.kafka.producer.properties.delivery.timeout.ms=2000",
        "logging.level.org.apache.kafka=WARN"})
@Import(DeadLetterIT.RetryProbeConfiguration.class)
class DeadLetterIT extends OrderPostgresIntegrationTest {
    private static final String SOURCE = "reservation-results.task7.orders";
    private static final String DLT = SOURCE + ".DLT";
    private static final String GROUP = "order-outcomes-task7";
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:4.1.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    static {
        KAFKA.start();
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(SOURCE, 1, (short) 1), new NewTopic(DLT, 1, (short) 1)))
                    .all().get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EventCodec codec;
    @Autowired private RetryProbe retryProbe;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("reservation.kafka.results-topic", () -> SOURCE);
        registry.add("reservation.kafka.result-consumer-group", () -> GROUP);
    }

    @BeforeEach
    void clearData() {
        ensureTopic(DLT);
        retryProbe.clear();
        jdbc.execute("truncate table order_items, idempotency_keys, outbox, processed_events, orders cascade");
    }

    @Test
    void retriesMalformedJsonFourTotalDeliveriesThenPublishesOriginalToDltAndCommitsOffset() throws Exception {
        String key = UUID.randomUUID().toString();
        String payload = "{not-json";
        var sent = kafka.send(new ProducerRecord<>(SOURCE, key, payload)).get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, String> dead = consumeMatching(DLT, key, Duration.ofSeconds(12));

        assertThat(dead.key()).isEqualTo(key);
        assertThat(dead.value()).isEqualTo(payload);
        assertRetrySchedule(key);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(committedOffset()).isGreaterThanOrEqualTo(sent.getRecordMetadata().offset() + 1));
        assertThat(jdbc.queryForObject("select count(*) from processed_events", Integer.class)).isZero();
    }

    @Test
    void unsupportedVersionIsTechnicalAndNeverRejectsOrder() throws Exception {
        Source source = pendingOrder();
        String key = source.orderId().toString();
        String payload = codec.encode(new StockReserved(
                new EventMetadata(UUID.randomUUID(), EventCodec.STOCK_RESERVED, 2, Instant.now(), source.orderId()),
                source.createdEventId()));

        kafka.send(new ProducerRecord<>(SOURCE, key, payload)).get(10, TimeUnit.SECONDS);

        assertThat(consumeMatching(DLT, key, Duration.ofSeconds(12)).value()).isEqualTo(payload);
        assertRetrySchedule(key);
        assertThat(status(source.orderId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from processed_events", Integer.class)).isZero();
    }

    @Test
    void transientUnknownOrderRecoversOnRetryWithoutDeadLettering() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID createdEventId = UUID.randomUUID();
        String key = orderId.toString();
        String payload = codec.encode(new StockReserved(
                new EventMetadata(UUID.randomUUID(), EventCodec.STOCK_RESERVED, 1, Instant.now(), orderId),
                createdEventId));

        kafka.send(new ProducerRecord<>(SOURCE, key, payload)).get(10, TimeUnit.SECONDS);
        try (var executor = Executors.newSingleThreadScheduledExecutor()) {
            executor.schedule(() -> insertPendingOrder(orderId, createdEventId), 500, TimeUnit.MILLISECONDS);
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("select count(*) from orders where id = ?", Integer.class, orderId))
                        .isOne();
                assertThat(status(orderId)).isEqualTo("CONFIRMED");
            });
        }

        assertThat(retryProbe.attempts(key)).hasSizeBetween(1, 2);
        assertThat(consumeOptional(DLT, key, Duration.ofSeconds(1))).isNull();
        assertThat(jdbc.queryForObject("select count(*) from processed_events", Integer.class)).isOne();
    }

    @Test
    void failedDltPublicationDoesNotAdvanceSourceOffset() throws Exception {
        deleteTopic(DLT);
        String key = UUID.randomUUID().toString();
        var sent = kafka.send(new ProducerRecord<>(SOURCE, key, "{still-bad"))
                .get(10, TimeUnit.SECONDS);

        try {
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(retryProbe.attempts(key)).hasSizeGreaterThanOrEqualTo(5);
                assertThat(retryProbe.recoveryFailures()).isPositive();
            });
            assertThat(committedOffset()).isLessThan(sent.getRecordMetadata().offset() + 1);
        } finally {
            ensureTopic(DLT);
        }
        assertThat(consumeMatching(DLT, key, Duration.ofSeconds(12)).value()).isEqualTo("{still-bad");
    }

    @Test
    void consumesResultThroughCommittedOutcomeTransaction() throws Exception {
        Source source = pendingOrder();
        var result = new StockReserved(
                new EventMetadata(UUID.randomUUID(), EventCodec.STOCK_RESERVED, 1, Instant.now(), source.orderId()),
                source.createdEventId());

        kafka.send(new ProducerRecord<>(SOURCE, source.orderId().toString(), codec.encode(result)))
                .get(10, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(status(source.orderId())).isEqualTo("CONFIRMED");
            assertThat(jdbc.queryForObject("select count(*) from processed_events", Integer.class)).isOne();
        });
    }

    private Source pendingOrder() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertPendingOrder(orderId, eventId);
        return new Source(orderId, eventId);
    }

    private void insertPendingOrder(UUID orderId, UUID eventId) {
        Instant now = Instant.now();
        jdbc.update("insert into orders(id,owner_subject,status,created_at,updated_at) values (?,?, 'PENDING', ?, ?)",
                orderId, "owner", Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into outbox(id,event_id,order_id,event_type,topic,payload,created_at,next_attempt_at,attempt_count) "
                        + "values (?,?,?,?,?,'{}',?,?,0)", UUID.randomUUID(), eventId, orderId,
                EventCodec.ORDER_CREATED, "orders.v1", Timestamp.from(now), Timestamp.from(now));
    }

    private String status(UUID orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private record Source(UUID orderId, UUID createdEventId) {}

    private void assertRetrySchedule(String key) {
        List<Instant> attempts = retryProbe.attempts(key);
        assertThat(attempts).hasSize(4);
        for (int index = 1; index < attempts.size(); index++) {
            assertThat(Duration.between(attempts.get(index - 1), attempts.get(index)))
                    .isGreaterThanOrEqualTo(Duration.ofMillis(900));
        }
    }

    private long committedOffset() throws Exception {
        try (Admin admin = admin()) {
            var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS);
            return offsets.getOrDefault(new TopicPartition(SOURCE, 0), new org.apache.kafka.clients.consumer.OffsetAndMetadata(0)).offset();
        }
    }

    private static void ensureTopic(String topic) {
        try (Admin admin = admin()) {
            if (!admin.listTopics().names().get(5, TimeUnit.SECONDS).contains(topic)) {
                admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create test topic " + topic, exception);
        }
    }

    private static void deleteTopic(String topic) {
        try (Admin admin = admin()) {
            admin.deleteTopics(List.of(topic)).all().get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    !admin.listTopics().names().get(5, TimeUnit.SECONDS).contains(topic));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot delete test topic " + topic, exception);
        }
    }

    private static Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
    }

    private ConsumerRecord<String, String> consumeMatching(String topic, String key, Duration timeout) {
        ConsumerRecord<String, String> result = consumeOptional(topic, key, timeout);
        assertThat(result).as("matching record on " + topic).isNotNull();
        return result;
    }

    private ConsumerRecord<String, String> consumeOptional(String topic, String key, Duration timeout) {
        var match = new java.util.concurrent.atomic.AtomicReference<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            try {
                await().atMost(timeout).pollInterval(Duration.ofMillis(100)).until(() -> {
                    consumer.poll(Duration.ofMillis(100)).forEach(record -> {
                        if (key.equals(record.key())) match.compareAndSet(null, record);
                    });
                    return match.get() != null;
                });
            } catch (org.awaitility.core.ConditionTimeoutException ignored) {
                // Absence is the behavior asserted by transient-recovery tests.
            }
        }
        return match.get();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RetryProbeConfiguration {
        @Bean
        RetryProbe retryProbe() {
            return new RetryProbe();
        }
    }

    static final class RetryProbe implements RetryListener {
        private final Map<String, CopyOnWriteArrayList<Instant>> deliveries = new ConcurrentHashMap<>();
        private final AtomicInteger recoveryFailures = new AtomicInteger();

        @Override
        public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int deliveryAttempt) {
            deliveries.computeIfAbsent(String.valueOf(record.key()), ignored -> new CopyOnWriteArrayList<>())
                    .add(Instant.now());
        }

        @Override
        public void recoveryFailed(ConsumerRecord<?, ?> record, Exception original, Exception failure) {
            recoveryFailures.incrementAndGet();
        }

        List<Instant> attempts(String key) {
            return List.copyOf(deliveries.getOrDefault(key, new CopyOnWriteArrayList<>()));
        }

        int recoveryFailures() {
            return recoveryFailures.get();
        }

        void clear() {
            deliveries.clear();
            recoveryFailures.set(0);
        }
    }
}
