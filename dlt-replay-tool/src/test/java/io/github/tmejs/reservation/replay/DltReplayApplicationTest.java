package io.github.tmejs.reservation.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;

class DltReplayApplicationTest {
    private static final String DLT = "orders.v1.DLT";
    private static final String SOURCE = "orders.v1";
    private static final TopicPartition DLT_PARTITION = new TopicPartition(DLT, 0);

    @Test
    void preservesNewlinesNulAndEmptyBytesAndPublishesExactlyOneRecord() throws Exception {
        byte[] key = new byte[] {'k', '\n', 0, 'z'};
        byte[] value = new byte[] {'{', '\n', 0, '}'};
        var fixture = fixture(7, key, value);
        fixture.consumer.schedulePollTask(() -> fixture.consumer.addRecord(record(8, "later".getBytes(), new byte[0])));

        var result = DltReplayApplication.replay(fixture.consumer, fixture.producer,
                new DltReplayApplication.ReplayRequest(DLT, 0, 7, SOURCE));

        assertEquals(SOURCE, result.topic());
        assertEquals(0, result.partition());
        assertEquals(1, fixture.producer.history().size());
        assertArrayEquals(key, fixture.producer.history().getFirst().key());
        assertArrayEquals(value, fixture.producer.history().getFirst().value());
        assertNull(fixture.consumer.committed(java.util.Set.of(DLT_PARTITION), Duration.ZERO).get(DLT_PARTITION));
    }

    @Test
    void preservesNullKeyAndValue() throws Exception {
        var fixture = fixture(3, null, null);

        DltReplayApplication.replay(fixture.consumer, fixture.producer,
                new DltReplayApplication.ReplayRequest(DLT, 0, 3, SOURCE));

        assertNull(fixture.producer.history().getFirst().key());
        assertNull(fixture.producer.history().getFirst().value());
    }

    @Test
    void preservesEmptyKeyAndValue() throws Exception {
        var fixture = fixture(4, new byte[0], new byte[0]);

        DltReplayApplication.replay(fixture.consumer, fixture.producer,
                new DltReplayApplication.ReplayRequest(DLT, 0, 4, SOURCE));

        assertArrayEquals(new byte[0], fixture.producer.history().getFirst().key());
        assertArrayEquals(new byte[0], fixture.producer.history().getFirst().value());
    }

    @Test
    void rejectsARecordAtAnyOffsetOtherThanTheExactRequestedOffset() {
        var consumer = consumerAt(7);
        consumer.schedulePollTask(() -> consumer.addRecord(record(8, new byte[0], new byte[0])));
        var producer = new MockProducer<byte[], byte[]>(
                true, null, new ByteArraySerializer(), new ByteArraySerializer());

        assertThrows(IllegalStateException.class, () -> DltReplayApplication.replay(consumer, producer,
                new DltReplayApplication.ReplayRequest(DLT, 0, 7, SOURCE)));
        assertEquals(List.of(), producer.history());
    }

    @Test
    void rejectsNonAllowlistedTopicPairs() {
        var fixture = fixture(0, new byte[0], new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> DltReplayApplication.replay(
                fixture.consumer, fixture.producer,
                new DltReplayApplication.ReplayRequest(DLT, 0, 0, "other.v1")));
    }

    private static Fixture fixture(long offset, byte[] key, byte[] value) {
        var consumer = consumerAt(offset);
        consumer.schedulePollTask(() -> consumer.addRecord(record(offset, key, value)));
        return new Fixture(consumer, new MockProducer<byte[], byte[]>(
                true, null, new ByteArraySerializer(), new ByteArraySerializer()));
    }

    private static MockConsumer<byte[], byte[]> consumerAt(long offset) {
        var consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.NONE);
        consumer.updateBeginningOffsets(Map.of(DLT_PARTITION, offset));
        consumer.updateEndOffsets(Map.of(DLT_PARTITION, offset + 10));
        return consumer;
    }

    private static ConsumerRecord<byte[], byte[]> record(long offset, byte[] key, byte[] value) {
        var headers = new RecordHeaders()
                .add("kafka_dlt-original-topic", SOURCE.getBytes(StandardCharsets.UTF_8))
                .add("kafka_dlt-original-partition", ByteBuffer.allocate(Integer.BYTES).putInt(0).array());
        return new ConsumerRecord<>(DLT, 0, offset, -1L,
                org.apache.kafka.common.record.TimestampType.NO_TIMESTAMP_TYPE,
                -1, -1, key, value, headers, java.util.Optional.empty());
    }

    private record Fixture(MockConsumer<byte[], byte[]> consumer, MockProducer<byte[], byte[]> producer) {}
}
