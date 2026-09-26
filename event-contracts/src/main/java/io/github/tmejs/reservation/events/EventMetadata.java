package io.github.tmejs.reservation.events;

import java.time.Instant;
import java.util.UUID;

public record EventMetadata(
        UUID eventId, String eventType, int schemaVersion, Instant occurredAt, UUID orderId) {}
