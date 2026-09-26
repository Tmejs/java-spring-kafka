package io.github.tmejs.reservation.events;

import java.util.UUID;

public record OrderLine(UUID productId, int quantity) {}
