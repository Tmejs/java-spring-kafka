package io.github.tmejs.reservation.orders.order;

import io.github.tmejs.reservation.events.EventCodec;
import io.github.tmejs.reservation.events.EventMetadata;
import io.github.tmejs.reservation.events.OrderCreated;
import io.github.tmejs.reservation.events.OrderLine;
import io.github.tmejs.reservation.orders.idempotency.IdempotencyRepository;
import io.github.tmejs.reservation.orders.messaging.OutboxEntity;
import io.github.tmejs.reservation.orders.messaging.OutboxRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderService {

    private static final String OUTBOX_TOPIC = "orders.v1";

    private final OrderRepository orders;
    private final IdempotencyRepository idempotency;
    private final OutboxRepository outbox;
    private final EventCodec eventCodec;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    OrderService(
            OrderRepository orders,
            IdempotencyRepository idempotency,
            OutboxRepository outbox,
            EventCodec eventCodec,
            ObjectMapper objectMapper,
            Clock clock) {
        this.orders = orders;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.eventCodec = eventCodec;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public CreationResult create(String ownerSubject, String idempotencyKey, CreateOrderCommand command) {
        List<Line> canonicalItems = validateAndCanonicalize(command);
        String fingerprint = fingerprint(canonicalItems);
        Instant createdAt = clock.instant();

        if (!idempotency.tryClaim(ownerSubject, idempotencyKey, fingerprint, createdAt)) {
            var stored = idempotency.find(ownerSubject, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Conflicting idempotency claim disappeared"));
            if (!fingerprint.equals(stored.fingerprint())) {
                throw new IdempotencyConflictException();
            }
            if (stored.payload() == null || stored.location() == null) {
                throw new IllegalStateException("Completed idempotency response is missing");
            }
            return new CreationResult(decodeResponse(stored.payload()), stored.location());
        }

        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var entity = new OrderEntity(orderId, ownerSubject, createdAt);
        canonicalItems.forEach(line -> entity.addItem(UUID.randomUUID(), line.productId(), line.quantity()));
        orders.saveAndFlush(entity);

        var event = new OrderCreated(
                new EventMetadata(
                        eventId,
                        EventCodec.ORDER_CREATED,
                        EventCodec.SCHEMA_VERSION,
                        createdAt,
                        orderId),
                canonicalItems.stream()
                        .map(line -> new OrderLine(line.productId(), line.quantity()))
                        .toList());
        outbox.save(new OutboxEntity(
                UUID.randomUUID(),
                eventId,
                orderId,
                EventCodec.ORDER_CREATED,
                OUTBOX_TOPIC,
                eventCodec.encode(event),
                createdAt));

        var response = new OrderView(orderId, canonicalItems, "PENDING", null);
        String location = "/orders/" + orderId;
        idempotency.complete(ownerSubject, idempotencyKey, orderId, encodeResponse(response), location);
        return new CreationResult(response, location);
    }

    @Transactional(readOnly = true)
    public OrderView get(String ownerSubject, UUID orderId) {
        OrderEntity order = orders.findByIdAndOwnerSubject(orderId, ownerSubject)
                .orElseThrow(OrderNotFoundException::new);
        List<Line> items = order.getItems().stream()
                .map(item -> new Line(item.getProductId(), item.getQuantity()))
                .toList();
        return new OrderView(order.getId(), items, order.getStatus(), order.getRejectionReason());
    }

    private static List<Line> validateAndCanonicalize(CreateOrderCommand command) {
        if (command == null || command.items() == null || command.items().isEmpty()) {
            throw new InvalidOrderRequestException("At least one item is required");
        }
        var productIds = new HashSet<UUID>();
        for (Line item : command.items()) {
            if (item == null || item.productId() == null || item.quantity() < 1) {
                throw new InvalidOrderRequestException("Every item requires a product and positive quantity");
            }
            if (!productIds.add(item.productId())) {
                throw new InvalidOrderRequestException("Product identifiers must be distinct");
            }
        }
        return command.items().stream()
                .sorted(Comparator.comparing(line -> line.productId().toString()))
                .toList();
    }

    /**
     * Stable SHA-256 over UTF-8 lines formatted as lowercase UUID, colon, decimal quantity, newline,
     * after sorting by the UUID string.
     */
    private static String fingerprint(List<Line> items) {
        var canonical = new StringBuilder();
        items.forEach(item -> canonical.append(item.productId())
                .append(':')
                .append(item.quantity())
                .append('\n'));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String encodeResponse(OrderView response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Order response cannot be serialized", exception);
        }
    }

    private OrderView decodeResponse(String payload) {
        try {
            return objectMapper.readValue(payload, OrderView.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored order response cannot be deserialized", exception);
        }
    }

    public record CreateOrderCommand(List<Line> items) {
        public CreateOrderCommand {
            items = items == null ? null : List.copyOf(items);
        }
    }

    public record Line(UUID productId, int quantity) {}

    public record OrderView(UUID id, List<Line> items, String status, String rejectionReason) {
        public OrderView {
            items = items == null ? null : List.copyOf(items);
        }
    }

    public record CreationResult(OrderView response, String location) {}

    public static final class InvalidOrderRequestException extends RuntimeException {
        InvalidOrderRequestException(String message) {
            super(message);
        }
    }

    public static final class IdempotencyConflictException extends RuntimeException {
        IdempotencyConflictException() {
            super("The idempotency key was already used with a different request");
        }
    }

    public static final class OrderNotFoundException extends RuntimeException {
        OrderNotFoundException() {
            super("Order not found");
        }
    }
}
