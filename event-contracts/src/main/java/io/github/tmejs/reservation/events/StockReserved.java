package io.github.tmejs.reservation.events;

import java.util.UUID;

public record StockReserved(EventMetadata metadata, UUID causationId) {}
