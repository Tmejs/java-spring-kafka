package io.github.tmejs.reservation.inventory.reservation;

import io.github.tmejs.reservation.events.EventCodec;
import io.github.tmejs.reservation.events.EventMetadata;
import io.github.tmejs.reservation.events.OrderCreated;
import io.github.tmejs.reservation.events.OrderLine;
import io.github.tmejs.reservation.events.StockRejected;
import io.github.tmejs.reservation.events.StockReserved;
import io.github.tmejs.reservation.inventory.messaging.OutboxEntity;
import io.github.tmejs.reservation.inventory.messaging.OutboxRepository;
import io.github.tmejs.reservation.inventory.product.ProductEntity;
import io.github.tmejs.reservation.inventory.product.ProductRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {
    static final String UNKNOWN_PRODUCT = "UNKNOWN_PRODUCT";
    static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    private static final String RESULT_TOPIC = "reservation-results.v1";

    private final ReservationRepository reservations;
    private final ProductRepository products;
    private final OutboxRepository outbox;
    private final EventCodec codec;
    private final Clock clock;

    public ReservationService(
            ReservationRepository reservations,
            ProductRepository products,
            OutboxRepository outbox,
            EventCodec codec,
            Clock clock) {
        this.reservations = reservations;
        this.products = products;
        this.outbox = outbox;
        this.codec = codec;
        this.clock = clock;
    }

    @Transactional
    public void reserve(OrderCreated event) {
        List<OrderLine> items = validateAndCanonicalize(event);
        String fingerprint = fingerprint(items);
        EventMetadata source = event.metadata();
        Instant decidedAt = clock.instant();

        reservations.lockOrder(source.orderId());
        boolean newEvent = reservations.claimEvent(source.eventId(), decidedAt);
        var existing = reservations.findDecision(source.orderId());
        if (!newEvent) {
            verifyExisting(existing.orElseThrow(() ->
                    new IllegalStateException("Processed event has no reservation decision")), fingerprint);
            return;
        }
        if (existing.isPresent()) {
            verifyExisting(existing.get(), fingerprint);
            return;
        }

        List<UUID> productIds = items.stream().map(OrderLine::productId).toList();
        Map<UUID, ProductEntity> lockedProducts = products.findAllByIdForUpdate(productIds).stream()
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));

        String rejectionReason = null;
        if (lockedProducts.size() != productIds.size()) {
            rejectionReason = UNKNOWN_PRODUCT;
        } else if (items.stream().anyMatch(item ->
                lockedProducts.get(item.productId()).getAvailableQuantity() < item.quantity())) {
            rejectionReason = INSUFFICIENT_STOCK;
        }

        if (rejectionReason == null) {
            items.forEach(item -> lockedProducts.get(item.productId()).reserve(item.quantity()));
        }
        String outcome = rejectionReason == null ? "RESERVED" : "REJECTED";
        if (!reservations.insertDecision(
                source.orderId(), source.eventId(), fingerprint, outcome, rejectionReason, decidedAt)) {
            throw new IllegalStateException("Reservation decision was concurrently created");
        }
        saveResult(source, decidedAt, rejectionReason);
    }

    private void saveResult(EventMetadata source, Instant decidedAt, String rejectionReason) {
        UUID resultEventId = UUID.randomUUID();
        Object result;
        String eventType;
        if (rejectionReason == null) {
            eventType = EventCodec.STOCK_RESERVED;
            result = new StockReserved(
                    new EventMetadata(resultEventId, eventType, EventCodec.SCHEMA_VERSION, decidedAt, source.orderId()),
                    source.eventId());
        } else {
            eventType = EventCodec.STOCK_REJECTED;
            result = new StockRejected(
                    new EventMetadata(resultEventId, eventType, EventCodec.SCHEMA_VERSION, decidedAt, source.orderId()),
                    source.eventId(), rejectionReason);
        }
        outbox.save(new OutboxEntity(
                UUID.randomUUID(), resultEventId, source.orderId(), eventType, RESULT_TOPIC, codec.encode(result), decidedAt));
    }

    private static void verifyExisting(ReservationRepository.Decision decision, String fingerprint) {
        if (!decision.itemsFingerprint().equals(fingerprint)) {
            throw new IllegalStateException("Repeated order has a different items fingerprint");
        }
    }

    private static List<OrderLine> validateAndCanonicalize(OrderCreated event) {
        if (event == null || event.metadata() == null || event.metadata().eventId() == null
                || event.metadata().orderId() == null || event.items() == null || event.items().isEmpty()) {
            throw new IllegalArgumentException("OrderCreated metadata and items are required");
        }
        if (!EventCodec.ORDER_CREATED.equals(event.metadata().eventType())
                || event.metadata().schemaVersion() != EventCodec.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported OrderCreated contract");
        }
        var distinctProducts = new HashSet<UUID>();
        for (OrderLine item : event.items()) {
            if (item == null || item.productId() == null || item.quantity() < 1) {
                throw new IllegalArgumentException("Every order item requires a product and positive quantity");
            }
            if (!distinctProducts.add(item.productId())) {
                throw new IllegalArgumentException("Order item product identifiers must be distinct");
            }
        }
        return event.items().stream()
                .sorted(Comparator.comparing(line -> line.productId().toString()))
                .toList();
    }

    private static String fingerprint(List<OrderLine> items) {
        var canonical = new StringBuilder();
        items.forEach(item -> canonical.append(item.productId())
                .append(':')
                .append(item.quantity())
                .append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
