package io.github.tmejs.reservation.replay;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

public final class DltReplayApplication {
    private static final Map<String, String> ALLOWED_TOPICS = Map.of(
            "orders.v1.DLT", "orders.v1",
            "reservation-results.v1.DLT", "reservation-results.v1");
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final String ORIGINAL_TOPIC = "kafka_dlt-original-topic";
    private static final String ORIGINAL_PARTITION = "kafka_dlt-original-partition";

    private DltReplayApplication() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: <bootstrap-servers> <dlt-topic> <partition> <offset>");
        }
        String expectedSource = ALLOWED_TOPICS.get(args[1]);
        if (expectedSource == null) {
            throw new IllegalArgumentException("Unsupported DLT topic: " + args[1]);
        }
        int partition = nonNegativeInt(args[2], "partition");
        long offset = nonNegativeLong(args[3], "offset");

        try (var consumer = consumer(args[0]); var producer = producer(args[0])) {
            var result = replay(consumer, producer, new ReplayRequest(args[1], partition, offset, expectedSource));
            System.out.printf("Replayed one record to %s[%d]@%d; raw key and value were copied unchanged.%n",
                    result.topic(), result.partition(), result.offset());
        }
    }

    static ReplayResult replay(
            Consumer<byte[], byte[]> consumer,
            Producer<byte[], byte[]> producer,
            ReplayRequest request) throws Exception {
        if (!request.sourceTopic().equals(ALLOWED_TOPICS.get(request.dltTopic()))) {
            throw new IllegalArgumentException("DLT/source topic pair is not allowlisted");
        }
        var dltPartition = new TopicPartition(request.dltTopic(), request.partition());
        consumer.assign(Set.of(dltPartition));
        consumer.seek(dltPartition, request.offset());

        long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
        org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]> selected = null;
        while (selected == null && System.nanoTime() < deadline) {
            var remaining = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
            for (var record : consumer.poll(remaining.compareTo(Duration.ofSeconds(1)) > 0
                    ? Duration.ofSeconds(1) : remaining).records(dltPartition)) {
                if (record.offset() != request.offset()) {
                    throw new IllegalStateException("Broker returned offset " + record.offset()
                            + " while exact offset " + request.offset() + " was requested");
                }
                selected = record;
                break;
            }
        }
        if (selected == null) {
            throw new IllegalStateException("No record exists at the requested DLT partition and offset");
        }

        String originalTopic = utf8Header(selected.headers().lastHeader(ORIGINAL_TOPIC), ORIGINAL_TOPIC);
        if (!request.sourceTopic().equals(originalTopic)) {
            throw new IllegalStateException("DLT original topic header does not match the allowlisted source");
        }
        int originalPartition = intHeader(selected.headers().lastHeader(ORIGINAL_PARTITION));
        var published = producer.send(new ProducerRecord<>(
                        request.sourceTopic(), originalPartition, selected.key(), selected.value()))
                .get(10, TimeUnit.SECONDS);
        producer.flush();
        return new ReplayResult(published.topic(), published.partition(), published.offset());
    }

    private static KafkaConsumer<byte[], byte[]> consumer(String bootstrapServers) {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, "manual-dlt-replay-" + UUID.randomUUID());
        return new KafkaConsumer<>(properties);
    }

    private static KafkaProducer<byte[], byte[]> producer(String bootstrapServers) {
        var properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, "manual-dlt-replay-" + UUID.randomUUID());
        return new KafkaProducer<>(properties);
    }

    private static String utf8Header(Header header, String name) {
        if (header == null || header.value() == null) {
            throw new IllegalStateException("Missing required DLT header: " + name);
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static int intHeader(Header header) {
        if (header == null || header.value() == null || header.value().length != Integer.BYTES) {
            throw new IllegalStateException("Missing or invalid DLT original partition header");
        }
        int value = ByteBuffer.wrap(header.value()).getInt();
        if (value < 0) {
            throw new IllegalStateException("DLT original partition header is negative");
        }
        return value;
    }

    private static int nonNegativeInt(String value, String name) {
        long parsed = nonNegativeLong(value, name);
        if (parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is too large");
        }
        return (int) parsed;
    }

    private static long nonNegativeLong(String value, String name) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw new IllegalArgumentException(name + " must be non-negative");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a non-negative integer", exception);
        }
    }

    record ReplayRequest(String dltTopic, int partition, long offset, String sourceTopic) {}
    record ReplayResult(String topic, int partition, long offset) {}
}
