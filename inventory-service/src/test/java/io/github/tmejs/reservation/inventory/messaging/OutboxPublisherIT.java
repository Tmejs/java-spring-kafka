package io.github.tmejs.reservation.inventory.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.tmejs.reservation.inventory.InventoryApplication;
import io.github.tmejs.reservation.inventory.support.InventoryPostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(classes = InventoryApplication.class, properties = {
        "reservation.outbox.batch-size=2", "reservation.outbox.scheduling-enabled=false",
        "reservation.outbox.ack-timeout=10s", "reservation.outbox.backoff-initial=10ms",
        "reservation.outbox.backoff-max=100ms", "logging.level.org.apache.kafka=WARN"})
class OutboxPublisherIT extends InventoryPostgresIntegrationTest {
    private static final String TOPIC = "reservation-results.v1";
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:4.1.1");

    static { KAFKA.start(); }

    @Autowired private OutboxPublisher publisher;
    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void clearOutbox() {
        jdbc.execute("truncate table outbox, processed_events, reservations cascade");
    }

    @Test
    void publishesBoundedBatchWithOrderKeyAndMarksAcknowledgedRows() {
        List<TestEvent> events = List.of(insert(TOPIC), insert(TOPIC), insert(TOPIC));
        publisher.publishBatch();

        List<Published> published = consume(2,
                copy -> events.stream().anyMatch(event -> event.payload().equals(copy.payload())));
        assertThat(published).extracting(Published::key)
                .containsExactlyInAnyOrder(events.get(0).orderId().toString(), events.get(1).orderId().toString());
        assertThat(published).extracting(Published::payload)
                .containsExactlyInAnyOrder(events.get(0).payload(), events.get(1).payload());
        assertThat(jdbc.queryForObject("select count(*) from outbox where published_at is not null", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from outbox where published_at is null", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void failedRowStaysPendingWithBackoffAndDoesNotBlockNextRow() {
        TestEvent failed = insert("invalid topic name");
        TestEvent succeeding = insert(TOPIC);
        publisher.publishBatch();

        assertThat(consume(1, copy -> copy.payload().equals(succeeding.payload())))
                .extracting(Published::payload).containsExactly(succeeding.payload());
        Map<String, Object> failedRow = jdbc.queryForMap(
                "select attempt_count, next_attempt_at, published_at, last_error from outbox where id = ?",
                failed.rowId());
        assertThat(failedRow.get("attempt_count")).isEqualTo(1);
        assertThat(failedRow.get("published_at")).isNull();
        assertThat(failedRow.get("last_error")).asString().isNotBlank();
        assertThat(jdbc.queryForObject(
                        "select next_attempt_at from outbox where id = ?", Instant.class, failed.rowId()))
                .isAfter(Instant.now().minusSeconds(1));

        jdbc.update("update outbox set topic = ?, next_attempt_at = now() where id = ?", TOPIC, failed.rowId());
        publisher.publishBatch();
        assertThat(consume(1, copy -> copy.payload().equals(failed.payload())))
                .extracting(Published::payload).containsExactly(failed.payload());
        assertThat(jdbc.queryForObject(
                        "select published_at from outbox where id = ?", Instant.class, failed.rowId()))
                .isNotNull();
    }

    @Test
    void restartAfterSendBeforeMarkRepublishesSameEventIdAndPayload() throws Exception {
        TestEvent original = insert(TOPIC);
        kafka.send(new ProducerRecord<>(TOPIC, original.orderId().toString(), original.payload()))
                .get(10, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject(
                "select count(*) from outbox where id = ? and published_at is null", Integer.class, original.rowId()))
                .isOne();

        publisher.publishBatch();

        List<Published> copies = consume(2, copy -> copy.payload().equals(original.payload()));
        assertThat(copies).allMatch(copy -> copy.key().equals(original.orderId().toString()));
        assertThat(copies).allMatch(copy -> copy.payload().contains(original.eventId().toString()));
        assertThat(jdbc.queryForObject("select published_at from outbox where id = ?", Instant.class, original.rowId()))
                .isNotNull();
    }

    private TestEvent insert(String topic) {
        UUID rowId = UUID.randomUUID(); UUID eventId = UUID.randomUUID(); UUID orderId = UUID.randomUUID();
        String payload = "{\"metadata\":{\"eventId\":\"" + eventId + "\",\"orderId\":\"" + orderId + "\"}}";
        jdbc.update("insert into outbox(id,event_id,order_id,event_type,topic,payload,created_at,next_attempt_at,attempt_count) "
                + "values (?,?,?,?,?,?,now(),now(),0)",
                rowId, eventId, orderId, "ReservationResult", topic, payload);
        return new TestEvent(rowId, eventId, orderId, payload);
    }

    private List<Published> consume(int expected, Predicate<Published> filter) {
        var result = new ArrayList<Published>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(TOPIC));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    Published published = new Published(record.key(), record.value());
                    if (filter.test(published)) result.add(published);
                });
                assertThat(result).hasSizeGreaterThanOrEqualTo(expected);
            });
        }
        return result.subList(0, expected);
    }

    private record TestEvent(UUID rowId, UUID eventId, UUID orderId, String payload) {}
    private record Published(String key, String payload) {}
}
