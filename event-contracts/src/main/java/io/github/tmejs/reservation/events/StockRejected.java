package io.github.tmejs.reservation.events;

import java.util.UUID;

public record StockRejected(EventMetadata metadata, UUID causationId, String reason) {}
