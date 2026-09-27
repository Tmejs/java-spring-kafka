package io.github.tmejs.reservation.inventory.product;

final class StockOverflowException extends RuntimeException {

    StockOverflowException() {
        super("Adding stock would exceed the maximum supported quantity");
    }
}
