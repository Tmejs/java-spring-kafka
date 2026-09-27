package io.github.tmejs.reservation.inventory.product;

import java.util.UUID;

final class ProductNotFoundException extends RuntimeException {

    ProductNotFoundException(UUID productId) {
        super("Product " + productId + " does not exist");
    }
}
