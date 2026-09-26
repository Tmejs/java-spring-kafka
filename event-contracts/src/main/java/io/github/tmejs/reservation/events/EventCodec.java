package io.github.tmejs.reservation.events;

import java.util.Objects;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class EventCodec {

    public static final int SCHEMA_VERSION = 1;
    public static final String ORDER_CREATED = "OrderCreated";
    public static final String STOCK_RESERVED = "StockReserved";
    public static final String STOCK_REJECTED = "StockRejected";

    private final ObjectMapper objectMapper;

    public EventCodec() {
        this(new ObjectMapper());
    }

    public EventCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String encode(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Event cannot be encoded as JSON", exception);
        }
    }

    public OrderCreated decodeOrderCreated(String json) {
        return decode(json, OrderCreated.class, OrderCreated::metadata, ORDER_CREATED);
    }

    public StockReserved decodeStockReserved(String json) {
        return decode(json, StockReserved.class, StockReserved::metadata, STOCK_RESERVED);
    }

    public StockRejected decodeStockRejected(String json) {
        return decode(json, StockRejected.class, StockRejected::metadata, STOCK_REJECTED);
    }

    private <T> T decode(
            String json, Class<T> eventClass, Function<T, EventMetadata> metadataExtractor, String expectedType) {
        final T event;
        try {
            event = objectMapper.readValue(json, eventClass);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Malformed event JSON", exception);
        }

        EventMetadata metadata = metadataExtractor.apply(event);
        if (metadata == null) {
            throw new IllegalArgumentException("Event metadata is required");
        }
        if (!expectedType.equals(metadata.eventType())) {
            throw new IllegalArgumentException(
                    "Expected event type " + expectedType + " but received " + metadata.eventType());
        }
        if (metadata.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported schema version " + metadata.schemaVersion());
        }
        return event;
    }
}
