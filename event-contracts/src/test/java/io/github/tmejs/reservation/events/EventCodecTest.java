package io.github.tmejs.reservation.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EventCodecTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID CAUSATION_ID = UUID.fromString("00000000-0000-4000-8000-000000000004");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-26T10:15:30Z");

    private final EventCodec codec = new EventCodec();

    @Test
    void roundTripsEveryV1EventWithoutJavaTypeMetadata() {
        var created = new OrderCreated(
                metadata("OrderCreated"), List.of(new OrderLine(PRODUCT_ID, 3)));
        var reserved = new StockReserved(metadata("StockReserved"), CAUSATION_ID);
        var rejected = new StockRejected(metadata("StockRejected"), CAUSATION_ID, "INSUFFICIENT_STOCK");

        String createdJson = codec.encode(created);
        String reservedJson = codec.encode(reserved);
        String rejectedJson = codec.encode(rejected);

        assertThat(codec.decodeOrderCreated(createdJson)).isEqualTo(created);
        assertThat(codec.decodeStockReserved(reservedJson)).isEqualTo(reserved);
        assertThat(codec.decodeStockRejected(rejectedJson)).isEqualTo(rejected);
        assertThat(createdJson).contains("\"eventType\":\"OrderCreated\"", "\"schemaVersion\":1");
        assertThat(createdJson).doesNotContain("@class", "java.", "io.github.tmejs");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> codec.decodeOrderCreated("{not-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed event JSON");
    }

    @Test
    void rejectsWrongEventType() throws Exception {
        var json = new ObjectMapper().writeValueAsString(
                new OrderCreated(metadata("StockReserved"), List.of(new OrderLine(PRODUCT_ID, 3))));

        assertThatThrownBy(() -> codec.decodeOrderCreated(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event type")
                .hasMessageContaining("OrderCreated");
    }

    @Test
    void rejectsUnsupportedSchemaVersion() throws Exception {
        var unsupported = new EventMetadata(EVENT_ID, "OrderCreated", 2, OCCURRED_AT, ORDER_ID);
        var json = new ObjectMapper().writeValueAsString(
                new OrderCreated(unsupported, List.of(new OrderLine(PRODUCT_ID, 3))));

        assertThatThrownBy(() -> codec.decodeOrderCreated(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema version")
                .hasMessageContaining("2");
    }

    private static EventMetadata metadata(String eventType) {
        return new EventMetadata(EVENT_ID, eventType, 1, OCCURRED_AT, ORDER_ID);
    }
}
